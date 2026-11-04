package be.lain.bloom.data.food

import androidx.room.withTransaction
import be.lain.bloom.data.db.BloomDatabase
import be.lain.bloom.data.db.DayLogEntity
import be.lain.bloom.data.db.DiaryEntryEntity
import be.lain.bloom.data.db.FoodEntity
import be.lain.bloom.data.db.MealTemplateEntity
import be.lain.bloom.data.db.MealTemplateItemEntity
import be.lain.bloom.data.db.RecipeEntity
import be.lain.bloom.data.db.RecipeIngredientEntity
import be.lain.bloom.domain.DayStatus
import be.lain.bloom.domain.EntrySource
import be.lain.bloom.domain.FoodCandidate
import be.lain.bloom.domain.FoodSource
import be.lain.bloom.domain.Meal
import be.lain.bloom.domain.Nutrients
import be.lain.bloom.domain.Recipes
import be.lain.bloom.domain.Serving
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/** A quick protein-rich option for the rest of the day. */
data class ProteinIdea(
    val name: String,
    val portionLabel: String,
    val grams: Double,
    val amount: Double,
    val unit: String,
    val protein: Double,
    val kcal: Double,
    /** Saved food, or a basic food from the built-in database to save on first use. */
    val foodId: Long? = null,
    val candidate: FoodCandidate? = null,
)

/** At least ~30% of energy from protein and a worthwhile amount per portion. */
fun isProteinRich(proteinPer100: Double?, kcalPer100: Double?): Boolean =
    proteinPer100 != null && kcalPer100 != null && kcalPer100 > 0 && proteinPer100 * 4 / kcalPer100 >= 0.3

data class RecipeDraft(
    val id: Long? = null,
    val name: String,
    val servings: Int,
    val cookedWeightG: Double?,
    val ingredients: List<RecipeIngredientEntity>,
)

/** Basic high-protein foods (built-in database search, typical portion in grams). */
private val BASICS = listOf("skyr" to 150.0, "cottage cheese" to 150.0, "egg whole cooked" to 100.0, "chicken breast roasted" to 120.0, "tuna canned" to 100.0, "tofu" to 150.0)

sealed interface BarcodeResult {
    data class Found(val food: FoodEntity) : BarcodeResult
    data class NotFound(val code: String) : BarcodeResult
    data class Offline(val code: String, val message: String) : BarcodeResult
}

/** A natural single portion: "1 medium" beats "1 cup, mashed". */
fun preferredServing(servings: List<Serving>): Serving? =
    servings.firstOrNull { it.label.contains("medium", ignoreCase = true) }
        ?: servings.firstOrNull { it.label.contains("serving", ignoreCase = true) || it.label.contains("portion", ignoreCase = true) }
        ?: servings.firstOrNull { !Regex("cup|tbsp|tsp|tablespoon|teaspoon|oz", RegexOption.IGNORE_CASE).containsMatchIn(it.label) }
        ?: servings.firstOrNull()

@Singleton
class FoodRepository @Inject constructor(
    private val db: BloomDatabase,
    private val generic: GenericFoodDb,
    private val off: OpenFoodFacts,
    private val clock: Clock,
) {
    private val foods get() = db.foodDao()
    private val diary get() = db.diaryDao()

    fun today(): LocalDate = LocalDate.now(clock)
    fun defaultMeal(): Meal = Meal.forTime(LocalTime.now(clock))

    // ---- Foods ----
    fun observeFood(id: Long) = foods.observe(id)
    suspend fun food(id: Long) = foods.get(id)
    val recent: Flow<List<FoodEntity>> = foods.observeRecent()
    val favorites: Flow<List<FoodEntity>> = foods.observeFavorites()
    val myFoods: Flow<List<FoodEntity>> = foods.observeMine()

    suspend fun searchLocal(q: String): List<FoodCandidate> = foods.search(q.trim()).map { it.toCandidate() }
    suspend fun searchGeneric(q: String): List<FoodCandidate> = generic.search(q)
    suspend fun searchOnline(q: String): List<FoodCandidate> = off.search(q)

    /** Saves a search result locally (or finds the existing copy) so it can be logged and reused. */
    suspend fun materialize(c: FoodCandidate): FoodEntity {
        c.localId?.let { id -> foods.get(id)?.let { return it } }
        val existing = when {
            c.barcode != null -> foods.byBarcode(c.barcode)
            c.externalId != null -> foods.byExternal(c.externalId, c.source.name)
            else -> null
        }
        if (existing != null) return existing
        val now = clock.instant()
        val entity = FoodEntity(
            name = c.name, brand = c.brand, barcode = c.barcode, source = c.source, externalId = c.externalId,
            liquid = c.liquid, per100 = c.per100, servings = c.servings, createdAt = now, updatedAt = now,
        )
        return entity.copy(id = foods.insert(entity))
    }

    /** Creates or updates a user food. A barcode already used by another food is moved to this one. */
    suspend fun saveFood(food: FoodEntity): FoodEntity = db.withTransaction {
        val now = clock.instant()
        val code = food.barcode?.trim()?.takeIf { it.isNotEmpty() }
        if (code != null) {
            foods.byBarcode(code)?.takeIf { it.id != food.id }?.let { foods.update(it.copy(barcode = null, updatedAt = now)) }
        }
        val f = food.copy(barcode = code, updatedAt = now)
        if (f.id == 0L) f.copy(id = foods.insert(f.copy(createdAt = now))) else f.also { foods.update(it) }
    }

    suspend fun hideFood(id: Long) {
        foods.get(id)?.let { foods.update(it.copy(hidden = true, barcode = null, favorite = false)) }
    }

    suspend fun setFavorite(id: Long, fav: Boolean) = foods.setFavorite(id, fav)

    suspend fun lookupBarcode(code: String): BarcodeResult {
        foods.byBarcode(code)?.let { return BarcodeResult.Found(it) }
        return try {
            val found = off.barcode(code) ?: return BarcodeResult.NotFound(code)
            BarcodeResult.Found(materialize(found))
        } catch (e: Exception) {
            BarcodeResult.Offline(code, e.message ?: "No connection")
        }
    }

    // ---- Diary ----
    fun observeDay(date: LocalDate): Flow<List<DiaryEntryEntity>> = diary.observeDay(date)
    fun observeRange(from: LocalDate, to: LocalDate) = diary.observeRange(from, to)
    suspend fun range(from: LocalDate, to: LocalDate) = diary.range(from, to)
    suspend fun entry(id: Long) = diary.get(id)
    fun observeLoggedDays(from: LocalDate, to: LocalDate) = diary.observeLoggedDays(from, to)

    /** Amount to use for one-tap logging: last time's amount, else first serving, else 100 g. */
    /**
     * Protein-rich foods to suggest: the user's own favourites and recent foods first,
     * then a few basics from the built-in database.
     */
    suspend fun proteinIdeas(limit: Int = 4): List<ProteinIdea> {
        val own = (foods.observeFavorites().first() + foods.observeRecent(60).first()).distinctBy { it.id }
            .filter { isProteinRich(it.per100.protein, it.per100.kcal) }
            .sortedByDescending { it.useCount + if (it.favorite) 5 else 0 }
            .mapNotNull { f ->
                val (g, amount, unit) = defaultPortion(f)
                val n = f.per100.forGrams(g)
                val protein = n.protein ?: return@mapNotNull null
                if (protein < 8) return@mapNotNull null
                ProteinIdea(f.name, portionLabel(amount, unit), g, amount, unit, protein, n.kcal ?: 0.0, foodId = f.id)
            }
        if (own.size >= limit) return own.take(limit)
        val basics = BASICS.mapNotNull { (query, grams) ->
            runCatching { generic.search(query, 5) }.getOrDefault(emptyList())
                .firstOrNull { isProteinRich(it.per100.protein, it.per100.kcal) }
                ?.let { c ->
                    val n = c.per100.forGrams(grams)
                    ProteinIdea(c.name, "${grams.toInt()} g", grams, grams, "g", n.protein ?: 0.0, n.kcal ?: 0.0, candidate = c)
                }
        }.filter { b -> own.none { it.name.equals(b.name, true) } }
        return (own + basics).take(limit)
    }

    private fun portionLabel(amount: Double, unit: String) = when (unit) {
        "g", "ml" -> "${amount.toInt()} $unit"
        else -> if (amount == 1.0) unit else "${"%.1f".format(java.util.Locale.UK, amount).removeSuffix(".0")} × $unit"
    }

    suspend fun logIdea(idea: ProteinIdea, meal: Meal, date: LocalDate): Long {
        val f = idea.foodId?.let { foods.get(it) } ?: idea.candidate?.let { materialize(it) } ?: error("No food")
        return log(f, idea.grams, idea.amount, idea.unit, meal, date)
    }

    suspend fun defaultPortion(food: FoodEntity): Triple<Double, Double, String> {
        diary.lastFor(food.id)?.takeIf { it.grams > 0 }?.let { return Triple(it.grams, it.amount, it.unit) }
        preferredServing(food.servings)?.let { return Triple(it.grams, 1.0, it.label) }
        return Triple(100.0, 100.0, if (food.liquid) "ml" else "g")
    }
    suspend fun entries(ids: List<Long>) = diary.getAll(ids)

    suspend fun log(
        food: FoodEntity,
        grams: Double,
        amount: Double,
        unit: String,
        meal: Meal,
        date: LocalDate,
        source: EntrySource = EntrySource.MANUAL,
        aiActionId: Long? = null,
    ): Long = db.withTransaction {
        val now = clock.instant()
        foods.markUsed(food.id, now)
        diary.insert(
            DiaryEntryEntity(
                date = date, meal = meal, at = now, foodId = food.id, name = food.name, brand = food.brand,
                grams = grams, amount = amount, unit = unit, nutrients = food.per100.forGrams(grams),
                source = source, aiActionId = aiActionId,
            ),
        )
    }

    suspend fun quickAdd(
        name: String,
        nutrients: Nutrients,
        meal: Meal,
        date: LocalDate,
        source: EntrySource = EntrySource.QUICK,
        aiActionId: Long? = null,
    ): Long = diary.insert(
        DiaryEntryEntity(
            date = date, meal = meal, at = clock.instant(), name = name.ifBlank { "Quick add" }, grams = 0.0,
            amount = nutrients.kcal ?: 0.0, unit = "kcal", nutrients = nutrients, source = source, aiActionId = aiActionId,
        ),
    )

    /** An AI estimate without a database food (e.g. a home-cooked plate from a photo). */
    suspend fun logEstimate(name: String, grams: Double?, n: Nutrients, meal: Meal, date: LocalDate, aiActionId: Long?): Long =
        diary.insert(
            DiaryEntryEntity(
                date = date, meal = meal, at = clock.instant(), name = name, grams = grams ?: 0.0,
                amount = grams ?: (n.kcal ?: 0.0), unit = if (grams != null && grams > 0) "g" else "kcal",
                nutrients = n, source = EntrySource.AI, aiActionId = aiActionId,
            ),
        )

    /** Changes amount/meal of an entry, recomputing its nutrients from the original ratio. */
    suspend fun updateEntry(e: DiaryEntryEntity, grams: Double, amount: Double, unit: String, meal: Meal) {
        val food = e.foodId?.let { foods.get(it) }
        val nutrients = when {
            food != null -> food.per100.forGrams(grams)
            e.grams > 0 -> e.nutrients.scaled(grams / e.grams)
            else -> e.nutrients
        }
        diary.update(e.copy(grams = grams, amount = amount, unit = unit, meal = meal, nutrients = nutrients))
    }

    suspend fun updateEntryRaw(e: DiaryEntryEntity) = diary.update(e)
    suspend fun deleteEntry(id: Long) = diary.delete(id)
    suspend fun deleteEntries(ids: List<Long>) = diary.deleteAll(ids)
    suspend fun restoreEntry(e: DiaryEntryEntity) = diary.insert(e)
    suspend fun restoreEntries(e: List<DiaryEntryEntity>) = diary.insertAll(e)

    suspend fun copyMeal(fromDate: LocalDate, fromMeal: Meal, toDate: LocalDate, toMeal: Meal): Int {
        val items = diary.meal(fromDate, fromMeal)
        val now = clock.instant()
        diary.insertAll(items.map { it.copy(id = 0, date = toDate, meal = toMeal, at = now, source = EntrySource.COPY, aiActionId = null) })
        return items.size
    }

    // ---- Day status ----
    fun observeDayStatus(date: LocalDate) = db.dayLogDao().observe(date)
    suspend fun setDayStatus(date: LocalDate, status: DayStatus?) {
        if (status == null) db.dayLogDao().clear(date)
        else db.dayLogDao().upsert(DayLogEntity(date, status, clock.instant()))
    }
    suspend fun dayStatuses(from: LocalDate, to: LocalDate) = db.dayLogDao().range(from, to)
    fun observeDayStatuses(from: LocalDate, to: LocalDate) = db.dayLogDao().observeRange(from, to)

    // ---- Meal templates ----
    val templates = db.templateDao().observeAll()
    val templateItems = db.templateDao().observeItems()

    suspend fun saveTemplate(name: String, entries: List<DiaryEntryEntity>): Long = db.withTransaction {
        val id = db.templateDao().insert(MealTemplateEntity(name = name, createdAt = clock.instant()))
        db.templateDao().insertItems(entries.map {
            MealTemplateItemEntity(
                templateId = id, foodId = it.foodId, name = it.name, brand = it.brand, grams = it.grams,
                amount = it.amount, unit = it.unit, nutrients = it.nutrients,
            )
        })
        id
    }

    suspend fun logTemplate(templateId: Long, meal: Meal, date: LocalDate): List<Long> = db.withTransaction {
        val now = clock.instant()
        val items = db.templateDao().items(templateId)
        diary.insertAll(items.map {
            DiaryEntryEntity(
                date = date, meal = meal, at = now, foodId = it.foodId, name = it.name, brand = it.brand,
                grams = it.grams, amount = it.amount, unit = it.unit, nutrients = it.nutrients, source = EntrySource.TEMPLATE,
            )
        })
    }

    suspend fun deleteTemplate(id: Long) = db.withTransaction {
        db.templateDao().deleteItems(id)
        db.templateDao().delete(id)
    }

    // ---- Recipes ----
    val recipes = db.recipeDao().observeAll()
    suspend fun recipe(id: Long) = db.recipeDao().get(id)
    suspend fun recipeIngredients(id: Long) = db.recipeDao().ingredients(id)

    /** Saves the recipe and its linked food (per-100 g of the finished dish + a "portion" serving). */
    suspend fun saveRecipe(d: RecipeDraft): Long = db.withTransaction {
        val now = clock.instant()
        val dao = db.recipeDao()
        val ingredients = d.ingredients.map { Recipes.Ingredient(it.grams, it.per100) }
        val per100 = Recipes.per100(ingredients, d.cookedWeightG)
        val weight = Recipes.totalWeight(ingredients, d.cookedWeightG)
        val servings = buildList {
            if (d.servings > 0 && weight > 0) add(Serving("1 portion (1/${d.servings})", weight / d.servings))
            if (weight > 0) add(Serving("Whole dish", weight))
        }
        val recipeId = d.id ?: dao.insert(RecipeEntity(name = d.name, servings = d.servings, cookedWeightG = d.cookedWeightG, createdAt = now, updatedAt = now))
        val existingFood = foods.byRecipe(recipeId)
        val food = (existingFood ?: FoodEntity(name = d.name, source = FoodSource.RECIPE, per100 = per100, createdAt = now, updatedAt = now))
            .copy(name = d.name, per100 = per100, servings = servings, recipeId = recipeId, updatedAt = now, hidden = false)
        val foodId = if (existingFood == null) foods.insert(food) else food.id.also { foods.update(food) }
        dao.update(RecipeEntity(recipeId, d.name, d.servings, d.cookedWeightG, foodId, existingFood?.createdAt ?: now, now))
        dao.clearIngredients(recipeId)
        dao.insertIngredients(d.ingredients.map { it.copy(id = 0, recipeId = recipeId) })
        recipeId
    }

    suspend fun deleteRecipe(id: Long) = db.withTransaction {
        foods.byRecipe(id)?.let { foods.update(it.copy(hidden = true)) }
        db.recipeDao().clearIngredients(id)
        db.recipeDao().delete(id)
    }
}
