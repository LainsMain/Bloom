package be.lain.bloom.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Which day something counts for. After midnight a late workout or run usually belongs to the day
 * before, so anything started before 4 am defaults to yesterday.
 */
fun defaultLogDate(startedAt: Instant, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): LocalDate {
    val local = startedAt.atZone(zone)
    return if (local.toLocalDate() == today && local.toLocalTime() < LocalTime.of(4, 0)) today.minusDays(1) else local.toLocalDate()
}

private enum class DayChoice { TODAY, YESTERDAY, OTHER }

/** "Counts for: Today · Yesterday · Other day" — lets anything be logged for an earlier day. */
@Composable
fun LogDateRow(date: LocalDate, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier, today: LocalDate = LocalDate.now()) {
    var picking by remember { mutableStateOf(false) }
    val choice = when (date) { today -> DayChoice.TODAY; today.minusDays(1) -> DayChoice.YESTERDAY; else -> DayChoice.OTHER }
    val short = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    Column(modifier) {
        Text("Counts for", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        ConnectedChoice(
            listOf(DayChoice.TODAY, DayChoice.YESTERDAY, DayChoice.OTHER), choice,
            { when (it) { DayChoice.TODAY -> "Today"; DayChoice.YESTERDAY -> "Yesterday"; DayChoice.OTHER -> if (choice == DayChoice.OTHER) date.format(short) else "Other day" } },
            {
                when (it) {
                    DayChoice.TODAY -> onChange(today)
                    DayChoice.YESTERDAY -> onChange(today.minusDays(1))
                    DayChoice.OTHER -> picking = true
                }
            },
        )
    }
    if (picking) {
        val millis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val todayMillis = today.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = millis,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMillis
                override fun isSelectableYear(year: Int) = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}
