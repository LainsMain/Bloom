package be.lain.bloom.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.pow

data class WeightPoint(val date: LocalDate, val kg: Double)
data class TrendPoint(val date: LocalDate, val raw: Double?, val trend: Double)

/**
 * Exponentially smoothed weight trend that tolerates missing days.
 * Daily water swings are mostly noise; the trend is what we act on.
 */
object Trend {
    /** Smoothing per day; 0.1 ≈ a 10-day memory. */
    const val ALPHA = 0.1

    /** One trend point per calendar day from the first weigh-in to [until]. */
    fun daily(weights: List<WeightPoint>, until: LocalDate = weights.maxOfOrNull { it.date } ?: LocalDate.now()): List<TrendPoint> {
        if (weights.isEmpty()) return emptyList()
        // Average multiple weigh-ins on the same day.
        val byDay = weights.groupBy { it.date }.mapValues { (_, v) -> v.map { it.kg }.average() }
        val first = byDay.keys.min()
        val out = ArrayList<TrendPoint>()
        var trend = byDay.getValue(first)
        var day = first
        while (!day.isAfter(until)) {
            val raw = byDay[day]
            if (raw != null) trend += ALPHA * (raw - trend)
            out += TrendPoint(day, raw, trend)
            day = day.plusDays(1)
        }
        return out
    }

    /** Trend value on [date] (carried forward past the last weigh-in). */
    fun at(weights: List<WeightPoint>, date: LocalDate): Double? =
        daily(weights, date).lastOrNull()?.trend

    /**
     * Least-squares slope of the trend in kg/week over the last [days] days.
     * Returns null if there are fewer than two weigh-ins in the window.
     */
    fun weeklyRate(weights: List<WeightPoint>, until: LocalDate, days: Int = 14): Double? {
        val from = until.minusDays(days.toLong() - 1)
        if (weights.count { !it.date.isBefore(from) && !it.date.isAfter(until) } < 2) return null
        val pts = daily(weights, until).filter { !it.date.isBefore(from) }
        if (pts.size < 2) return null
        val xs = pts.map { ChronoUnit.DAYS.between(from, it.date).toDouble() }
        val ys = pts.map { it.trend }
        val mx = xs.average()
        val my = ys.average()
        val den = xs.sumOf { (it - mx).pow(2) }
        if (den == 0.0) return null
        val slopePerDay = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den
        return slopePerDay * 7.0
    }
}
