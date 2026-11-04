package be.lain.bloom.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Additive schema changes use Room auto-migrations (schemas are exported to app/schemas).
 * Never fall back to destructive migration: this holds the user's only copy of their data.
 */
@Database(
    version = 10,
    exportSchema = true,
    entities = [
        ProfileEntity::class,
        PlanVersionEntity::class,
        WeightEntryEntity::class,
        WaterEntryEntity::class,
        MeasurementEntity::class,
        FoodEntity::class,
        DiaryEntryEntity::class,
        RecipeEntity::class,
        RecipeIngredientEntity::class,
        MealTemplateEntity::class,
        MealTemplateItemEntity::class,
        DayLogEntity::class,
        ChatEntity::class,
        ChatMessageEntity::class,
        AiActionEntity::class,
        WorkoutSessionEntity::class,
        SetLogEntity::class,
        SlotChoiceEntity::class,
        VideoOverrideEntity::class,
        RunSessionEntity::class,
        RunPointEntity::class,
        RunPlanEntity::class,
        be.lain.bloom.data.health.HcDailyEntity::class,
        be.lain.bloom.data.health.HcSessionEntity::class,
        WeeklyReviewEntity::class,
        ProgressPhotoEntity::class,
        CycleDayEntity::class,
    ],
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10),
    ],
)
@TypeConverters(Converters::class)
abstract class BloomDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun planDao(): PlanDao
    abstract fun bodyDao(): BodyDao
    abstract fun foodDao(): FoodDao
    abstract fun diaryDao(): DiaryDao
    abstract fun recipeDao(): RecipeDao
    abstract fun templateDao(): TemplateDao
    abstract fun dayLogDao(): DayLogDao
    abstract fun chatDao(): ChatDao
    abstract fun trainingDao(): TrainingDao
    abstract fun runDao(): RunDao
    abstract fun healthDao(): be.lain.bloom.data.health.HealthDao
    abstract fun reviewDao(): ReviewDao
    abstract fun cycleDao(): CycleDao
}
