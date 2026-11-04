package be.lain.bloom.data.food

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import be.lain.bloom.domain.FoodCandidate
import be.lain.bloom.domain.FoodSource
import be.lain.bloom.domain.Nutrients
import be.lain.bloom.domain.Serving
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Offline USDA SR Legacy database bundled as an asset (see scripts/build_foods.py). */
@Singleton
class GenericFoodDb @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    private var db: SQLiteDatabase? = null
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun open(): SQLiteDatabase = mutex.withLock {
        db?.let { return it }
        val file = File(context.noBackupFilesDir, ASSET)
        val versionFile = File(context.noBackupFilesDir, "$ASSET.version")
        val assetVersion = "$ASSET_VERSION"
        if (!file.exists() || versionFile.takeIf { it.exists() }?.readText() != assetVersion) {
            context.assets.open(ASSET).use { input -> file.outputStream().use { input.copyTo(it) } }
            versionFile.writeText(assetVersion)
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also { db = it }
    }

    suspend fun byId(id: String): FoodCandidate? = withContext(Dispatchers.IO) {
        val d = open()
        query(d, "SELECT $COLUMNS FROM foods f WHERE f.id = ?", arrayOf(id)).firstOrNull()
    }

    suspend fun search(query: String, limit: Int = 25): List<FoodCandidate> = withContext(Dispatchers.IO) {
        val match = ftsQuery(query) ?: return@withContext emptyList()
        // Shorter names first: "Bananas, raw" beats "Bread, banana, prepared from recipe".
        query(
            open(),
            """SELECT $COLUMNS FROM foods f JOIN foods_fts ON foods_fts.docid = f.id
               WHERE foods_fts MATCH ? ORDER BY (f.name LIKE ? || '%') DESC, length(f.name) LIMIT ?""",
            arrayOf(match, query.trim().split(' ').first(), limit.toString()),
        )
    }

    private fun query(d: SQLiteDatabase, sql: String, args: Array<String>): List<FoodCandidate> {
        val out = ArrayList<FoodCandidate>()
        d.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                fun d(i: Int) = if (c.isNull(i)) null else c.getDouble(i)
                val portions = runCatching {
                    json.parseToJsonElement(c.getString(10)).jsonArray.map {
                        val o = it.jsonObject
                        Serving(o["n"]!!.jsonPrimitive.content, o["g"]!!.jsonPrimitive.doubleOrNull ?: 0.0)
                    }
                }.getOrDefault(emptyList())
                out += FoodCandidate(
                    source = FoodSource.GENERIC,
                    name = c.getString(1),
                    externalId = c.getLong(0).toString(),
                    per100 = Nutrients(d(2), d(3), d(4), d(5), d(6), d(7), d(8), d(9)),
                    servings = portions,
                )
            }
        }
        return out
    }

    companion object {
        private const val COLUMNS = "f.id, f.name, f.kcal, f.protein, f.carbs, f.sugar, f.fat, f.sat_fat, f.fiber, f.salt, f.portions"
        const val ASSET = "generic_foods.db"
        private const val ASSET_VERSION = 1

        /** "brown rice" -> "brown* rice*" (prefix match on every word). */
        fun ftsQuery(q: String): String? {
            val words = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
            return if (words.isEmpty()) null else words.joinToString(" ") { "$it*" }
        }
    }
}

/** Open Food Facts lookups (barcode and text search). */
@Singleton
class OpenFoodFacts @Inject constructor(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun barcode(code: String): FoodCandidate? = withContext(Dispatchers.IO) {
        val url = "https://world.openfoodfacts.org/api/v2/product/$code.json".toHttpUrl().newBuilder()
            .addQueryParameter("fields", FIELDS).build()
        val body = get(url.toString()) ?: return@withContext null
        val root = json.parseToJsonElement(body).jsonObject
        if (root["status"]?.jsonPrimitive?.contentOrNull != "1") return@withContext null
        root["product"]?.jsonObject?.let { parse(it, code) }
    }

    suspend fun search(query: String): List<FoodCandidate> = withContext(Dispatchers.IO) {
        val url = "https://search.openfoodfacts.org/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page_size", "20")
            .addQueryParameter("fields", FIELDS)
            .build()
        val body = runCatching { get(url.toString()) }.getOrNull()
        val hits = body?.let { runCatching { json.parseToJsonElement(it).jsonObject["hits"]?.jsonArray }.getOrNull() }
        val list = hits ?: legacySearch(query)
        list.mapNotNull { runCatching { parse(it.jsonObject, null) }.getOrNull() }
    }

    private fun legacySearch(query: String): JsonArray {
        val url = "https://world.openfoodfacts.org/cgi/search.pl".toHttpUrl().newBuilder()
            .addQueryParameter("search_terms", query)
            .addQueryParameter("search_simple", "1")
            .addQueryParameter("json", "1")
            .addQueryParameter("page_size", "20")
            .addQueryParameter("fields", FIELDS)
            .build()
        val body = get(url.toString()) ?: return JsonArray(emptyList())
        return json.parseToJsonElement(body).jsonObject["products"]?.jsonArray ?: JsonArray(emptyList())
    }

    private fun get(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IOException("Open Food Facts returned ${r.code}")
            return r.body?.string()
        }
    }

    companion object {
        const val USER_AGENT = "Bloom/1.0 (Android personal fitness app; github.com/LainsMain)"
        private const val FIELDS =
            "code,product_name,product_name_en,product_name_nl,product_name_fr,brands,quantity,serving_size,serving_quantity,nutriments,image_front_small_url"

        /** Parses an OFF product JSON object. Returns null when it has no usable name or energy. */
        fun parse(p: JsonObject, code: String?): FoodCandidate? {
            fun str(k: String) = (p[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val name = str("product_name_en") ?: str("product_name") ?: str("product_name_nl") ?: str("product_name_fr")
                ?: return null
            val n = p["nutriments"] as? JsonObject ?: return null
            fun num(k: String): Double? = (n[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.replace(',', '.')?.toDoubleOrNull() }
            val kcal = num("energy-kcal_100g") ?: num("energy_100g")?.let { it / 4.184 }
            val protein = num("proteins_100g")
            val carbs = num("carbohydrates_100g")
            val fat = num("fat_100g")
            val per100 = Nutrients(
                kcal = kcal ?: Nutrients.kcalFromMacros(protein, carbs, fat),
                protein = protein, carbs = carbs, sugar = num("sugars_100g"), fat = fat,
                satFat = num("saturated-fat_100g"), fiber = num("fiber_100g"),
                salt = num("salt_100g") ?: num("sodium_100g")?.times(2.5),
            )
            if (per100.kcal == null) return null
            val quantity = str("quantity").orEmpty().lowercase()
            val liquid = Regex("\\d\\s*(ml|cl|l)\\b").containsMatchIn(quantity)
            val servingG = (p["serving_quantity"] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
            val servings = buildList {
                if (servingG != null && servingG > 0) add(Serving(str("serving_size")?.take(40) ?: "1 serving", servingG))
                Regex("^(\\d+(?:[.,]\\d+)?)\\s*(g|ml)$").find(quantity)?.let { m ->
                    val g = m.groupValues[1].replace(',', '.').toDouble()
                    if (g > 0 && none { it.grams == g }) add(Serving("Whole pack ($quantity)", g))
                }
            }
            return FoodCandidate(
                source = FoodSource.OFF,
                name = name,
                brand = str("brands")?.split(',')?.firstOrNull()?.trim(),
                barcode = code ?: str("code"),
                externalId = code ?: str("code"),
                per100 = per100,
                servings = servings,
                liquid = liquid,
                imageUrl = str("image_front_small_url"),
            )
        }
    }
}
