package be.lain.bloom.domain

import java.util.Locale
import kotlin.math.floor

data class Milestone(
    /** Stable id, so each milestone is celebrated only once. */
    val id: String,
    /** Short hero text, e.g. "−5 kg". */
    val hero: String,
    val title: String,
    val message: String,
    /** Big ones get the full celebration. */
    val big: Boolean,
    /** Ordering when several are reached at once (higher wins the celebration). */
    val rank: Int,
)

/**
 * Weight-loss milestones from the *trend* weight (not a single weigh-in), so a water dip
 * never triggers a celebration that the next morning takes away.
 */
object Milestones {
    private fun kg(v: Double) = String.format(Locale.UK, "%.1f", v)

    fun reached(profile: Profile, trendKg: Double): List<Milestone> {
        if (profile.goal != Goal.LOSE) return emptyList()
        val start = profile.startWeightKg
        val goal = profile.goalWeightKg
        val lost = start - trendKg
        if (lost <= 0) return emptyList()
        val out = mutableListOf<Milestone>()

        val wholeKg = floor(lost + 1e-9).toInt()
        for (k in 1..wholeKg) {
            val big = k % 5 == 0
            out += Milestone(
                id = "kg-$k", hero = "−$k kg",
                title = when { k == 1 -> "Your first kilo!"; big -> "$k kilos down!"; else -> "$k kg down" },
                message = when {
                    k == 1 -> "The first one is the hardest. Your trend went from ${kg(start)} to below ${kg(start - 1)} kg."
                    big -> "$k kilos of steady work. Your trend is now under ${kg(start - k)} kg."
                    else -> "Another kilo off your trend weight. Keep doing what you're doing."
                },
                big = big, rank = if (big) 200 + k else 100 + k,
            )
        }
        for (pct in listOf(5, 10, 15, 20, 25, 30)) {
            if (lost >= start * pct / 100.0) out += Milestone(
                id = "pct-$pct", hero = "−$pct%",
                title = "$pct% of your starting weight",
                message = if (pct == 5) "Losing 5% is a real milestone for energy and health, and you've done it." else "You've lost $pct% of where you started. That's huge.",
                big = true, rank = 300 + pct,
            )
        }
        if (goal < start && trendKg <= start - (start - goal) / 2) out += Milestone(
            id = "halfway", hero = "½", title = "Halfway there!",
            message = "Half of the way from ${kg(start)} to ${kg(goal)} kg is behind you.", big = true, rank = 400,
        )
        val bmi = Energy.bmi(trendKg, profile.heightCm)
        val startBmi = Energy.bmi(start, profile.heightCm)
        for ((limit, text) in listOf(30.0 to "Your BMI is now under 30.", 25.0 to "Your BMI is now in the healthy range.")) {
            if (startBmi >= limit && bmi < limit) out += Milestone(
                id = "bmi-${limit.toInt()}", hero = "BMI ${String.format(Locale.UK, "%.1f", bmi)}", title = "A new BMI range",
                message = text, big = true, rank = 350 + (40 - limit.toInt()),
            )
        }
        if (trendKg <= goal) out += Milestone(
            id = "goal", hero = "${kg(goal)} kg", title = "You reached your goal!",
            message = "From ${kg(start)} to ${kg(goal)} kg. You did this, one day at a time.", big = true, rank = 1000,
        )
        return out
    }

    /** The next milestone ahead and how far it is, for the Progress screen. */
    fun next(profile: Profile, trendKg: Double): Pair<String, Double>? {
        if (profile.goal != Goal.LOSE || trendKg <= profile.goalWeightKg) return null
        val lost = (profile.startWeightKg - trendKg).coerceAtLeast(0.0)
        val nextKg = floor(lost + 1e-9) + 1
        val target = profile.startWeightKg - nextKg
        return "−${nextKg.toInt()} kg" to (trendKg - target)
    }

    /** Of the newly reached milestones, the one to celebrate (the rest are marked done quietly). */
    fun pick(newlyReached: List<Milestone>): Milestone? = newlyReached.maxByOrNull { it.rank }
}
