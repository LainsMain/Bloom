package be.lain.bloom.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

enum class CyclePhase(val label: String, val note: String) {
    MENSTRUAL("Period", "Water retention and bloating are common in the first days, so the scale often reads a little high."),
    FOLLICULAR("Follicular", "Energy often rises after your period: a good time to push your workouts."),
    OVULATION("Ovulation", "Around mid-cycle. Some people feel strongest now; others notice a small water bump."),
    LUTEAL("Luteal", "Hunger and water weight often rise, especially in the last days before your period."),
}

data class CycleStats(
    val starts: List<LocalDate>,
    /** Recent cycle lengths in days (start to next start), oldest first. */
    val cycleLengths: List<Int>,
    val avgCycle: Int,
    val avgPeriod: Int,
    /** False until at least one full cycle is logged; averages are defaults until then. */
    val lengthsKnown: Boolean,
)

data class CycleDay(
    /** 1 on the first day of the period. */
    val day: Int,
    val phase: CyclePhase?,
    val nextStart: LocalDate,
    val daysUntilNext: Int,
    /** Later than the usual cycle length. */
    val late: Boolean,
    /** Days around the period when water weight commonly hides fat loss. */
    val waterRetention: Boolean,
)

/** Cycle maths from logged period days. Pure and deterministic; nothing here is a medical prediction. */
object Cycle {
    const val DEFAULT_CYCLE = 28
    const val DEFAULT_PERIOD = 5
    private const val MIN_GAP_BETWEEN_STARTS = 15
    private const val MAX_GAP_INSIDE_PERIOD = 2

    /** Period starts: a period day with no period day in the 3 days before it, at least 15 days after the last start. */
    fun starts(periodDays: Collection<LocalDate>): List<LocalDate> {
        val days = periodDays.toSortedSet()
        val out = mutableListOf<LocalDate>()
        for (d in days) {
            val continues = (1..MAX_GAP_INSIDE_PERIOD + 1).any { days.contains(d.minusDays(it.toLong())) }
            if (continues) continue
            val last = out.lastOrNull()
            if (last != null && ChronoUnit.DAYS.between(last, d) < MIN_GAP_BETWEEN_STARTS) continue
            out += d
        }
        return out
    }

    /** Logged period days that belong to the period beginning at [start] (allowing short gaps). */
    fun periodLength(start: LocalDate, periodDays: Set<LocalDate>): Int {
        var last = start
        var d = start.plusDays(1)
        while (ChronoUnit.DAYS.between(last, d) <= MAX_GAP_INSIDE_PERIOD + 1 && ChronoUnit.DAYS.between(start, d) < 12) {
            if (periodDays.contains(d)) last = d
            d = d.plusDays(1)
        }
        return ChronoUnit.DAYS.between(start, last).toInt() + 1
    }

    fun stats(periodDays: Collection<LocalDate>): CycleStats {
        val set = periodDays.toSet()
        val starts = starts(set)
        val lengths = starts.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }.filter { it in 18..60 }.takeLast(6)
        // Only count periods logged as more than a single "start" tap.
        val periods = starts.map { periodLength(it, set) }.filter { it > 1 }.takeLast(6)
        return CycleStats(
            starts = starts,
            cycleLengths = lengths,
            avgCycle = lengths.takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: DEFAULT_CYCLE,
            avgPeriod = periods.takeIf { it.isNotEmpty() }?.average()?.roundToInt()?.coerceIn(2, 9) ?: DEFAULT_PERIOD,
            lengthsKnown = lengths.isNotEmpty(),
        )
    }

    /** Where [date] falls in the cycle, or null before the first logged period. */
    fun on(date: LocalDate, periodDays: Set<LocalDate>, stats: CycleStats = stats(periodDays)): CycleDay? {
        val start = stats.starts.lastOrNull { !it.isAfter(date) } ?: return null
        val day = ChronoUnit.DAYS.between(start, date).toInt() + 1
        val logged = periodLength(start, periodDays)
        val periodLen = if (logged > 1) logged else stats.avgPeriod
        // A little late: the period is overdue (untilNext ≤ 0). Very late: probably not logged, so roll forward.
        var next = start.plusDays(stats.avgCycle.toLong())
        if (day > stats.avgCycle + 7) while (!next.isAfter(date)) next = next.plusDays(stats.avgCycle.toLong())
        val ovulation = (stats.avgCycle - 14).coerceAtLeast(periodLen + 2)
        val phase = when {
            periodDays.contains(date) || day <= periodLen -> CyclePhase.MENSTRUAL
            day < ovulation - 1 -> CyclePhase.FOLLICULAR
            day <= ovulation + 1 -> CyclePhase.OVULATION
            day <= stats.avgCycle + 7 -> CyclePhase.LUTEAL
            else -> null // Very late or a period wasn't logged: don't guess.
        }
        val untilNext = ChronoUnit.DAYS.between(date, next).toInt()
        val late = day > stats.avgCycle + 2
        val water = (phase == CyclePhase.MENSTRUAL && day <= 3) ||
            (phase == CyclePhase.LUTEAL && (stats.avgCycle - day) < 5) || (late && phase != null)
        return CycleDay(day, phase, next, untilNext, late, water)
    }

    /** The next few predicted period days, for the calendar. */
    fun predictedPeriodDays(date: LocalDate, periodDays: Set<LocalDate>, stats: CycleStats = stats(periodDays), cycles: Int = 2): Set<LocalDate> {
        val c = on(date, periodDays, stats) ?: return emptySet()
        val out = mutableSetOf<LocalDate>()
        var start = c.nextStart
        repeat(cycles) {
            for (i in 0 until stats.avgPeriod) out += start.plusDays(i.toLong())
            start = start.plusDays(stats.avgCycle.toLong())
        }
        return out - periodDays
    }
}
