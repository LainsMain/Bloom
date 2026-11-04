package be.lain.bloom.feature.onboarding

import be.lain.bloom.domain.Energy
import be.lain.bloom.domain.EnergyFormula
import be.lain.bloom.domain.Goal
import be.lain.bloom.domain.Lifestyle
import be.lain.bloom.domain.Profile
import java.time.DayOfWeek
import java.time.LocalDate

/** Editable form state for creating or editing a profile. */
data class ProfileDraft(
    val name: String = "",
    val goal: Goal = Goal.LOSE,
    val formula: EnergyFormula = EnergyFormula.FEMALE,
    val birthYear: Int = 2000,
    val heightCm: Double = 170.0,
    val weightKg: Double = 75.0,
    val goalWeightKg: Double = 65.0,
    val lifestyle: Lifestyle = Lifestyle.DESK,
    val paceKgPerWeek: Double = 0.5,
    val strengthDays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
    val runDays: Set<DayOfWeek> = setOf(DayOfWeek.TUESDAY, DayOfWeek.SATURDAY),
) {
    val hasPace: Boolean get() = goal == Goal.LOSE || goal == Goal.GAIN

    /** Goal weight actually used: maintain/recomp keep the current weight. */
    val effectiveGoalKg: Double get() = if (hasPace) goalWeightKg else weightKg

    fun toProfile(startDate: LocalDate, startWeightKg: Double = weightKg) = Profile(
        name = name.trim(),
        goal = goal,
        formula = formula,
        birthYear = birthYear,
        heightCm = heightCm,
        startWeightKg = startWeightKg,
        goalWeightKg = effectiveGoalKg,
        lifestyle = lifestyle,
        paceKgPerWeek = if (hasPace) paceKgPerWeek else 0.0,
        strengthDays = strengthDays,
        runDays = runDays,
        startDate = startDate,
    )

    fun goalBmi(): Double = Energy.bmi(effectiveGoalKg, heightCm)

    fun nameError(): String? = if (name.isBlank()) "Add your name" else null

    fun bodyError(today: LocalDate): String? = when {
        heightCm !in 120.0..230.0 -> "Height should be between 120 and 230 cm"
        birthYear !in 1930..(today.year - 14) -> "Birth year looks off"
        else -> null
    }

    fun weightError(): String? = when {
        weightKg !in 35.0..250.0 -> "Weight should be between 35 and 250 kg"
        goal == Goal.LOSE && goalWeightKg >= weightKg -> "Your goal should be below your current weight"
        goal == Goal.GAIN && goalWeightKg <= weightKg -> "Your goal should be above your current weight"
        hasPace && goalBmi() < 17.0 -> "That goal is below a healthy weight for your height"
        else -> null
    }

    /** Blocking problems; empty means the draft can be saved. */
    fun errors(today: LocalDate): List<String> = listOfNotNull(nameError(), bodyError(today), weightError())

    /** Non-blocking notes shown next to the goal. */
    fun goalNote(): String? = when {
        !hasPace -> null
        goalBmi() < 18.5 -> "A goal BMI under 18.5 is below the healthy range."
        else -> "Goal BMI ${"%.1f".format(goalBmi())} — within the healthy range."
    }

    companion object {
        fun from(p: Profile, currentKg: Double) = ProfileDraft(
            name = p.name, goal = p.goal, formula = p.formula, birthYear = p.birthYear, heightCm = p.heightCm,
            weightKg = currentKg, goalWeightKg = p.goalWeightKg, lifestyle = p.lifestyle,
            paceKgPerWeek = if (p.paceKgPerWeek > 0) p.paceKgPerWeek else 0.5,
            strengthDays = p.strengthDays, runDays = p.runDays,
        )

        val losePaces = listOf(
            0.25 to "Gentle",
            0.5 to "Steady",
            0.75 to "Faster",
            1.0 to "Aggressive",
        )
        val gainPaces = listOf(
            0.1 to "Lean",
            0.25 to "Steady",
            0.5 to "Faster",
        )
    }
}
