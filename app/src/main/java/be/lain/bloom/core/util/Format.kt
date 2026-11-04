package be.lain.bloom.core.util

import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val locale: Locale = Locale.UK

/** Number with thousands grouping and fixed decimals, e.g. 1,284 or 72.4. */
fun Double.fmt(decimals: Int = 1, grouping: Boolean = true): String {
    val nf = NumberFormat.getNumberInstance(locale)
    nf.minimumFractionDigits = decimals
    nf.maximumFractionDigits = decimals
    nf.isGroupingUsed = grouping
    return nf.format(this)
}

fun Int.fmt(): String = NumberFormat.getIntegerInstance(locale).format(this)

/** Signed value like "+0.4" / "−1.2" (true minus sign). */
fun Double.signed(decimals: Int = 1): String {
    val body = abs(this).fmt(decimals)
    return when {
        this > 0.0 && body.any { it in '1'..'9' } -> "+$body"
        this < 0.0 && body.any { it in '1'..'9' } -> "−$body"
        else -> body
    }
}

private val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", locale)
private val shortFmt = DateTimeFormatter.ofPattern("d MMM", locale)
private val shortYearFmt = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
private val monthYearFmt = DateTimeFormatter.ofPattern("MMMM yyyy", locale)

fun LocalDate.long(): String = format(dayFmt)
fun LocalDate.short(): String = format(shortFmt)
fun LocalDate.shortWithYear(): String = format(shortYearFmt)
fun LocalDate.monthYear(): String = format(monthYearFmt)

fun LocalDate.relative(today: LocalDate): String = when (this) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    today.plusDays(1) -> "Tomorrow"
    else -> if (year == today.year) short() else shortWithYear()
}
