package be.lain.bloom.data.ai

import be.lain.bloom.data.BodyRepository
import be.lain.bloom.data.ProfileRepository
import be.lain.bloom.data.activeOn
import be.lain.bloom.data.db.AiActionKind
import be.lain.bloom.data.db.AiActionStatus
import be.lain.bloom.data.db.BloomDatabase
import be.lain.bloom.data.db.FoodEntity
import be.lain.bloom.data.db.MeasurementEntity
import be.lain.bloom.data.db.RecipeIngredientEntity
import be.lain.bloom.data.db.RunSessionEntity
import be.lain.bloom.data.db.SetLogEntity
import be.lain.bloom.data.db.WorkoutSessionEntity
import be.lain.bloom.data.food.FoodRepository
import be.lain.bloom.data.food.RecipeDraft
import be.lain.bloom.data.training.TrainingRepository
import be.lain.bloom.domain.DayStatus
import be.lain.bloom.domain.Energy
import be.lain.bloom.domain.EntrySource
import be.lain.bloom.domain.Goal
import be.lain.bloom.domain.Meal
import be.lain.bloom.domain.Nutrients
import be.lain.bloom.domain.Trend
import be.lain.bloom.domain.WeightPoint
import be.lain.bloom.domain.running.Geo
import be.lain.bloom.domain.training.Effort
import be.lain.bloom.domain.training.Exercises
import be.lain.bloom.domain.training.Program
import be.lain.bloom.domain.training.Progression
import be.lain.bloom.domain.training.SetResult
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Coach tools added after real use: recipes, workout detail and logging, runs, measurements,
 * progress photos, long nutrition history, goal proposals, copying meals and reminders.
 * Every write is journaled so it can be undone; goal changes are proposals the user accepts.
 */
@Singleton
class MoreTools @Inject constructor(
    private val db: BloomDatabase,
    private val food: FoodRepository,
    private val body: BodyRepository,
    private val profiles: ProfileRepository,
    private val training: TrainingRepository,
    private val runs: be.lain.bloom.data.training.RunRepository,
    private val reviews: be.lain.bloom.data.review.ReviewRepository,
    private val reminders: be.lain.bloom.service.Reminders,
    private val barcodes: PhotoBarcodes,
    private val cycle: be.lain.bloom.data.cycle.CycleRepository,
    private val clock: Clock,
) {
    /** Photos the coach asked to see, handed to the engine to attach to the next request. */
    private val pendingImages = HashMap<Long, Pair<String, List<String>>>()

    fun takePendingImages(chatId: Long): Pair<String, List<String>>? = pendingImages.remove(chatId)

    val names = setOf(
        "create_recipe", "get_workout_details", "log_workout", "log_run", "log_measurements", "get_progress_photos",
        "view_progress_photos", "get_nutrition_averages", "propose_goal_change", "copy_meal", "schedule_reminder",
        "read_barcode_from_photo", "get_cycle", "log_period", "log_cycle_symptoms",
    )

    fun definitions(): List<JsonObject> = listOf(
        DeepSeek.tool(
            "get_cycle",
            "Menstrual cycle tracking: cycle day, phase, expected next period, whether water retention is likely now, " +
                "average lengths and recent symptoms. Use it before reading too much into a weight jump or plateau.",
            buildJsonObject { }, emptyList(),
        ),
        DeepSeek.tool(
            "log_period",
            "Mark days as period days (or remove them) when the user tells you, e.g. 'my period started yesterday'. Undoable.",
            buildJsonObject {
                put("from", DeepSeek.prop("string", "First day, YYYY-MM-DD"))
                put("to", DeepSeek.prop("string", "Last day, default same as from"))
                put("period", DeepSeek.prop("boolean", "true to mark as period days, false to remove"))
                put("flow", DeepSeek.prop("string", "light, medium or heavy (optional)"))
            }, listOf("from", "period"),
        ),
        DeepSeek.tool(
            "log_cycle_symptoms",
            "Record symptoms for a day: cramps, bloating, cravings, tired, headache, tender_breasts, low_mood, spotting. Replaces that day's symptoms. Undoable.",
            buildJsonObject {
                put("date", DeepSeek.prop("string", "YYYY-MM-DD, default today"))
                put("symptoms", DeepSeek.prop("string", "Comma-separated keys from the list"))
            }, listOf("symptoms"),
        ),
        DeepSeek.tool(
            "read_barcode_from_photo",
            "Scan the user's most recent photo(s) in this chat for a product barcode (EAN/UPC), on the phone. " +
                "Returns the digits; then use lookup_barcode.",
            buildJsonObject { }, emptyList(),
        ),
        DeepSeek.tool(
            "create_recipe",
            "Save a home recipe or regular combo (e.g. a skyr bowl) as one food the user can log with one tap. " +
                "Each ingredient needs food_ref + grams (from search_foods) or name + grams + estimated kcal/macros for that amount. " +
                "Returns a food_ref; log it with log_food using servings or grams.",
            buildJsonObject {
                put("name", DeepSeek.prop("string", "Recipe name"))
                put("servings", DeepSeek.prop("integer", "How many portions the whole recipe makes (1 for a single bowl)"))
                put("cooked_weight_g", DeepSeek.prop("number", "Weight of the finished dish if known (cooking loses water)"))
                put("ingredients", arrayOf(
                    "food_ref" to DeepSeek.prop("string", "Ref from search_foods/lookup_barcode/create_food"),
                    "name" to DeepSeek.prop("string", "Ingredient name"),
                    "grams" to DeepSeek.prop("number", "Amount in the whole recipe, g or ml"),
                    "kcal" to DeepSeek.prop("number", "Only without food_ref: kcal for this amount"),
                    "protein" to DeepSeek.prop("number", "g for this amount"),
                    "carbs" to DeepSeek.prop("number", "g for this amount"),
                    "fat" to DeepSeek.prop("number", "g for this amount"),
                    "fiber" to DeepSeek.prop("number", "g for this amount"),
                    required = listOf("name", "grams"),
                ))
            }, listOf("name", "servings", "ingredients"),
        ),
        DeepSeek.tool(
            "get_workout_details",
            "Strength workouts in a date range with every exercise, set, reps/seconds and effort, plus the next program session " +
                "(exercises, sets × reps, rest, last performance and the app's progression suggestion).",
            dateRange(), listOf("from", "to"),
        ),
        DeepSeek.tool(
            "log_workout",
            "Record a strength workout the user did without the in-app player (undoable). Use exercise ids or names from get_workout_details. " +
                "If it was a program session, pass session (e.g. \"A\") so progression keeps working.",
            buildJsonObject {
                put("date", DeepSeek.prop("string", "YYYY-MM-DD, default today"))
                put("name", DeepSeek.prop("string", "e.g. 'Foundation A' or 'Glute circuit'"))
                put("session", DeepSeek.prop("string", "Program session id (A/B) if it was the program session"))
                put("minutes", DeepSeek.prop("integer", "Active minutes"))
                put("rpe", DeepSeek.prop("integer", "How hard overall, 1–10"))
                put("notes", DeepSeek.prop("string", "Anything worth remembering"))
                put("exercises", arrayOf(
                    "exercise" to DeepSeek.prop("string", "Exercise id or name"),
                    "sets" to DeepSeek.prop("integer", "Number of sets"),
                    "reps" to DeepSeek.prop("integer", "Reps per set (rep exercises)"),
                    "seconds" to DeepSeek.prop("integer", "Seconds per set (timed exercises)"),
                    "effort" to DeepSeek.prop("string", "easy, good or hard"),
                    required = listOf("exercise", "sets"),
                ))
            }, listOf("name", "minutes", "exercises"),
        ),
        DeepSeek.tool(
            "log_run",
            "Record a run or walk the user did without GPS tracking in the app (undoable).",
            buildJsonObject {
                put("date", DeepSeek.prop("string", "YYYY-MM-DD, default today"))
                put("title", DeepSeek.prop("string", "e.g. 'Run-walk intervals'"))
                put("minutes", DeepSeek.prop("integer", "Moving time in minutes"))
                put("distance_km", DeepSeek.prop("number", "Distance if known"))
                put("rpe", DeepSeek.prop("integer", "1–10"))
                put("notes", DeepSeek.prop("string", "Optional"))
            }, listOf("minutes"),
        ),
        DeepSeek.tool(
            "log_measurements",
            "Save body measurements in cm (undoable). Give only the ones the user measured.",
            buildJsonObject {
                put("date", DeepSeek.prop("string", "YYYY-MM-DD, default today"))
                listOf("waist", "hips", "chest", "thigh", "arm").forEach { put(it, DeepSeek.prop("number", "cm")) }
            }, emptyList(),
        ),
        DeepSeek.tool("get_progress_photos", "List the user's progress photos (ids and dates). Doesn't send any image.", buildJsonObject { }, emptyList()),
        DeepSeek.tool(
            "view_progress_photos",
            "Look at up to 4 progress photos, e.g. the first and the latest, to compare shape changes. " +
                "ONLY call this when the user has asked you to look at their photos in this conversation. Be kind and factual about bodies.",
            buildJsonObject { put("photo_ids", DeepSeek.prop("string", "Comma-separated ids from get_progress_photos")) }, listOf("photo_ids"),
        ),
        DeepSeek.tool(
            "get_nutrition_averages",
            "Long-range nutrition summary (up to 120 days): per-day kcal/protein/carbs/fat/fiber with logging status, averages on " +
                "fully logged days, weekday patterns and adherence to targets.",
            dateRange(), listOf("from", "to"),
        ),
        DeepSeek.tool(
            "propose_goal_change",
            "Suggest a new goal weight, pace or goal type. Shown as a card; nothing changes until the user taps Accept. " +
                "The calorie target is then recomputed from the measured burn.",
            buildJsonObject {
                put("goal_kg", DeepSeek.prop("number", "New goal weight"))
                put("pace_kg_per_week", DeepSeek.prop("number", "0.1–1.0"))
                put("goal", DeepSeek.prop("string", "lose, maintain, gain or recomp"))
                put("reason", DeepSeek.prop("string", "One-sentence reason shown to the user"))
            }, listOf("reason"),
        ),
        DeepSeek.tool(
            "copy_meal",
            "Copy all entries of one meal to another day/meal, e.g. yesterday's breakfast to today (undoable).",
            buildJsonObject {
                put("from_date", DeepSeek.prop("string", "YYYY-MM-DD"))
                put("from_meal", DeepSeek.prop("string", "breakfast, lunch, dinner or snacks"))
                put("to_date", DeepSeek.prop("string", "YYYY-MM-DD, default today"))
                put("to_meal", DeepSeek.prop("string", "Default: same meal"))
            }, listOf("from_date", "from_meal"),
        ),
        DeepSeek.tool(
            "schedule_reminder",
            "Send the user a notification later (e.g. a nudge to prep lunch, or a check-in). Only when the user wants it. Undoable.",
            buildJsonObject {
                put("at", DeepSeek.prop("string", "Local time YYYY-MM-DDTHH:MM, within 30 days"))
                put("message", DeepSeek.prop("string", "Short, friendly text, max 140 characters"))
            }, listOf("at", "message"),
        ),
    )

    private fun dateRange() = buildJsonObject {
        put("from", DeepSeek.prop("string", "YYYY-MM-DD"))
        put("to", DeepSeek.prop("string", "YYYY-MM-DD"))
    }

    private fun arrayOf(vararg props: Pair<String, JsonObject>, required: List<String>) = buildJsonObject {
        put("type", "array")
        put("items", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { props.forEach { (k, v) -> put(k, v) } })
            put("required", JsonArray(required.map(::JsonPrimitive)))
        })
    }

    /** Journal helper supplied by CoachTools. */
    fun interface Journal {
        suspend fun add(chatId: Long, kind: AiActionKind, status: AiActionStatus, summary: String, payload: JsonObject): Long
    }

    suspend fun execute(
        chatId: Long,
        name: String,
        a: JsonObject,
        resolve: suspend (String) -> FoodEntity?,
        journal: Journal,
    ): String = when (name) {
        "create_recipe" -> createRecipe(chatId, a, resolve, journal)
        "get_workout_details" -> workoutDetails(a.date("from") ?: today().minusDays(13), a.date("to") ?: today())
        "log_workout" -> logWorkout(chatId, a, journal)
        "log_run" -> logRun(chatId, a, journal)
        "log_measurements" -> logMeasurements(chatId, a, journal)
        "get_progress_photos" -> progressPhotos()
        "view_progress_photos" -> viewPhotos(chatId, a)
        "get_nutrition_averages" -> nutrition(a.date("from") ?: today().minusDays(29), a.date("to") ?: today())
        "propose_goal_change" -> proposeGoal(chatId, a, journal)
        "copy_meal" -> copyMeal(chatId, a, journal)
        "schedule_reminder" -> scheduleReminder(chatId, a, journal)
        "read_barcode_from_photo" -> readBarcode(chatId)
        "get_cycle" -> cycle.summary(today()).toString()
        "log_period" -> logPeriod(chatId, a, journal)
        "log_cycle_symptoms" -> logSymptoms(chatId, a, journal)
        else -> err("Unknown tool $name")
    }

    // ---------- cycle ----------

    private suspend fun cycleOff(): String? =
        if (cycle.enabled.first()) null else err("Cycle tracking is off. Ask the user to turn it on in Settings → Cycle first.")

    private suspend fun snapshot(dates: List<LocalDate>) = buildJsonArray {
        dates.forEach { d ->
            add(buildJsonObject {
                put("date", d.toString())
                cycle.day(d)?.let { e -> put("period", e.period); e.flow?.let { put("flow", it) }; put("symptoms", e.symptoms) }
            })
        }
    }

    private suspend fun logPeriod(chatId: Long, a: JsonObject, journal: Journal): String {
        cycleOff()?.let { return it }
        val from = a.date("from") ?: return err("from required")
        val to = a.date("to") ?: from
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 14) return err("Give a range of at most 15 days")
        if (from.isAfter(today())) return err("Can't log future days")
        val on = a["period"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val flow = when (a.s("flow").lowercase()) { "light" -> 1; "medium" -> 2; "heavy" -> 3; else -> null }
        val dates = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
        val before = snapshot(dates)
        dates.forEach { cycle.setPeriod(it, on, flow) }
        journal.add(chatId, AiActionKind.LOG_CYCLE, AiActionStatus.APPLIED,
            (if (on) "Marked period " else "Removed period ") + if (from == to) "$from" else "$from – $to", buildJsonObject { put("before", before) })
        return cycle.summary(today()).toString()
    }

    private suspend fun logSymptoms(chatId: Long, a: JsonObject, journal: Journal): String {
        cycleOff()?.let { return it }
        val date = a.date("date") ?: today()
        val set = be.lain.bloom.data.cycle.Symptom.parse(a.s("symptoms").lowercase().replace(' ', '_').replace(",_", ","))
        val before = snapshot(listOf(date))
        cycle.setSymptoms(date, set)
        journal.add(chatId, AiActionKind.LOG_CYCLE, AiActionStatus.APPLIED,
            "Symptoms $date: " + set.joinToString { it.label.lowercase() }.ifEmpty { "none" }, buildJsonObject { put("before", before) })
        return buildJsonObject { put("saved", set.joinToString(",") { it.key }) }.toString()
    }

    // ---------- barcodes ----------

    private suspend fun readBarcode(chatId: Long): String {
        val photos = db.chatDao().messages(chatId).lastOrNull { it.role == "user" && !it.images.isNullOrBlank() }
            ?.images?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
        if (photos.isEmpty()) return err("There's no photo in this chat yet. Ask the user to send one of the barcode.")
        val codes = barcodes.codesFor(photos)
        return buildJsonObject {
            put("barcodes", buildJsonArray { codes.forEach { add(JsonPrimitive(it)) } })
            if (codes.isEmpty()) put("tip", "No barcode could be decoded. Read the digits printed under the bars if they're visible, or ask for a sharp, close, well-lit photo of just the barcode.")
            else put("next", "Call lookup_barcode with these digits.")
        }.toString()
    }

    // ---------- recipes ----------

    private suspend fun createRecipe(chatId: Long, a: JsonObject, resolve: suspend (String) -> FoodEntity?, journal: Journal): String {
        val name = a.s("name").ifBlank { return err("name required") }
        val items = a["ingredients"] as? JsonArray ?: return err("ingredients required")
        val ingredients = mutableListOf<RecipeIngredientEntity>()
        val problems = mutableListOf<String>()
        for (el in items) {
            val i = el.jsonObject
            val grams = i.d("grams")?.takeIf { it > 0 } ?: run { problems += "${i.s("name")}: grams missing"; null } ?: continue
            val ref = i.s("food_ref")
            val f = if (ref.isNotBlank()) resolve(ref) else null
            if (f != null) {
                ingredients += RecipeIngredientEntity(recipeId = 0, foodId = f.id, name = f.name, grams = grams, per100 = f.per100)
            } else {
                val kcal = i.d("kcal") ?: run { problems += "${i.s("name")}: need food_ref or a kcal estimate"; null } ?: continue
                val portion = Nutrients(kcal = kcal, protein = i.d("protein"), carbs = i.d("carbs"), fat = i.d("fat"), fiber = i.d("fiber"))
                ingredients += RecipeIngredientEntity(recipeId = 0, name = i.s("name").ifBlank { "Ingredient" }, grams = grams, per100 = portion.scaled(100.0 / grams))
            }
        }
        if (ingredients.isEmpty()) return err("No usable ingredients: ${problems.joinToString("; ")}")
        val servings = (a.d("servings")?.roundToInt() ?: 1).coerceIn(1, 50)
        val recipeId = food.saveRecipe(RecipeDraft(name = name, servings = servings, cookedWeightG = a.d("cooked_weight_g")?.takeIf { it > 0 }, ingredients = ingredients))
        val f = db.foodDao().byRecipe(recipeId) ?: return err("Couldn't save the recipe")
        journal.add(chatId, AiActionKind.CREATE_RECIPE, AiActionStatus.APPLIED, "Saved recipe “$name” · ${ingredients.size} ingredients",
            buildJsonObject { put("recipeId", recipeId) })
        val portion = f.servings.firstOrNull()
        return buildJsonObject {
            put("food_ref", "food:${f.id}")
            put("name", name)
            put("servings", servings)
            portion?.let {
                put("portion", it.label); put("portion_grams", (it.grams * 10).roundToInt() / 10.0)
                put("per_portion", CoachTools.nutrientsJson(f.per100.forGrams(it.grams)))
            }
            if (problems.isNotEmpty()) put("skipped", buildJsonArray { problems.forEach { add(JsonPrimitive(it)) } })
            put("note", "Saved under Food → Recipes. To log a portion: log_food with this food_ref and servings: 1.")
        }.toString()
    }

    // ---------- strength ----------

    private suspend fun workoutDetails(from: LocalDate, to: LocalDate): String {
        val end = if (ChronoUnit.DAYS.between(from, to) > 60) from.plusDays(60) else to
        val list = training.workoutsBetween(from, end)
        val st = training.status.first()
        val choices = training.choices.first()
        return buildJsonObject {
            put("workouts", buildJsonArray {
                list.forEach { w ->
                    val sets = training.sets(w.id)
                    add(buildJsonObject {
                        put("id", w.id); put("date", w.date.toString()); put("name", w.name)
                        put("phase", w.phase + 1); put("minutes", w.activeSeconds / 60)
                        put("sets_done", "${w.setsDone}/${w.setsPlanned}")
                        w.rpe?.let { put("rpe", it) }
                        if (w.notes.isNotBlank()) put("notes", w.notes)
                        put("exercises", buildJsonArray {
                            sets.groupBy { it.exerciseId }.forEach { (exId, s) ->
                                add(buildJsonObject {
                                    put("exercise", Exercises.find(exId)?.name ?: exId)
                                    put("id", exId)
                                    put("sets", buildJsonArray {
                                        s.forEach { set ->
                                            add(JsonPrimitive(
                                                if (set.skipped) "skipped"
                                                else listOfNotNull(
                                                    set.reps?.let { "$it reps" }, set.seconds?.let { "${it}s" }, set.side, set.effort?.label?.lowercase(),
                                                ).joinToString(" "),
                                            ))
                                        }
                                    })
                                })
                            }
                        })
                    })
                }
            })
            put("next_session", buildJsonObject {
                val s = st.next
                put("program", "${Program.NAME}, phase ${st.phase.index + 1}/${Program.phases.size} ${st.phase.name}, week ${st.week} of ${st.phase.weeks}")
                put("ready_to_advance", st.readyToAdvance)
                put("session", "${s.id}: ${s.name} (${s.focus}), about ${s.estimateMinutes()} min")
                put("blocks", buildJsonArray {
                    s.blocks.forEachIndexed { b, block ->
                        add(buildJsonObject {
                            put("block", block.kind.label)
                            put("exercises", buildJsonArray {
                                block.items.forEachIndexed { i, p ->
                                    val slot = Program.slotKey(st.phase.index, s.id, b, i)
                                    val exId = choices[slot] ?: p.exerciseId
                                    val last = training.lastForSlot(slot)
                                    add(buildJsonObject {
                                        put("exercise", Exercises.find(exId)?.name ?: exId); put("id", exId)
                                        put("prescription", "${p.sets} × ${p.target()}, rest ${p.restSec}s" + if (p.note.isNotBlank()) " · ${p.note}" else "")
                                        Exercises.find(exId)?.let { ex -> put("cues", ex.cues.take(3).joinToString(" / ")) }
                                        if (last.isNotEmpty()) {
                                            put("last_time", last.filter { !it.skipped }.joinToString(", ") { set ->
                                                (set.reps?.let { "$it" } ?: set.seconds?.let { "${it}s" } ?: "?") + (set.effort?.let { " ${it.label.lowercase()}" } ?: "")
                                            })
                                            Progression.suggest(p, exId, last.map { it.toResult() })?.let { put("suggestion", it.text) }
                                        }
                                        Exercises.easier(exId)?.let { put("easier", Exercises[it].name) }
                                        Exercises.harder(exId)?.let { put("harder", Exercises[it].name) }
                                    })
                                }
                            })
                        })
                    }
                })
            })
        }.toString()
    }

    private fun SetLogEntity.toResult() = SetResult(0, exerciseId, slotKey, setIndex, side, reps, seconds, effort, skipped)

    private fun findExercise(text: String): String? {
        val t = text.trim().lowercase()
        Exercises.find(t)?.let { return it.id }
        val all = Exercises.all
        return all.firstOrNull { it.name.equals(t, true) }?.id
            ?: all.firstOrNull { it.id.replace('-', ' ') == t.replace('-', ' ') }?.id
            ?: all.firstOrNull { t.contains(it.name.lowercase()) || it.name.lowercase().contains(t) }?.id
    }

    private suspend fun logWorkout(chatId: Long, a: JsonObject, journal: Journal): String {
        val minutes = a.d("minutes")?.roundToInt()?.coerceIn(1, 300) ?: return err("minutes required")
        val items = a["exercises"] as? JsonArray ?: return err("exercises required")
        val date = a.date("date") ?: today()
        val st = training.status.first()
        val sessionId = a.s("session").uppercase().takeIf { id -> st.phase.sessions.any { it.id == id } }
        val session = sessionId?.let { id -> st.phase.sessions.first { it.id == id } }
        val unknown = mutableListOf<String>()
        val sets = mutableListOf<SetResult>()
        items.forEach { el ->
            val e = el.jsonObject
            val exId = findExercise(e.s("exercise")) ?: run { unknown += e.s("exercise"); return@forEach }
            // Link to the program slot so the app's progression sees it.
            val slot = session?.let { s ->
                s.blocks.withIndex().firstNotNullOfOrNull { (b, block) ->
                    block.items.withIndex().firstOrNull { (_, p) -> p.exerciseId == exId || Exercises.find(p.exerciseId)?.ladder?.let { it == Exercises.find(exId)?.ladder } == true }
                        ?.let { (i, _) -> Program.slotKey(st.phase.index, s.id, b, i) }
                }
            } ?: "manual:$exId"
            val effort = when (e.s("effort").lowercase()) { "easy" -> Effort.EASY; "hard" -> Effort.HARD; "good" -> Effort.GOOD; else -> null }
            val n = (e.d("sets")?.roundToInt() ?: 1).coerceIn(1, 20)
            repeat(n) { k ->
                sets += SetResult(0, exId, slot, k + 1, null, e.d("reps")?.roundToInt(), e.d("seconds")?.roundToInt(), effort, false)
            }
        }
        if (sets.isEmpty()) return err("No known exercises. Use ids or names from get_workout_details. Unknown: ${unknown.joinToString()}")
        val weight = Trend.at(body.weights.first().map { WeightPoint(it.date, it.kg) }, date) ?: profiles.profile.first()?.startWeightKg ?: 70.0
        val zone = ZoneId.systemDefault()
        val start = if (date == today()) clock.instant().minusSeconds(minutes * 60L) else date.atTime(LocalTime.of(18, 0)).atZone(zone).toInstant()
        val notes = listOfNotNull(a.s("notes").ifBlank { null }, unknown.takeIf { it.isNotEmpty() }?.let { "Also: ${it.joinToString()}" }).joinToString(" · ")
        val id = training.saveWorkout(
            WorkoutSessionEntity(
                date = date, startedAt = start, endedAt = start.plusSeconds(minutes * 60L), phase = st.phase.index,
                sessionId = sessionId ?: "manual", name = a.s("name").ifBlank { session?.name ?: "Workout" }, activeSeconds = minutes * 60,
                kcal = Progression.kcal(weight, minutes.toDouble()), setsDone = sets.size, setsPlanned = sets.size,
                rpe = a.d("rpe")?.roundToInt()?.coerceIn(1, 10), notes = notes,
            ),
            sets,
        )
        journal.add(chatId, AiActionKind.LOG_WORKOUT, AiActionStatus.APPLIED,
            "Logged workout “${a.s("name").ifBlank { session?.name ?: "Workout" }}” · ${sets.size} sets · $minutes min", buildJsonObject { put("workoutId", id) })
        return buildJsonObject {
            put("saved", true); put("sets", sets.size)
            if (unknown.isNotEmpty()) put("not_matched", buildJsonArray { unknown.forEach { add(JsonPrimitive(it)) } })
            put("note", "Saved. Exercise never adds calories to the food budget.")
        }.toString()
    }

    // ---------- runs ----------

    private suspend fun logRun(chatId: Long, a: JsonObject, journal: Journal): String {
        val minutes = a.d("minutes")?.takeIf { it > 0 } ?: return err("minutes required")
        val date = a.date("date") ?: today()
        val km = a.d("distance_km")?.takeIf { it in 0.0..100.0 } ?: 0.0
        val sec = (minutes * 60).roundToInt()
        val weight = Trend.at(body.weights.first().map { WeightPoint(it.date, it.kg) }, date) ?: profiles.profile.first()?.startWeightKg ?: 70.0
        val start = if (date == today()) clock.instant().minusSeconds(sec.toLong()) else date.atTime(LocalTime.of(18, 0)).atZone(ZoneId.systemDefault()).toInstant()
        val pace = if (km > 0) sec / km else null
        val run = RunSessionEntity(
            date = date, startedAt = start, endedAt = start.plusSeconds(sec.toLong()), title = a.s("title").ifBlank { "Run" }, planKey = "manual",
            distanceM = km * 1000, movingSec = sec, elapsedSec = sec, kcal = if (km > 0) Geo.kcal(weight, km, pace) else (minutes * weight * 0.1).roundToInt(),
            planCompleted = true, finished = true, rpe = a.d("rpe")?.roundToInt()?.coerceIn(1, 10), notes = a.s("notes"),
        )
        val id = db.runDao().insertRun(run)
        journal.add(chatId, AiActionKind.LOG_RUN, AiActionStatus.APPLIED,
            "Logged ${run.title.lowercase()} · ${minutes.roundToInt()} min" + if (km > 0) " · ${"%.1f".format(Locale.UK, km)} km" else "",
            buildJsonObject { put("runId", id) })
        return buildJsonObject { put("saved", true); put("run_id", id) }.toString()
    }

    // ---------- body ----------

    private suspend fun logMeasurements(chatId: Long, a: JsonObject, journal: Journal): String {
        fun v(k: String) = a.d(k)?.takeIf { it in 20.0..250.0 }
        val m = MeasurementEntity(date = a.date("date") ?: today(), waistCm = v("waist"), hipsCm = v("hips"), chestCm = v("chest"), thighCm = v("thigh"), armCm = v("arm"))
        if (listOf(m.waistCm, m.hipsCm, m.chestCm, m.thighCm, m.armCm).all { it == null }) return err("Give at least one measurement in cm")
        val id = body.logMeasurement(m)
        val text = listOfNotNull(m.waistCm?.let { "waist $it" }, m.hipsCm?.let { "hips $it" }, m.chestCm?.let { "chest $it" },
            m.thighCm?.let { "thigh $it" }, m.armCm?.let { "arm $it" }).joinToString(", ")
        journal.add(chatId, AiActionKind.LOG_MEASUREMENT, AiActionStatus.APPLIED, "Saved measurements · $text cm", buildJsonObject { put("measurementId", id) })
        return buildJsonObject { put("saved", true) }.toString()
    }

    private suspend fun progressPhotos(): String {
        val photos = reviews.photos.first()
        return buildJsonObject {
            put("photos", buildJsonArray { photos.sortedBy { it.date }.forEach { p -> add(buildJsonObject { put("id", p.id); put("date", p.date.toString()) }) } })
            if (photos.isEmpty()) put("note", "No photos yet. The user can add them in Progress → Progress photos.")
        }.toString()
    }

    private suspend fun viewPhotos(chatId: Long, a: JsonObject): String {
        val ids = a.s("photo_ids").split(',', ' ').mapNotNull { it.trim().toLongOrNull() }.distinct().take(4)
        val photos = ids.mapNotNull { db.reviewDao().photo(it) }.filter { java.io.File(it.path).isFile }
        if (photos.isEmpty()) return err("No photos with those ids")
        val dates = photos.joinToString(", ") { it.date.toString() }
        pendingImages[chatId] = "[Bloom attached ${photos.size} progress photo${if (photos.size == 1) "" else "s"} you asked for, taken on: $dates]" to photos.map { it.path }
        return buildJsonObject { put("status", "The photos follow in the next message, in this order: $dates") }.toString()
    }

    // ---------- nutrition history ----------

    private suspend fun nutrition(from: LocalDate, to0: LocalDate): String {
        val to = if (ChronoUnit.DAYS.between(from, to0) > 119) from.plusDays(119) else to0
        val entries = food.range(from, to)
        val statuses = food.dayStatuses(from, to).associate { it.date to it.status }
        val plans = profiles.plans.first()
        data class Day(val date: LocalDate, val n: Nutrients, val items: Int, val status: DayStatus?, val target: be.lain.bloom.domain.Targets?)
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { d ->
            val day = entries.filter { it.date == d }
            Day(d, Nutrients.sum(day.map { it.nutrients }), day.size, statuses[d], plans.activeOn(d)?.targets)
        }.toList()
        val logged = days.filter { it.items > 0 }
        val complete = logged.filter { it.status == DayStatus.COMPLETE }
        fun avg(list: List<Day>, f: (Nutrients) -> Double?) = list.mapNotNull { f(it.n) }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        return buildJsonObject {
            put("range", "$from to $to (${days.size} days)")
            put("days_logged", logged.size); put("days_complete", complete.size)
            val basis = complete.ifEmpty { logged }
            put("averages_basis", if (complete.isNotEmpty()) "fully logged days" else "all logged days (none marked complete)")
            put("avg", buildJsonObject {
                avg(basis) { it.kcal }?.let { put("kcal", it) }; avg(basis) { it.protein }?.let { put("protein_g", it) }
                avg(basis) { it.carbs }?.let { put("carbs_g", it) }; avg(basis) { it.fat }?.let { put("fat_g", it) }
                avg(basis) { it.fiber }?.let { put("fiber_g", it) }
            })
            val withTarget = basis.filter { it.target != null }
            if (withTarget.isNotEmpty()) {
                put("days_within_10pct_of_kcal_target", withTarget.count { abs((it.n.kcal ?: 0.0) - it.target!!.kcal) <= it.target.kcal * 0.1 })
                put("days_protein_target_hit", withTarget.count { (it.n.protein ?: 0.0) >= it.target!!.proteinG * 0.9 })
                put("days_over_kcal_target", withTarget.count { (it.n.kcal ?: 0.0) > it.target!!.kcal * 1.1 })
                put("compared_days", withTarget.size)
            }
            put("by_weekday_avg_kcal", buildJsonObject {
                DayOfWeek.entries.forEach { wd ->
                    val l = basis.filter { it.date.dayOfWeek == wd }
                    avg(l) { it.kcal }?.let { put(wd.getDisplayName(TextStyle.SHORT, Locale.ENGLISH), it) }
                }
            })
            put("days", buildJsonArray {
                days.forEach { d ->
                    add(JsonPrimitive(
                        "${d.date} " + if (d.items == 0) "nothing logged" else
                            "${(d.n.kcal ?: 0.0).roundToInt()} kcal P${(d.n.protein ?: 0.0).roundToInt()} C${(d.n.carbs ?: 0.0).roundToInt()} " +
                                "F${(d.n.fat ?: 0.0).roundToInt()} fib${(d.n.fiber ?: 0.0).roundToInt()} ${d.status?.label?.lowercase() ?: "unmarked"}",
                    ))
                }
            })
        }.toString()
    }

    // ---------- goal ----------

    private suspend fun proposeGoal(chatId: Long, a: JsonObject, journal: Journal): String {
        val p = profiles.profile.first() ?: return err("No profile")
        val goal = when (a.s("goal").lowercase()) {
            "lose" -> Goal.LOSE; "maintain" -> Goal.MAINTAIN; "gain" -> Goal.GAIN; "recomp", "tone" -> Goal.RECOMP; "" -> null
            else -> return err("goal must be lose, maintain, gain or recomp")
        }
        val goalKg = a.d("goal_kg")
        val pace = a.d("pace_kg_per_week")
        if (goalKg == null && pace == null && goal == null) return err("Change at least one of goal_kg, pace_kg_per_week, goal")
        if (goalKg != null) {
            val bmi = Energy.bmi(goalKg, p.heightCm)
            if (bmi < 18.5) return err("That goal is below a healthy BMI (18.5 = ${"%.1f".format(Locale.US, Energy.weightForBmi(18.5, p.heightCm))} kg at this height)")
            if (goalKg !in 35.0..250.0) return err("Implausible goal weight")
        }
        if (pace != null && pace !in 0.1..1.0) return err("pace_kg_per_week must be between 0.1 and 1.0")
        val parts = listOfNotNull(
            goal?.let { "goal: ${it.label.lowercase()}" },
            goalKg?.let { "goal weight ${p.goalWeightKg} → $it kg" },
            pace?.let { "pace ${p.paceKgPerWeek} → $it kg/week" },
        )
        journal.add(chatId, AiActionKind.GOAL_PROPOSAL, AiActionStatus.PENDING, parts.joinToString(", ").replaceFirstChar { it.uppercase() } + ". " + a.s("reason"),
            buildJsonObject {
                goalKg?.let { put("goal_kg", it) }; pace?.let { put("pace", it) }; goal?.let { put("goal", it.name) }
                put("reason", a.s("reason"))
            })
        return buildJsonObject { put("status", "Shown to the user as a card with Accept / Decline.") }.toString()
    }

    // ---------- meals ----------

    private suspend fun copyMeal(chatId: Long, a: JsonObject, journal: Journal): String {
        val fromDate = a.date("from_date") ?: return err("from_date required")
        val fromMeal = a.s("from_meal").toMeal() ?: return err("from_meal must be breakfast, lunch, dinner or snacks")
        val toDate = a.date("to_date") ?: today()
        val toMeal = a.s("to_meal").toMeal() ?: fromMeal
        val items = db.diaryDao().meal(fromDate, fromMeal)
        if (items.isEmpty()) return err("Nothing logged for ${fromMeal.label.lowercase()} on $fromDate")
        val actionId = journal.add(chatId, AiActionKind.LOG_FOOD, AiActionStatus.APPLIED, "Copying…", buildJsonObject { })
        val now = clock.instant()
        val ids = db.diaryDao().insertAll(items.map { it.copy(id = 0, date = toDate, meal = toMeal, at = now, source = EntrySource.COPY, aiActionId = actionId) })
        val kcal = items.sumOf { it.nutrients.kcal ?: 0.0 }.roundToInt()
        db.chatDao().action(actionId)?.let {
            db.chatDao().updateAction(it.copy(
                summary = "Copied ${items.size} item${if (items.size == 1) "" else "s"} to ${toMeal.label.lowercase()} · $kcal kcal",
                payload = buildJsonObject { put("entryIds", buildJsonArray { ids.forEach { id -> add(JsonPrimitive(id)) } }) }.toString(),
            ))
        }
        return buildJsonObject { put("copied", items.size); put("kcal", kcal); put("note", "Already saved; the user can undo.") }.toString()
    }

    // ---------- reminders ----------

    private suspend fun scheduleReminder(chatId: Long, a: JsonObject, journal: Journal): String {
        val at = runCatching { LocalDateTime.parse(a.s("at").take(16)) }.getOrNull() ?: return err("at must be YYYY-MM-DDTHH:MM")
        val now = LocalDateTime.now(clock)
        if (!at.isAfter(now)) return err("That time has already passed")
        if (at.isAfter(now.plusDays(30))) return err("Reminders can be at most 30 days ahead")
        val message = a.s("message").take(140).ifBlank { return err("message required") }
        if (!reminders.canNotify()) return err("Notifications are off for Bloom. Ask the user to allow them in Settings → Weekly review & reminders.")
        val actionId = journal.add(chatId, AiActionKind.REMINDER, AiActionStatus.APPLIED,
            "Reminder ${at.toLocalDate()} at ${"%02d:%02d".format(at.hour, at.minute)}: $message", buildJsonObject { })
        reminders.scheduleCustom(actionId, at, message)
        db.chatDao().action(actionId)?.let { db.chatDao().updateAction(it.copy(payload = buildJsonObject { put("work", "coach_reminder_$actionId") }.toString())) }
        return buildJsonObject { put("scheduled", at.toString()) }.toString()
    }

    // ---------- applying accepted goal proposals and undo ----------

    suspend fun acceptGoal(p: JsonObject) {
        val goal = p["goal"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Goal.valueOf(it) }.getOrNull() }
        profiles.updateGoal(goal, p["goal_kg"]?.jsonPrimitive?.doubleOrNull, p["pace"]?.jsonPrimitive?.doubleOrNull,
            "Coach suggestion: ${p["reason"]?.jsonPrimitive?.contentOrNull.orEmpty()}".take(200))
    }

    suspend fun undo(kind: AiActionKind, p: JsonObject) {
        when (kind) {
            AiActionKind.CREATE_RECIPE -> p["recipeId"]?.jsonPrimitive?.longOrNull?.let { food.deleteRecipe(it) }
            AiActionKind.LOG_WORKOUT -> p["workoutId"]?.jsonPrimitive?.longOrNull?.let { training.deleteWorkout(it) }
            AiActionKind.LOG_RUN -> p["runId"]?.jsonPrimitive?.longOrNull?.let { runs.deleteRun(it) }
            AiActionKind.LOG_MEASUREMENT -> p["measurementId"]?.jsonPrimitive?.longOrNull?.let { body.deleteMeasurement(it) }
            AiActionKind.REMINDER -> p["work"]?.jsonPrimitive?.contentOrNull?.let { reminders.cancelCustom(it) }
            AiActionKind.LOG_CYCLE -> (p["before"] as? JsonArray)?.forEach { el ->
                val o = el.jsonObject
                val date = LocalDate.parse(o["date"]!!.jsonPrimitive.content)
                val period = o["period"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
                cycle.restore(date, period?.let {
                    be.lain.bloom.data.db.CycleDayEntity(date, it, o["flow"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                        o["symptoms"]?.jsonPrimitive?.contentOrNull.orEmpty(), clock.instant())
                })
            }
            else -> Unit
        }
    }

    // ---------- helpers ----------

    private fun today() = LocalDate.now(clock)
    private fun err(msg: String) = buildJsonObject { put("error", msg) }.toString()
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
    private fun JsonObject.date(k: String) = s(k).takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    private fun String.toMeal(): Meal? = Meal.entries.firstOrNull { it.name.equals(this, true) || it.label.equals(this, true) }
}
