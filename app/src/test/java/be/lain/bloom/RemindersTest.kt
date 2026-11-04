package be.lain.bloom

import be.lain.bloom.data.AppSettings
import be.lain.bloom.data.Reminder
import be.lain.bloom.service.nextReminderTime
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime

class RemindersTest {
    private val s = AppSettings(reviewDay = DayOfWeek.MONDAY, reviewTime = 9 * 60, weighInTime = 7 * 60 + 30)

    @Test fun dailyReminderLaterToday() {
        val wed6 = LocalDateTime.of(2026, 11, 4, 6, 0)
        assertEquals(LocalDateTime.of(2026, 11, 4, 7, 30), nextReminderTime(Reminder.WEIGH_IN, s, wed6))
    }

    @Test fun dailyReminderTomorrowOncePassed() {
        val wed730 = LocalDateTime.of(2026, 11, 4, 7, 30)
        assertEquals(LocalDateTime.of(2026, 11, 5, 7, 30), nextReminderTime(Reminder.WEIGH_IN, s, wed730))
    }

    @Test fun reviewOnTheChosenDay() {
        val wed = LocalDateTime.of(2026, 11, 4, 12, 0)
        assertEquals(LocalDateTime.of(2026, 11, 9, 9, 0), nextReminderTime(Reminder.REVIEW, s, wed))
        val monEarly = LocalDateTime.of(2026, 11, 9, 8, 0)
        assertEquals(LocalDateTime.of(2026, 11, 9, 9, 0), nextReminderTime(Reminder.REVIEW, s, monEarly))
        val monLate = LocalDateTime.of(2026, 11, 9, 9, 1)
        assertEquals(LocalDateTime.of(2026, 11, 16, 9, 0), nextReminderTime(Reminder.REVIEW, s, monLate))
    }
}
