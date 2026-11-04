package be.lain.bloom

import be.lain.bloom.domain.Adaptive
import be.lain.bloom.domain.DayStatus
import be.lain.bloom.domain.EnergyFormula
import be.lain.bloom.domain.Goal
import be.lain.bloom.domain.Lifestyle
import be.lain.bloom.domain.Profile
import be.lain.bloom.domain.WeightPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class AdaptiveTest {
    private val review = LocalDate.of(2026, 12, 14) // a Monday
    private val start = review.minusWeeks(6)
    private val profile = Profile(
        name = "Test", goal = Goal.LOSE, formula = EnergyFormula.FEMALE, birthYear = 2000, heightCm = 176.0,
        startWeightKg = 85.0, goalWeightKg = 59.0, lifestyle = Lifestyle.DESK, paceKgPerWeek = 0.5,
        strengthDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
        runDays = setOf(DayOfWeek.TUESDAY, DayOfWeek.SATURDAY), startDate = start,
    )

    /** Daily weigh-ins falling linearly at [kgPerWeek] from the start date up to the review day. */
    private fun weights(kgPerWeek: Double, every: Int = 1): List<WeightPoint> {
        val days = java.time.temporal.ChronoUnit.DAYS.between(start, review).toInt()
        return (0..days step every).map { WeightPoint(start.plusDays(it.toLong()), 85.0 - kgPerWeek * it / 7.0) }
    }

    private fun week(kcal: Double, complete: Int = 7) = (1..7).map { i ->
        Adaptive.Day(review.minusDays(i.toLong()), kcal, if (i <= complete) DayStatus.COMPLETE else DayStatus.PARTIAL)
    }

    private fun input(weights: List<WeightPoint>, days: List<Adaptive.Day>, current: Int = 1650, prior: Int = 2200) =
        Adaptive.Input(profile, weights, days, currentTargetKcal = current, priorExpenditure = prior, reviewDate = review)

    @Test fun measuresExpenditureFromIntakeAndTrend() {
        // Eating 1,800 kcal while losing 0.5 kg/week means burning about 1,800 + 550 = 2,350.
        val r = Adaptive.review(input(weights(0.5), week(1800.0)))
        assertEquals(2350.0, r.measuredExpenditure!!.toDouble(), 60.0)
        assertEquals(0.5, r.confidence, 1e-9)
        // Halfway from the prior 2,200 towards ~2,350.
        assertTrue(r.newExpenditure in 2250..2300)
        assertTrue(r.proposedKcal > r.currentKcal)
        assertFalse(r.capped)
        assertEquals(7, r.completeDays)
    }

    @Test fun fewCompleteDaysKeepsTheEstimate() {
        val r = Adaptive.review(input(weights(0.5), week(1800.0, complete = 2)))
        assertNull(r.measuredExpenditure)
        assertEquals(2200, r.newExpenditure)
        assertTrue(r.notes.any { "fully logged" in it })
    }

    @Test fun fewWeighInsKeepsTheEstimate() {
        val r = Adaptive.review(input(weights(0.5, every = 7).takeLast(2), week(1800.0)))
        assertNull(r.measuredExpenditure)
        assertEquals(2200, r.newExpenditure)
    }

    @Test fun changeIsCappedPerWeek() {
        // Losing 1 kg/week on 2,300 kcal implies a much higher burn; the target may only rise by 150.
        val r = Adaptive.review(input(weights(1.0), week(2300.0)))
        assertTrue(r.capped)
        assertEquals(1650 + Adaptive.MAX_WEEKLY_CHANGE, r.proposedKcal)
        assertTrue(r.uncappedKcal > r.proposedKcal)
    }

    @Test fun neverBelowSafetyFloor() {
        // A low prior burn would put the target under the floor.
        val r = Adaptive.review(input(weights(0.0), week(1300.0, complete = 1), current = 1300, prior = 1700))
        assertTrue(r.limitedByFloor)
        assertTrue(r.proposedKcal >= r.floor)
        assertEquals(r.proposedKcal, r.targets.kcal)
    }

    @Test fun implausibleWeekIsIgnored() {
        // 900 kcal "eaten" with no loss implies a burn below resting energy: missed logs, not real.
        val r = Adaptive.review(input(weights(0.0), week(900.0)))
        assertNull(r.measuredExpenditure)
        assertEquals(2200, r.newExpenditure)
    }

    @Test fun earlyWeeksCountForLess() {
        val early = profile.copy(startDate = review.minusDays(14))
        val r = Adaptive.review(Adaptive.Input(early, weights(0.5).filter { !it.date.isBefore(early.startDate) }, week(1800.0), 1650, 2200, review))
        assertEquals(0.25, r.confidence, 0.05)
    }

    @Test fun periodWaterWeightCountsForLess() {
        val normal = Adaptive.review(input(weights(0.5), week(1800.0)))
        val swing = Adaptive.review(input(weights(0.5), week(1800.0)).copy(cycleSwing = true))
        assertEquals(normal.confidence / 2, swing.confidence, 1e-9)
        assertTrue(swing.notes.any { "period" in it })
    }

    @Test fun guessesDayStatus() {
        assertEquals(DayStatus.SKIPPED, Adaptive.guessStatus(0.0, 0, 0, 1700))
        assertEquals(DayStatus.COMPLETE, Adaptive.guessStatus(1500.0, 6, 3, 1700))
        assertEquals(DayStatus.PARTIAL, Adaptive.guessStatus(600.0, 2, 1, 1700))
        assertEquals(DayStatus.PARTIAL, Adaptive.guessStatus(1600.0, 1, 1, 1700))
    }

    @Test fun reviewDueOncePerWeekAfterFirstWeek() {
        val monday = LocalDate.of(2026, 11, 9)
        assertTrue(Adaptive.isDue(monday, DayOfWeek.MONDAY, monday.minusDays(8), null))
        assertFalse(Adaptive.isDue(monday, DayOfWeek.MONDAY, monday.minusDays(4), null))
        assertFalse(Adaptive.isDue(monday, DayOfWeek.MONDAY, monday.minusDays(30), monday))
        // Missed Monday: still due on Wednesday.
        assertTrue(Adaptive.isDue(monday.plusDays(2), DayOfWeek.MONDAY, monday.minusDays(30), monday.minusDays(7)))
        assertEquals(monday, Adaptive.lastReviewDay(monday.plusDays(6), DayOfWeek.MONDAY))
    }
}
