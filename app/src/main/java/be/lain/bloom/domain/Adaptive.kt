package be.lain.bloom.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The weekly review's deterministic engine. It estimates real daily expenditure from what was
 * eaten on well-logged days and how the weight trend moved, blends that with the previous estimate
 * (so one noisy week can't swing the plan), and turns it into a new target that changes by at
 * most [MAX_WEEKLY_CHANGE] kcal and never goes below the safety floor.
 *
 * No Android dependencies; the AI only explains these numbers, it never computes them.
 */
object Adaptive {
    /** Largest change to the daily target one review may make. */
    const val MAX_WEEKLY_CHANGE = 150

    /** Window for the weight-rate regression; two weeks smooths out water swings. */
    const val RATE_WINDOW_DAYS = 14

    /** Fewer complete days than this and the intake average isn't trusted. */
    const val MIN_COMPLETE_DAYS = 3

    /** Fewer weigh-ins in the rate window than this and the weight rate isn't trusted. */
    const val MIN_WEIGH_INS = 3

    /** At most this share of the gap between the old estimate and the measurement is closed per week. */
    private const val MAX_BLEND = 0.5

    data class Day(val date: LocalDate, val kcal: Double, val status: DayStatus)

    data class Input(
        val profile: Profile,
        val weights: List<WeightPoint>,
        /** The reviewed days (usually the 7 days before [reviewDate]). */
        val days: List<Day>,
        val currentTargetKcal: Int,
        /** Expenditure behind the current plan; 0 or less means unknown. */
        val priorExpenditure: Int,
        val reviewDate: LocalDate,
    )

    data class Result(
        val completeDays: Int,
        val partialDays: Int,
        /** Average intake on complete days only. */
        val avgIntake: Double?,
        val trendKg: Double?,
        /** Least-squares trend slope over [RATE_WINDOW_DAYS] (negative = losing). */
        val weeklyRateKg: Double?,
        val weighIns: Int,
        /** Intake minus stored energy; null when the data isn't good enough. */
        val measuredExpenditure: Int?,
        /** Share of the measurement that was trusted (0..[MAX_BLEND]). */
        val confidence: Double,
        val priorExpenditure: Int,
        val newExpenditure: Int,
        val currentKcal: Int,
        val proposedKcal: Int,
        /** What the target would be without the weekly change limit. */
        val uncappedKcal: Int,
        val targets: Targets,
        val floor: Int,
        val limitedByFloor: Boolean,
        val capped: Boolean,
        val notes: List<String>,
    ) {
        val change: Int get() = proposedKcal - currentKcal
    }

    fun review(input: Input): Result {
        val p = input.profile
        val date = input.reviewDate
        val notes = mutableListOf<String>()

        val complete = input.days.filter { it.status == DayStatus.COMPLETE }
        val partial = input.days.count { it.status == DayStatus.PARTIAL }
        val avgIntake = complete.takeIf { it.isNotEmpty() }?.map { it.kcal }?.average()

        val windowStart = date.minusDays(RATE_WINDOW_DAYS.toLong() - 1)
        val weighIns = input.weights.filter { !it.date.isBefore(windowStart) && !it.date.isAfter(date) }.map { it.date }.distinct().size
        val trend = Trend.at(input.weights, date)
        val rate = Trend.weeklyRate(input.weights, date, RATE_WINDOW_DAYS)
        val currentKg = trend ?: p.startWeightKg

        val bmr = Energy.bmr(p.formula, currentKg, p.heightCm, p.ageOn(date))
        val formulaEstimate = Energy.estimatedExpenditure(p, currentKg, date)
        val prior = if (input.priorExpenditure > 0) input.priorExpenditure.toDouble() else formulaEstimate

        var measured: Double? = null
        var confidence = 0.0
        when {
            complete.size < MIN_COMPLETE_DAYS ->
                notes += "Only ${complete.size} fully logged day${if (complete.size == 1) "" else "s"}, so your intake this week wasn't used. Your burn estimate stays the same."
            weighIns < MIN_WEIGH_INS || rate == null ->
                notes += "Only $weighIns weigh-in${if (weighIns == 1) "" else "s"} in the last two weeks, so the weight trend wasn't used. Weigh in 3 or more times a week for a better estimate."
            else -> {
                val raw = avgIntake!! - rate * Energy.KCAL_PER_KG / 7.0
                // Outside these bounds the logs or the scale are almost certainly off.
                val lo = bmr * 1.0
                val hi = bmr * 2.4
                if (raw < lo || raw > hi) {
                    notes += "The numbers didn't add up this week (an estimated burn of ${raw.roundToInt()} kcal is unlikely), so they weren't used. Missed entries or a water swing are the usual cause."
                } else {
                    measured = raw
                    val logging = min(1.0, complete.size / 5.0)
                    val scale = min(1.0, weighIns / 6.0)
                    // Early weeks of a diet lose water and glycogen, which overstates the burn.
                    val weeksIn = ChronoUnit.DAYS.between(p.startDate, date) / 7.0
                    val settling = if (weeksIn < 3) 0.5 else 1.0
                    confidence = MAX_BLEND * logging * scale * settling
                    if (settling < 1.0) notes += "Your first weeks include water weight, so this week's numbers count for less."
                }
            }
        }
        val newExpenditure = prior + confidence * ((measured ?: prior) - prior)

        val target = Energy.targets(p, currentKg, date, expenditureOverride = newExpenditure)
        val uncapped = target.targets.kcal
        val lo = input.currentTargetKcal - MAX_WEEKLY_CHANGE
        val hi = input.currentTargetKcal + MAX_WEEKLY_CHANGE
        var kcal = uncapped.coerceIn(lo, hi)
        val capped = kcal != uncapped
        val limitedByFloor = kcal < target.floor
        if (limitedByFloor) kcal = ((target.floor + 9) / 10) * 10
        if (capped && !limitedByFloor) {
            notes += "The full adjustment would be ${signed(uncapped - input.currentTargetKcal)} kcal. Changes are limited to $MAX_WEEKLY_CHANGE kcal a week, so the rest follows next week if the trend holds."
        }
        if (limitedByFloor || target.limitedByFloor) {
            notes += "Your target is held at the safe minimum of ${target.floor} kcal. If progress is slower than planned, more steps or a slower pace are better than eating less."
        }
        rate?.let { r -> paceNote(p, r, currentKg)?.let(notes::add) }

        return Result(
            completeDays = complete.size,
            partialDays = partial,
            avgIntake = avgIntake,
            trendKg = trend,
            weeklyRateKg = rate,
            weighIns = weighIns,
            measuredExpenditure = measured?.roundToInt(),
            confidence = confidence,
            priorExpenditure = prior.roundToInt(),
            newExpenditure = Energy.roundTo(newExpenditure, 10),
            currentKcal = input.currentTargetKcal,
            proposedKcal = kcal,
            uncappedKcal = uncapped,
            targets = Energy.macros(p, currentKg, kcal),
            floor = target.floor,
            limitedByFloor = limitedByFloor || target.limitedByFloor,
            capped = capped,
            notes = notes,
        )
    }

    /** Flags losing much faster than planned (muscle and wellbeing suffer) or a stall. */
    private fun paceNote(p: Profile, rateKgPerWeek: Double, currentKg: Double): String? {
        if (p.goal != Goal.LOSE) return null
        val loss = -rateKgPerWeek
        val maxSafe = max(p.paceKgPerWeek * 1.5, currentKg * 0.01)
        return when {
            loss > maxSafe -> "You're losing faster than planned (${"%.2f".format(java.util.Locale.US, loss)} kg/week). That's fine for a week, but if it continues, eating a bit more protects muscle and energy."
            loss < 0.05 -> "Your trend hasn't moved down over the last two weeks. Short plateaus are normal; keep logging and the estimate catches up."
            else -> null
        }
    }

    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    /**
     * Pre-fills the "how well was this day logged?" question. A day that reaches most of the
     * target across at least two meals is probably complete; anything logged at all is partial.
     */
    fun guessStatus(kcal: Double, entries: Int, meals: Int, targetKcal: Int?): DayStatus = when {
        entries == 0 -> DayStatus.SKIPPED
        targetKcal != null && kcal >= targetKcal * 0.6 && meals >= 2 -> DayStatus.COMPLETE
        targetKcal == null && meals >= 3 -> DayStatus.COMPLETE
        else -> DayStatus.PARTIAL
    }

    /** The most recent [reviewDay] on or before [today]. */
    fun lastReviewDay(today: LocalDate, reviewDay: java.time.DayOfWeek): LocalDate {
        val back = (today.dayOfWeek.value - reviewDay.value + 7) % 7
        return today.minusDays(back.toLong())
    }

    /**
     * A review is due once a full week of data exists and none was done since the latest
     * review day. Missing the day keeps it due until the next one.
     */
    fun isDue(today: LocalDate, reviewDay: java.time.DayOfWeek, startDate: LocalDate, lastReview: LocalDate?): Boolean {
        val due = lastReviewDay(today, reviewDay)
        if (ChronoUnit.DAYS.between(startDate, due) < 7) return false
        return lastReview == null || lastReview.isBefore(due)
    }
}
