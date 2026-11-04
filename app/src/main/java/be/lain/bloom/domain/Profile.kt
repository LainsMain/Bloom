package be.lain.bloom.domain

import java.time.DayOfWeek
import java.time.LocalDate

enum class Goal(val label: String, val description: String) {
    LOSE("Lose weight", "Lose fat steadily while keeping muscle"),
    MAINTAIN("Maintain", "Keep your weight where it is"),
    GAIN("Gain weight", "Build up slowly with a small surplus"),
    RECOMP("Tone & reshape", "Stay about the same weight, build shape"),
}

/**
 * Which Mifflin-St Jeor variant estimates resting energy. BLEND averages the two,
 * e.g. for people on hormone therapy, where neither constant fits well.
 */
enum class EnergyFormula(val label: String, val sexConstant: Double) {
    FEMALE("Female", -161.0),
    MALE("Male", 5.0),
    BLEND("In between", -78.0),
}

/** Day-to-day movement outside of planned training. */
enum class Lifestyle(val label: String, val description: String, val factor: Double) {
    DESK("Mostly sitting", "Desk job, studying, gaming", 1.2),
    LIGHT("Some walking", "On your feet now and then", 1.3),
    ON_FEET("On your feet", "Retail, teaching, lots of walking", 1.45),
    PHYSICAL("Physical work", "Manual labour, very active days", 1.6),
}

data class Profile(
    val name: String,
    val goal: Goal,
    val formula: EnergyFormula,
    val birthYear: Int,
    val heightCm: Double,
    val startWeightKg: Double,
    val goalWeightKg: Double,
    val lifestyle: Lifestyle,
    /** Positive kg per week; direction comes from [goal]. */
    val paceKgPerWeek: Double,
    val strengthDays: Set<DayOfWeek>,
    val runDays: Set<DayOfWeek>,
    val startDate: LocalDate,
    /** The user's own notes for the coach (e.g. "trans woman, 1 year on HRT", an injury, night shifts). */
    val notes: String = "",
) {
    fun ageOn(date: LocalDate): Int = (date.year - birthYear).coerceIn(14, 100)
    val plannedSessionsPerWeek: Int get() = strengthDays.size + runDays.size
}

enum class PlanSource { ONBOARDING, MANUAL, WEEKLY_REVIEW }

data class Targets(
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    val fiberG: Int,
)

data class PlanVersion(
    val id: Long = 0,
    val effectiveDate: LocalDate,
    val targets: Targets,
    val expenditureKcal: Int,
    val source: PlanSource,
    val reason: String,
)
