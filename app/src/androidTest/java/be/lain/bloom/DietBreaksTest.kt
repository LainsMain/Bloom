package be.lain.bloom

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import be.lain.bloom.data.ProfileRepository
import be.lain.bloom.data.SettingsRepository
import be.lain.bloom.data.db.BloomDatabase
import be.lain.bloom.data.db.PlanVersionEntity
import be.lain.bloom.data.db.ProfileEntity
import be.lain.bloom.data.review.DietBreaks
import be.lain.bloom.domain.Adaptive
import be.lain.bloom.domain.EnergyFormula
import be.lain.bloom.domain.Goal
import be.lain.bloom.domain.Lifestyle
import be.lain.bloom.domain.PlanSource
import be.lain.bloom.domain.Targets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Diet breaks against a real database: maintenance while on it, fat-loss targets back afterwards. */
@RunWith(AndroidJUnit4::class)
class DietBreaksTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val day0 = LocalDate.of(2026, 11, 2)
    private lateinit var db: BloomDatabase
    private lateinit var settings: SettingsRepository

    private fun clockOn(d: LocalDate): Clock = Clock.fixed(d.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneId.of("UTC"))
    private fun breaks(on: LocalDate) = DietBreaks(db, settings, ProfileRepository(db, clockOn(on)), clockOn(on))
    private val maintenance = Adaptive.DietBreak(14, 2150, Targets(2150, 120, 260, 70, 30), listOf("test"))

    @Before fun setUp() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(context, BloomDatabase::class.java).build()
        settings = SettingsRepository(context)
        settings.finishDietBreak(0) // clean slate in the shared settings store
        db.profileDao().upsert(
            ProfileEntity(
                name = "Test", goal = Goal.LOSE, formula = EnergyFormula.FEMALE, birthYear = 2000, heightCm = 176.0, startWeightKg = 85.0,
                goalWeightKg = 59.0, lifestyle = Lifestyle.DESK, paceKgPerWeek = 0.5, strengthDays = setOf(DayOfWeek.MONDAY),
                runDays = setOf(DayOfWeek.TUESDAY), startDate = day0.minusDays(90), updatedAt = clockOn(day0).instant(),
            ),
        )
        db.planDao().insert(PlanVersionEntity(effectiveDate = day0.minusDays(90), kcal = 1650, proteinG = 120, carbsG = 180, fatG = 50, fiberG = 25,
            expenditureKcal = 2150, source = PlanSource.ONBOARDING, reason = "test", createdAt = clockOn(day0).instant()))
    }

    @After fun tearDown() {
        runBlocking { settings.finishDietBreak(0) }
        db.close()
    }

    @Test fun breakRunsAtMaintenanceThenEndsByItself() = runBlocking<Unit> {
        breaks(day0).start(maintenance, day0, 2150)
        assertEquals(2150, db.planDao().activeOn(day0)!!.kcal)
        val s = breaks(day0.plusDays(4)).current()!!
        assertEquals(5, s.day); assertEquals(14, s.length); assertEquals(10, s.daysLeft)

        // Still on the break on its last day.
        assertFalse(breaks(day0.plusDays(13)).finishIfDue())
        // The day after, the fat-loss target comes back and the break is cleared.
        val end = day0.plusDays(14)
        assertTrue(breaks(end).finishIfDue())
        assertTrue(db.planDao().activeOn(end)!!.kcal < 2150)
        assertNull(breaks(end).current())
        assertEquals(end.toEpochDay(), settings.settings.first().lastDietBreakEnd)
        // A second check doesn't add another plan.
        val plans = db.planDao().observeAll().first().size
        assertFalse(breaks(end).finishIfDue())
        assertEquals(plans, db.planDao().observeAll().first().size)
    }

    @Test fun cancellingABreakThatStartsTomorrowKeepsTheFatLossTarget() = runBlocking<Unit> {
        val tomorrow = day0.plusDays(1)
        breaks(day0).start(maintenance, tomorrow, 2150)
        val s = breaks(day0).current()!!
        assertTrue(s.upcoming)
        breaks(day0).finish("cancelled")
        assertEquals(1650, db.planDao().activeOn(day0)!!.kcal)
        assertTrue(db.planDao().activeOn(tomorrow)!!.kcal < 2150)
        assertNull(breaks(day0).current())
    }
}
