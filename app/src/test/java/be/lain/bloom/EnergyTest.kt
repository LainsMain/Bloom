package be.lain.bloom

import be.lain.bloom.domain.Energy
import be.lain.bloom.domain.EnergyFormula
import be.lain.bloom.domain.Goal
import be.lain.bloom.domain.Lifestyle
import be.lain.bloom.domain.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate

class EnergyTest {
    private val today = LocalDate.of(2026, 11, 3)
    private val profile = Profile(
        name = "Test",
        goal = Goal.LOSE,
        formula = EnergyFormula.FEMALE,
        birthYear = 2000,
        heightCm = 176.0,
        startWeightKg = 85.0,
        goalWeightKg = 59.0,
        lifestyle = Lifestyle.DESK,
        paceKgPerWeek = 0.5,
        strengthDays = setOf(MONDAY, WEDNESDAY, FRIDAY),
        runDays = setOf(TUESDAY, SATURDAY),
        startDate = today,
    )

    @Test fun bmrMatchesMifflinStJeor() {
        assertEquals(1659.0, Energy.bmr(EnergyFormula.FEMALE, 85.0, 176.0, 26), 0.01)
        assertEquals(1825.0, Energy.bmr(EnergyFormula.MALE, 85.0, 176.0, 26), 0.01)
        assertEquals(1742.0, Energy.bmr(EnergyFormula.BLEND, 85.0, 176.0, 26), 0.01)
    }

    @Test fun trainingRaisesActivityFactorWithCap() {
        assertEquals(1.325, Energy.activityFactor(Lifestyle.DESK, 5), 1e-9)
        assertEquals(1.35, Energy.activityFactor(Lifestyle.DESK, 20), 1e-9)
    }

    @Test fun moderateDeficitTargets() {
        val r = Energy.targets(profile, 85.0, today)
        assertEquals(2200, r.expenditure)
        assertEquals(1650, r.targets.kcal)
        assertFalse(r.limitedByFloor)
        assertEquals(120, r.targets.proteinG)
        assertEquals(50, r.targets.fatG)
        assertEquals(180, r.targets.carbsG)
        assertEquals(25, r.targets.fiberG)
        assertEquals(-0.5, r.impliedKgPerWeek, 0.05)
    }

    @Test fun aggressivePaceIsHeldAtSafetyFloor() {
        val r = Energy.targets(profile.copy(paceKgPerWeek = 1.0), 85.0, today)
        assertTrue(r.limitedByFloor)
        assertEquals(1500, r.targets.kcal)
        assertTrue(r.targets.kcal >= r.floor)
        assertTrue(r.impliedKgPerWeek > -1.0)
    }

    @Test fun macrosAddUpToCalories() {
        val t = Energy.targets(profile, 85.0, today).targets
        val kcalFromMacros = t.proteinG * 4 + t.carbsG * 4 + t.fatG * 9
        assertEquals(t.kcal.toDouble(), kcalFromMacros.toDouble(), 6.0)
    }

    @Test fun paceEasesNearGoal() {
        assertEquals(0.5, Energy.effectivePace(Goal.LOSE, 0.5, 70.0, 59.0), 1e-9)
        assertEquals(0.25, Energy.effectivePace(Goal.LOSE, 0.5, 60.5, 59.0), 1e-9)
        assertEquals(0.0, Energy.effectivePace(Goal.LOSE, 0.5, 58.0, 59.0), 1e-9)
        assertEquals(0.0, Energy.effectivePace(Goal.MAINTAIN, 0.5, 70.0, 59.0), 1e-9)
    }

    @Test fun projectionReachesGoalMonotonically() {
        val p = Energy.projection(profile, 85.0)
        assertEquals(59.0, p.last(), 0.06)
        assertTrue(p.zipWithNext().all { (a, b) -> b <= a })
        // 26 kg at 0.5 kg/week with easing: a bit more than a year.
        assertTrue(p.size in 53..70)
    }

    @Test fun flooredPaceDoesNotPromiseNominalSpeed() {
        val nominalWeeks = (26.0 / 1.0).toInt()
        val weeks = Energy.projection(profile.copy(paceKgPerWeek = 1.0), 85.0).size - 1
        assertTrue("expected slower than nominal, got $weeks", weeks > nominalWeeks + 10)
    }

    @Test fun maintainUsesExpenditure() {
        val r = Energy.targets(profile.copy(goal = Goal.MAINTAIN), 59.0, today)
        assertEquals(r.expenditure, r.targets.kcal)
    }

    @Test fun bmiHelpers() {
        assertEquals(19.05, Energy.bmi(59.0, 176.0), 0.01)
        assertEquals(57.3, Energy.weightForBmi(18.5, 176.0), 0.1)
    }
}
