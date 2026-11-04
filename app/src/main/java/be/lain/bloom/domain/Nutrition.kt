package be.lain.bloom.domain

import kotlinx.serialization.Serializable
import java.time.LocalTime

/**
 * Nutrient amounts. `null` means unknown (not zero). Used both per 100 g/ml and per logged entry.
 */
@Serializable
data class Nutrients(
    val kcal: Double? = null,
    val protein: Double? = null,
    val carbs: Double? = null,
    val sugar: Double? = null,
    val fat: Double? = null,
    val satFat: Double? = null,
    val fiber: Double? = null,
    val salt: Double? = null,
) {
    fun scaled(factor: Double) = Nutrients(
        kcal?.times(factor), protein?.times(factor), carbs?.times(factor), sugar?.times(factor),
        fat?.times(factor), satFat?.times(factor), fiber?.times(factor), salt?.times(factor),
    )

    /** Sums known values; a nutrient stays unknown only if unknown on both sides. */
    operator fun plus(o: Nutrients) = Nutrients(
        add(kcal, o.kcal), add(protein, o.protein), add(carbs, o.carbs), add(sugar, o.sugar),
        add(fat, o.fat), add(satFat, o.satFat), add(fiber, o.fiber), add(salt, o.salt),
    )

    /** Amounts for [grams] of a food described per 100 g. */
    fun forGrams(grams: Double) = scaled(grams / 100.0)

    val isEmpty: Boolean get() = kcal == null && protein == null && carbs == null && fat == null

    companion object {
        val ZERO = Nutrients(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        private fun add(a: Double?, b: Double?): Double? = if (a == null && b == null) null else (a ?: 0.0) + (b ?: 0.0)

        fun sum(items: Iterable<Nutrients>): Nutrients = items.fold(Nutrients()) { acc, n -> acc + n }

        /** Energy from macros (Atwater) when a label lacks kcal. */
        fun kcalFromMacros(protein: Double?, carbs: Double?, fat: Double?): Double? =
            if (protein == null || carbs == null || fat == null) null else protein * 4 + carbs * 4 + fat * 9
    }
}

@Serializable
data class Serving(val label: String, val grams: Double)

enum class Meal(val label: String) {
    BREAKFAST("Breakfast"), LUNCH("Lunch"), DINNER("Dinner"), SNACKS("Snacks");

    companion object {
        /** A sensible default meal for the time of day. */
        fun forTime(t: LocalTime): Meal = when {
            t.isBefore(LocalTime.of(10, 30)) -> BREAKFAST
            t.isBefore(LocalTime.of(14, 30)) -> LUNCH
            t.isBefore(LocalTime.of(17, 0)) -> SNACKS
            t.isBefore(LocalTime.of(21, 30)) -> DINNER
            else -> SNACKS
        }
    }
}

enum class FoodSource(val label: String) {
    USER("My food"), OFF("Open Food Facts"), GENERIC("USDA"), AI("Found by AI"), RECIPE("Recipe"),
}

enum class EntrySource { MANUAL, SCAN, AI, QUICK, TEMPLATE, COPY }

/** How completely a day was logged; drives which days the weekly review trusts. */
enum class DayStatus(val label: String) { COMPLETE("Complete"), PARTIAL("Partly logged"), SKIPPED("Not logged") }

/** A food from any source, before or after it is saved locally. */
data class FoodCandidate(
    val localId: Long? = null,
    val source: FoodSource,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val externalId: String? = null,
    val per100: Nutrients,
    val servings: List<Serving> = emptyList(),
    val liquid: Boolean = false,
    val imageUrl: String? = null,
) {
    val unit: String get() = if (liquid) "ml" else "g"
}

object Recipes {
    data class Ingredient(val grams: Double, val per100: Nutrients)

    /** Per-100 g nutrients of the finished dish; [cookedWeightG] accounts for water lost or gained. */
    fun per100(ingredients: List<Ingredient>, cookedWeightG: Double?): Nutrients {
        val total = Nutrients.sum(ingredients.map { it.per100.forGrams(it.grams) })
        val weight = cookedWeightG?.takeIf { it > 0 } ?: ingredients.sumOf { it.grams }
        return if (weight <= 0) Nutrients() else total.scaled(100.0 / weight)
    }

    fun totalWeight(ingredients: List<Ingredient>, cookedWeightG: Double?): Double =
        cookedWeightG?.takeIf { it > 0 } ?: ingredients.sumOf { it.grams }
}
