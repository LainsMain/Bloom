package be.lain.bloom

import be.lain.bloom.domain.Cycle
import be.lain.bloom.domain.CyclePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CycleTest {
    private fun d(m: Int, day: Int) = LocalDate.of(2026, m, day)
    private fun range(a: LocalDate, n: Int) = (0 until n).map { a.plusDays(it.toLong()) }

    // Two 5-day periods, 28 days apart.
    private val days = (range(d(10, 1), 5) + range(d(10, 29), 5)).toSet()

    @Test fun findsStartsLengthsAndAverages() {
        val s = Cycle.stats(days)
        assertEquals(listOf(d(10, 1), d(10, 29)), s.starts)
        assertEquals(listOf(28), s.cycleLengths)
        assertEquals(28, s.avgCycle)
        assertEquals(5, s.avgPeriod)
        assertTrue(s.lengthsKnown)
    }

    @Test fun spottingAndShortGapsDontStartANewCycle() {
        val s = Cycle.stats(setOf(d(10, 1), d(10, 2), d(10, 4), d(10, 10)))
        assertEquals(listOf(d(10, 1)), s.starts)
        assertEquals(4, Cycle.periodLength(d(10, 1), setOf(d(10, 1), d(10, 2), d(10, 4))))
        assertFalse(s.lengthsKnown)
        assertEquals(Cycle.DEFAULT_CYCLE, s.avgCycle)
    }

    @Test fun phasesAcrossTheCycle() {
        assertEquals(CyclePhase.MENSTRUAL, Cycle.on(d(10, 31), days)!!.phase)
        assertTrue(Cycle.on(d(10, 31), days)!!.waterRetention)
        assertEquals(CyclePhase.FOLLICULAR, Cycle.on(d(11, 5), days)!!.phase)
        assertEquals(CyclePhase.OVULATION, Cycle.on(d(11, 11), days)!!.phase)
        val luteal = Cycle.on(d(11, 20), days)!!
        assertEquals(CyclePhase.LUTEAL, luteal.phase)
        assertEquals(23, luteal.day)
        assertEquals(d(11, 26), luteal.nextStart)
        assertFalse(luteal.waterRetention)
        assertTrue(Cycle.on(d(11, 23), days)!!.waterRetention)
    }

    @Test fun latePeriodIsOverdueNotSkipped() {
        val c = Cycle.on(d(11, 28), days)!!
        assertTrue(c.late)
        assertEquals(d(11, 26), c.nextStart)
        assertEquals(-2, c.daysUntilNext)
        assertTrue(c.waterRetention)
    }

    @Test fun nothingBeforeTheFirstPeriod() {
        assertNull(Cycle.on(d(9, 20), days))
        assertNull(Cycle.on(d(11, 1), emptySet()))
    }

    @Test fun predictsTheNextPeriods() {
        val p = Cycle.predictedPeriodDays(d(11, 10), days)
        assertTrue(p.containsAll(range(d(11, 26), 5)))
        assertTrue(p.contains(d(12, 24)))
    }
}
