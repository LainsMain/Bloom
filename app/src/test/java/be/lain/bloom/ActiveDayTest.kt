package be.lain.bloom

import be.lain.bloom.core.ActiveDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class ActiveDayTest {
    private class MovableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    private val clock = MovableClock(Instant.parse("2026-11-11T00:30:00Z"))
    private val today = LocalDate.of(2026, 11, 11)
    private val day = ActiveDay(clock, CoroutineScope(Dispatchers.Unconfined))

    @Test fun followsTodayUntilAnotherDayIsPicked() {
        assertEquals(today, day.current())
        assertFalse(day.isOtherDay.value)
        day.select(today.minusDays(1))
        assertEquals(today.minusDays(1), day.current())
        assertEquals(today.minusDays(1), day.date.value)
        assertTrue(day.isOtherDay.value)
        day.shift(1)
        assertEquals(today, day.current())
        assertFalse("picking today means following today again", day.isOtherDay.value)
    }

    @Test fun backToToday() {
        day.shift(-3)
        day.backToToday()
        assertEquals(today, day.current())
        assertFalse(day.isOtherDay.value)
    }

    @Test fun aPickedDayIsForgottenAfterHoursAway() {
        day.select(today.minusDays(1))
        day.markSeen()
        clock.now = clock.now.plusSeconds(60 * 60) // an hour later: still on yesterday
        day.refresh()
        assertEquals(today.minusDays(1), day.current())
        day.markSeen()
        clock.now = clock.now.plusSeconds(4 * 60 * 60) // next morning
        day.refresh()
        assertEquals(today, day.current())
        assertFalse(day.isOtherDay.value)
    }

    @Test fun followsTheCalendarAcrossMidnight() {
        clock.now = Instant.parse("2026-11-11T23:59:00Z")
        day.refresh()
        clock.now = Instant.parse("2026-11-12T00:01:00Z")
        day.refresh()
        assertEquals(LocalDate.of(2026, 11, 12), day.current())
        assertEquals(LocalDate.of(2026, 11, 12), day.date.value)
    }
}
