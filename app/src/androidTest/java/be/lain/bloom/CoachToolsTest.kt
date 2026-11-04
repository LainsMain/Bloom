package be.lain.bloom

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import be.lain.bloom.data.BodyRepository
import be.lain.bloom.data.ProfileRepository
import be.lain.bloom.data.SettingsRepository
import be.lain.bloom.data.ai.MoreTools
import be.lain.bloom.data.db.AiActionEntity
import be.lain.bloom.data.db.AiActionKind
import be.lain.bloom.data.db.AiActionStatus
import be.lain.bloom.data.db.BloomDatabase
import be.lain.bloom.data.db.ChatEntity
import be.lain.bloom.data.db.DiaryEntryEntity
import be.lain.bloom.data.db.PlanVersionEntity
import be.lain.bloom.data.db.ProfileEntity
import be.lain.bloom.data.food.FoodRepository
import be.lain.bloom.data.food.GenericFoodDb
import be.lain.bloom.data.food.OpenFoodFacts
import be.lain.bloom.data.health.HealthSync
import be.lain.bloom.data.review.ReviewRepository
import be.lain.bloom.data.training.RunRepository
import be.lain.bloom.data.training.TrainingRepository
import be.lain.bloom.domain.EnergyFormula
import be.lain.bloom.domain.Goal
import be.lain.bloom.domain.Lifestyle
import be.lain.bloom.domain.Meal
import be.lain.bloom.domain.Nutrients
import be.lain.bloom.domain.PlanSource
import be.lain.bloom.service.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** Calls the coach's new tools exactly as the model would (JSON in, JSON out) against a real database. */
@RunWith(AndroidJUnit4::class)
class CoachToolsTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = Clock.system(ZoneId.systemDefault())
    private val today = LocalDate.now(clock)
    private lateinit var db: BloomDatabase
    private lateinit var tools: MoreTools
    private lateinit var food: FoodRepository
    private lateinit var profiles: ProfileRepository
    private var chatId = 0L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, BloomDatabase::class.java).build()
        val settings = SettingsRepository(context)
        val scope = CoroutineScope(Dispatchers.Default)
        val health = HealthSync(context, db, settings, clock, scope)
        food = FoodRepository(db, GenericFoodDb(context), OpenFoodFacts(OkHttpClient()), clock)
        val body = BodyRepository(db, { health }, clock)
        profiles = ProfileRepository(db, clock)
        val training = TrainingRepository(context, db, settings, clock)
        val runs = RunRepository(context, db, clock)
        val reviews = ReviewRepository(db, profiles, body, food, training, runs, settings, be.lain.bloom.data.cycle.CycleRepository(db, settings, clock), clock)
        tools = MoreTools(db, food, body, profiles, training, runs, reviews, Reminders(context, settings, clock), be.lain.bloom.data.ai.PhotoBarcodes(context), be.lain.bloom.data.cycle.CycleRepository(db, settings, clock), clock)
        db.profileDao().upsert(
            ProfileEntity(
                name = "Test", goal = Goal.LOSE, formula = EnergyFormula.FEMALE, birthYear = 2000, heightCm = 176.0, startWeightKg = 85.0,
                goalWeightKg = 59.0, lifestyle = Lifestyle.DESK, paceKgPerWeek = 0.5, strengthDays = setOf(DayOfWeek.MONDAY),
                runDays = setOf(DayOfWeek.TUESDAY), startDate = today.minusDays(30), updatedAt = clock.instant(),
            ),
        )
        db.planDao().insert(PlanVersionEntity(effectiveDate = today.minusDays(30), kcal = 1600, proteinG = 120, carbsG = 160, fatG = 50, fiberG = 25,
            expenditureKcal = 2150, source = PlanSource.WEEKLY_REVIEW, reason = "test", createdAt = clock.instant()))
        chatId = db.chatDao().insertChat(ChatEntity(title = "t", createdAt = clock.instant(), updatedAt = clock.instant()))
    }

    @After fun tearDown() = db.close()

    private fun call(name: String, args: String): JsonObject = runBlocking {
        val out = tools.execute(chatId, name, Json.parseToJsonElement(args).jsonObject, { ref -> ref.removePrefix("food:").toLongOrNull()?.let { food.food(it) } }) { c, k, st, sum, pl ->
            db.chatDao().insertAction(AiActionEntity(chatId = c, kind = k, status = st, summary = sum, payload = pl.toString(), createdAt = clock.instant()))
        }
        Json.parseToJsonElement(out).jsonObject
    }

    private fun lastAction(): AiActionEntity = runBlocking { db.chatDao().observeActions(chatId).first().last() }

    @Test fun recipeIsSavedLoggableAndUndoable() {
        val r = call("create_recipe", """{"name":"Skyr bowl","servings":1,"ingredients":[
            {"name":"Skyr","grams":200,"kcal":126,"protein":22,"carbs":8,"fat":0.4},
            {"name":"Blueberries","grams":80,"kcal":46,"protein":0.6,"carbs":11,"fat":0.3},
            {"name":"Oats","grams":30,"kcal":114,"protein":4,"carbs":18,"fat":2}]}""")
        val ref = r["food_ref"]!!.jsonPrimitive.content
        val f = runBlocking { food.food(ref.removePrefix("food:").toLong()) }!!
        assertEquals(286.0, f.per100.forGrams(310.0).kcal!!, 1.0)
        assertEquals(310.0, f.servings.first().grams, 0.1)
        val a = lastAction()
        assertEquals(AiActionKind.CREATE_RECIPE, a.kind)
        runBlocking { tools.undo(a.kind, Json.parseToJsonElement(a.payload).jsonObject) }
        assertTrue(runBlocking { db.recipeDao().observeAll().first() }.isEmpty())
    }

    @Test fun workoutIsLoggedWithProgramSlotsAndShowsInDetails() {
        val r = call("log_workout", """{"name":"Foundation A","session":"A","minutes":28,"rpe":6,
            "exercises":[{"exercise":"box-squat","sets":2,"reps":12,"effort":"good"},{"exercise":"Glute bridge","sets":2,"reps":15},{"exercise":"handstand","sets":1}]}""")
        assertEquals(4, r["sets"]!!.jsonPrimitive.content.toInt())
        assertTrue(r.toString().contains("handstand"))
        val d = call("get_workout_details", """{"from":"${today.minusDays(1)}","to":"$today"}""").toString()
        assertTrue(d, d.contains("Foundation A") && d.contains("12 reps"))
        // Linked to the program slot (not "manual:"), so the in-app progression sees it next time.
        val w = runBlocking { db.trainingDao().workouts(today, today) }.single()
        val squat = runBlocking { db.trainingDao().sets(w.id) }.first { it.exerciseId == "box-squat" }
        assertTrue(squat.slotKey, !squat.slotKey.startsWith("manual:"))
        val a = lastAction()
        runBlocking { tools.undo(a.kind, Json.parseToJsonElement(a.payload).jsonObject) }
        assertTrue(runBlocking { db.trainingDao().workouts(today.minusDays(1), today) }.isEmpty())
    }

    @Test fun runAndMeasurementsAreLogged() {
        call("log_run", """{"minutes":30,"distance_km":3.5,"title":"Run-walk"}""")
        val run = runBlocking { db.runDao().runs(today, today) }.single()
        assertEquals(3500.0, run.distanceM, 0.1)
        assertTrue(run.finished && run.kcal > 0)
        call("log_measurements", """{"waist":88.5,"hips":104}""")
        val m = runBlocking { db.bodyDao().observeMeasurements().first() }.single()
        assertEquals(88.5, m.waistCm!!, 0.0)
        assertNull(m.chestCm)
        assertTrue(call("log_measurements", "{}").containsKey("error"))
    }

    @Test fun mealCopyAndNutritionHistory() {
        val y = today.minusDays(1)
        runBlocking {
            listOf(300.0, 200.0).forEach { k ->
                db.diaryDao().insert(DiaryEntryEntity(date = y, meal = Meal.BREAKFAST, at = clock.instant(), name = "x", grams = 100.0, amount = 100.0,
                    unit = "g", nutrients = Nutrients(kcal = k, protein = 10.0)))
            }
        }
        val c = call("copy_meal", """{"from_date":"$y","from_meal":"breakfast"}""")
        assertEquals(2, c["copied"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, runBlocking { db.diaryDao().day(today) }.size)
        val n = call("get_nutrition_averages", """{"from":"${today.minusDays(89)}","to":"$today"}""")
        assertEquals(2, n["days_logged"]!!.jsonPrimitive.content.toInt())
        assertEquals("$y 500 kcal P20 C0 F0 fib0 unmarked", (n["days"] as kotlinx.serialization.json.JsonArray)[88].jsonPrimitive.content)
    }

    @Test fun cycleToolsLogReadAndUndo() {
        val settings = SettingsRepository(context)
        val wasOn = runBlocking { settings.settings.first().cycleTracking }
        try {
            runBlocking { settings.setCycleTracking(false) }
            assertTrue(call("log_period", """{"from":"$today","period":true}""").containsKey("error"))
            runBlocking { settings.setCycleTracking(true) }
            val start = today.minusDays(3)
            val r = call("log_period", """{"from":"$start","to":"${today.minusDays(1)}","period":true,"flow":"medium"}""")
            assertEquals(4, r["cycle_day"]!!.jsonPrimitive.content.toInt())
            assertEquals(3, runBlocking { db.cycleDao().all() }.count { it.period })
            call("log_cycle_symptoms", """{"symptoms":"cramps, bloating"}""")
            assertEquals("cramps,bloating", runBlocking { db.cycleDao().get(today) }!!.symptoms)
            val g = call("get_cycle", "{}")
            assertTrue(g.toString(), g.toString().contains("cramps"))
            // Undo the period logging: those days go back to not logged.
            val logPeriod = runBlocking { db.chatDao().observeActions(chatId).first() }.first { it.kind == AiActionKind.LOG_CYCLE }
            runBlocking { tools.undo(logPeriod.kind, Json.parseToJsonElement(logPeriod.payload).jsonObject) }
            assertEquals(0, runBlocking { db.cycleDao().all() }.count { it.period })
        } finally {
            runBlocking { settings.setCycleTracking(wasOn) }
        }
    }

    @Test fun editingTheProfileKeepsTheMeasuredBurn() = runBlocking {
        val before = db.planDao().observeAll().first().size
        val p = profiles.profile.first()!!
        // Name and notes only: no new plan.
        profiles.updateProfile(p.copy(name = "Renamed", notes = "Trans woman, 1 year on HRT"))
        assertEquals(before, db.planDao().observeAll().first().size)
        assertEquals("Trans woman, 1 year on HRT", profiles.profile.first()!!.notes)
        // A slower pace: new plan, but built on the reviewed burn (2150), not the formula.
        profiles.updateProfile(profiles.profile.first()!!.copy(paceKgPerWeek = 0.25))
        val plan = db.planDao().activeOn(today)!!
        assertEquals(2150, plan.expenditureKcal)
        assertEquals("Trans woman, 1 year on HRT", profiles.profile.first()!!.notes)
    }

    @Test fun goalChangeIsAProposalAndKeepsTheMeasuredBurn() {
        assertTrue(call("propose_goal_change", """{"goal_kg":50,"reason":"x"}""").containsKey("error"))
        call("propose_goal_change", """{"goal_kg":65,"pace_kg_per_week":0.4,"reason":"Reassess at 70"}""")
        val a = lastAction()
        assertEquals(AiActionStatus.PENDING, a.status)
        assertEquals(59.0, runBlocking { db.profileDao().get() }!!.goalWeightKg, 0.0)
        runBlocking { tools.acceptGoal(Json.parseToJsonElement(a.payload).jsonObject) }
        val p = runBlocking { db.profileDao().get() }!!
        assertEquals(65.0, p.goalWeightKg, 0.0)
        assertEquals(0.4, p.paceKgPerWeek, 0.0)
        val plan = runBlocking { db.planDao().activeOn(today) }
        assertNotNull(plan)
        assertEquals(2150, plan!!.expenditureKcal)
    }
}
