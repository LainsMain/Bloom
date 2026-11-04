package be.lain.bloom.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import be.lain.bloom.domain.DayStatus
import be.lain.bloom.domain.EntrySource
import be.lain.bloom.domain.FoodCandidate
import be.lain.bloom.domain.FoodSource
import be.lain.bloom.domain.Meal
import be.lain.bloom.domain.Nutrients
import be.lain.bloom.domain.Serving
import java.time.Instant
import java.time.LocalDate

@Entity(
    tableName = "food",
    indices = [Index(value = ["barcode"], unique = true), Index("name"), Index("externalId"), Index("lastUsedAt")],
)
data class FoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val source: FoodSource,
    /** USDA fdc id or similar, to avoid saving the same generic food twice. */
    val externalId: String? = null,
    val liquid: Boolean = false,
    @Embedded val per100: Nutrients,
    val servings: List<Serving> = emptyList(),
    val recipeId: Long? = null,
    val favorite: Boolean = false,
    val useCount: Int = 0,
    val lastUsedAt: Instant? = null,
    val hidden: Boolean = false,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun toCandidate() = FoodCandidate(
        localId = id, source = source, name = name, brand = brand, barcode = barcode, externalId = externalId,
        per100 = per100, servings = servings, liquid = liquid,
    )
}

@Entity(tableName = "diary_entry", indices = [Index("date"), Index("foodId"), Index("aiActionId")])
data class DiaryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val meal: Meal,
    val at: Instant,
    val foodId: Long? = null,
    val name: String,
    val brand: String? = null,
    /** Weight in g (or ml) used for the nutrient snapshot; 0 for quick-add calories. */
    val grams: Double,
    /** What the user entered, e.g. 2 (servings) or 150 (g). */
    val amount: Double,
    /** "g", "ml", "kcal" or a serving label. */
    val unit: String,
    /** Nutrients for this entry (snapshot, so later food edits don't rewrite history). */
    @Embedded val nutrients: Nutrients,
    val source: EntrySource = EntrySource.MANUAL,
    val aiActionId: Long? = null,
)

@Entity(tableName = "recipe")
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val servings: Int = 1,
    val cookedWeightG: Double? = null,
    val foodId: Long? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Entity(tableName = "recipe_ingredient", indices = [Index("recipeId")])
data class RecipeIngredientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recipeId: Long,
    val foodId: Long? = null,
    val name: String,
    val grams: Double,
    @Embedded(prefix = "per100_") val per100: Nutrients,
)

@Entity(tableName = "meal_template")
data class MealTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Instant,
)

@Entity(tableName = "meal_template_item", indices = [Index("templateId")])
data class MealTemplateItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val templateId: Long,
    val foodId: Long? = null,
    val name: String,
    val brand: String? = null,
    val grams: Double,
    val amount: Double,
    val unit: String,
    @Embedded val nutrients: Nutrients,
)

@Entity(tableName = "day_log")
data class DayLogEntity(
    @PrimaryKey val date: LocalDate,
    val status: DayStatus,
    val updatedAt: Instant,
)
