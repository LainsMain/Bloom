package be.lain.bloom.domain

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Deterministic energy and macro target math. No Android dependencies. */
object Energy {
    /** Approximate energy content of 1 kg of body-weight change. */
    const val KCAL_PER_KG = 7700.0

    /** Each planned weekly training session adds this much to the activity factor. */
    private const val SESSION_FACTOR = 0.025
    private const val MAX_TRAINING_FACTOR = 0.15

    /** Below this many kg from the goal, the pace eases off linearly. */
    private const val EASE_ZONE_KG = 3.0
    private const val MIN_EASED_PACE = 0.2

    fun bmr(formula: EnergyFormula, weightKg: Double, heightCm: Double, age: Int): Double =
        10.0 * weightKg + 6.25 * heightCm - 5.0 * age + formula.sexConstant

    fun activityFactor(lifestyle: Lifestyle, sessionsPerWeek: Int): Double =
        lifestyle.factor + min(sessionsPerWeek * SESSION_FACTOR, MAX_TRAINING_FACTOR)

    fun estimatedExpenditure(profile: Profile, weightKg: Double, date: LocalDate): Double =
        bmr(profile.formula, weightKg, profile.heightCm, profile.ageOn(date)) *
            activityFactor(profile.lifestyle, profile.plannedSessionsPerWeek)

    /** Absolute minimum intake we will ever suggest for the given formula. */
    fun safetyFloor(formula: EnergyFormula, bmr: Double): Int {
        val absolute = when (formula) {
            EnergyFormula.FEMALE -> 1200
            EnergyFormula.BLEND -> 1350
            EnergyFormula.MALE -> 1500
        }
        return max(absolute, (bmr * 0.9).roundToInt())
    }

    /** Pace actually used today: eases off as the goal approaches. */
    fun effectivePace(goal: Goal, pace: Double, currentKg: Double, goalKg: Double): Double {
        if (goal != Goal.LOSE && goal != Goal.GAIN) return 0.0
        val remaining = if (goal == Goal.LOSE) currentKg - goalKg else goalKg - currentKg
        if (remaining <= 0.0) return 0.0
        if (remaining >= EASE_ZONE_KG) return pace
        val eased = pace * (remaining / EASE_ZONE_KG)
        return max(min(MIN_EASED_PACE, pace), eased)
    }

    data class Result(
        val targets: Targets,
        val expenditure: Int,
        val bmr: Int,
        val floor: Int,
        /** True when the safety floor raised the calorie target. */
        val limitedByFloor: Boolean,
        /** Weekly change implied by the final target (negative = loss). */
        val impliedKgPerWeek: Double,
    )

    /**
     * Daily targets for [profile] at [currentKg]. If [expenditureOverride] is given
     * (e.g. measured by the weekly review) it replaces the formula estimate.
     */
    fun targets(
        profile: Profile,
        currentKg: Double,
        date: LocalDate,
        expenditureOverride: Double? = null,
    ): Result {
        val bmr = bmr(profile.formula, currentKg, profile.heightCm, profile.ageOn(date))
        val tdee = expenditureOverride ?: (bmr * activityFactor(profile.lifestyle, profile.plannedSessionsPerWeek))
        val floor = safetyFloor(profile.formula, bmr)
        val pace = effectivePace(profile.goal, profile.paceKgPerWeek, currentKg, profile.goalWeightKg)
        val dailyDelta = pace * KCAL_PER_KG / 7.0
        val raw = when (profile.goal) {
            Goal.LOSE -> tdee - dailyDelta
            Goal.GAIN -> tdee + dailyDelta
            Goal.MAINTAIN -> tdee
            Goal.RECOMP -> tdee * 0.92
        }
        // Never round below the floor.
        val kcal = if (raw < floor) ((floor + 9) / 10) * 10 else roundTo(raw, 10)
        val macros = macros(profile, currentKg, kcal)
        return Result(
            targets = macros,
            expenditure = roundTo(tdee, 10),
            bmr = bmr.roundToInt(),
            floor = floor,
            limitedByFloor = raw < floor,
            impliedKgPerWeek = (kcal - tdee) * 7.0 / KCAL_PER_KG,
        )
    }

    /**
     * Protein scales with the reference weight (goal weight while losing, otherwise current),
     * fat is ~27% of energy with a minimum, carbs fill the rest. Fibre is 14 g per 1000 kcal (≥ 25 g).
     */
    fun macros(profile: Profile, currentKg: Double, kcal: Int): Targets {
        val reference = when (profile.goal) {
            Goal.LOSE -> min(currentKg, max(profile.goalWeightKg, currentKg * 0.75))
            else -> currentKg
        }
        val proteinPerKg = when (profile.goal) {
            Goal.LOSE, Goal.RECOMP -> 1.9
            Goal.GAIN -> 1.8
            Goal.MAINTAIN -> 1.6
        }
        val protein = roundTo(reference * proteinPerKg, 5)
        val fatMin = reference * 0.7
        val fat = roundTo(max(kcal * 0.27 / 9.0, fatMin), 1)
        val carbs = max(0, ((kcal - protein * 4 - fat * 9) / 4.0).roundToInt())
        val fiber = max(25, roundTo(kcal / 1000.0 * 14.0, 1))
        return Targets(kcal = kcal, proteinG = protein, carbsG = carbs, fatG = fat, fiberG = fiber)
    }

    fun bmi(weightKg: Double, heightCm: Double): Double {
        val m = heightCm / 100.0
        return weightKg / (m * m)
    }

    /** Weight at which BMI equals [bmi] for the given height. */
    fun weightForBmi(bmi: Double, heightCm: Double): Double {
        val m = heightCm / 100.0
        return bmi * m * m
    }

    /**
     * Week-by-week projected weight, including the slow-down near the goal.
     * Stops when the goal is reached or after [maxWeeks].
     */
    fun projection(
        profile: Profile,
        fromKg: Double,
        from: LocalDate = profile.startDate,
        maxWeeks: Int = 156,
    ): List<Double> {
        val out = mutableListOf(fromKg)
        if (profile.goal != Goal.LOSE && profile.goal != Goal.GAIN) return out
        var w = fromKg
        for (week in 0 until maxWeeks) {
            // Use the real target (with easing and the safety floor), not the nominal pace.
            val implied = targets(profile, w, from.plusWeeks(week.toLong())).impliedKgPerWeek
            val step = if (profile.goal == Goal.LOSE) -implied else implied
            if (step < 0.01) return out
            w = if (profile.goal == Goal.LOSE) max(profile.goalWeightKg, w - step) else min(profile.goalWeightKg, w + step)
            out += w
            if (abs(w - profile.goalWeightKg) < 0.05) return out
        }
        return out
    }

    fun roundTo(value: Double, step: Int): Int = ((value / step).roundToInt() * step)
}
