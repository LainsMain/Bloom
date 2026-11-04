package be.lain.bloom

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import be.lain.bloom.data.db.BloomDatabase
import be.lain.bloom.data.db.PlanVersionEntity
import be.lain.bloom.data.db.ProfileEntity
import be.lain.bloom.data.db.WaterEntryEntity
import be.lain.bloom.data.db.WeightEntryEntity
import be.lain.bloom.domain.EnergyFormula
import be.lain.bloom.domain.Goal
import be.lain.bloom.domain.Lifestyle
import be.lain.bloom.domain.PlanSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: BloomDatabase
    private val d0 = LocalDate.of(2026, 11, 3)

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, BloomDatabase::class.java).build()
    }

    @After fun close() = db.close()

    private fun plan(date: LocalDate, kcal: Int) = PlanVersionEntity(
        effectiveDate = date, kcal = kcal, proteinG = 100, carbsG = 150, fatG = 50, fiberG = 25,
        expenditureKcal = 2200, source = PlanSource.MANUAL, reason = "test", createdAt = Instant.EPOCH,
    )

    @Test fun activePlanIsLatestEffectiveOnOrBeforeDate() = runBlocking {
        val dao = db.planDao()
        dao.insert(plan(d0, 1650))
        dao.insert(plan(d0.plusDays(7), 1600))
        assertNull(dao.activeOn(d0.minusDays(1)))
        assertEquals(1650, dao.activeOn(d0.plusDays(6))!!.kcal)
        assertEquals(1600, dao.activeOn(d0.plusDays(7))!!.kcal)
    }

    @Test fun profileRoundTripsDaySets() = runBlocking {
        val p = ProfileEntity(
            name = "Lain", goal = Goal.LOSE, formula = EnergyFormula.BLEND, birthYear = 2000, heightCm = 176.0,
            startWeightKg = 85.0, goalWeightKg = 59.0, lifestyle = Lifestyle.DESK, paceKgPerWeek = 0.5,
            strengthDays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), runDays = emptySet(), startDate = d0,
            updatedAt = Instant.EPOCH,
        )
        db.profileDao().upsert(p)
        val back = db.profileDao().get()!!
        assertEquals(p.strengthDays, back.strengthDays)
        assertEquals(emptySet<DayOfWeek>(), back.runDays)
        assertEquals(EnergyFormula.BLEND, back.formula)
    }

    @Test fun waterUndoRemovesOnlyTheLastEntryOfThatDay() = runBlocking {
        val dao = db.bodyDao()
        dao.insertWater(WaterEntryEntity(date = d0, at = Instant.ofEpochMilli(1), ml = 250))
        dao.insertWater(WaterEntryEntity(date = d0, at = Instant.ofEpochMilli(2), ml = 500))
        dao.insertWater(WaterEntryEntity(date = d0.plusDays(1), at = Instant.ofEpochMilli(3), ml = 330))
        dao.deleteLastWater(d0)
        assertEquals(listOf(250), dao.observeWater(d0).first().map { it.ml })
        assertEquals(listOf(330), dao.observeWater(d0.plusDays(1)).first().map { it.ml })
    }

    @Test fun weightsAreOrderedByDate() = runBlocking {
        val dao = db.bodyDao()
        dao.insertWeight(WeightEntryEntity(date = d0.plusDays(1), at = Instant.ofEpochMilli(2), kg = 84.0))
        dao.insertWeight(WeightEntryEntity(date = d0, at = Instant.ofEpochMilli(1), kg = 85.0))
        assertEquals(listOf(85.0, 84.0), dao.weights().map { it.kg })
    }
}
