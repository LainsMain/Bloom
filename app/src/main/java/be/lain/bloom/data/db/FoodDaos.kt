package be.lain.bloom.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import be.lain.bloom.domain.Meal
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

@Dao
interface FoodDao {
    @Insert suspend fun insert(food: FoodEntity): Long
    @Update suspend fun update(food: FoodEntity)

    @Query("SELECT * FROM food WHERE id = :id") suspend fun get(id: Long): FoodEntity?
    @Query("SELECT * FROM food WHERE id = :id") fun observe(id: Long): Flow<FoodEntity?>
    @Query("SELECT * FROM food WHERE barcode = :code LIMIT 1") suspend fun byBarcode(code: String): FoodEntity?
    @Query("SELECT * FROM food WHERE externalId = :ext AND source = :source LIMIT 1")
    suspend fun byExternal(ext: String, source: String): FoodEntity?
    @Query("SELECT * FROM food WHERE recipeId = :recipeId LIMIT 1") suspend fun byRecipe(recipeId: Long): FoodEntity?

    @Query(
        """SELECT * FROM food WHERE hidden = 0 AND (name LIKE '%' || :q || '%' OR brand LIKE '%' || :q || '%' OR barcode = :q)
           ORDER BY favorite DESC, useCount DESC, length(name) LIMIT :limit""",
    )
    suspend fun search(q: String, limit: Int = 30): List<FoodEntity>

    @Query("SELECT * FROM food WHERE hidden = 0 AND lastUsedAt IS NOT NULL ORDER BY lastUsedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<FoodEntity>>

    @Query("SELECT * FROM food WHERE hidden = 0 AND favorite = 1 ORDER BY name")
    fun observeFavorites(): Flow<List<FoodEntity>>

    @Query("SELECT * FROM food WHERE hidden = 0 AND source IN ('USER','AI') ORDER BY name")
    fun observeMine(): Flow<List<FoodEntity>>

    @Query("UPDATE food SET useCount = useCount + 1, lastUsedAt = :at WHERE id = :id")
    suspend fun markUsed(id: Long, at: Instant)

    @Query("UPDATE food SET favorite = :fav WHERE id = :id") suspend fun setFavorite(id: Long, fav: Boolean)
}

@Dao
interface DiaryDao {
    @Insert suspend fun insert(e: DiaryEntryEntity): Long
    @Insert suspend fun insertAll(e: List<DiaryEntryEntity>): List<Long>
    @Update suspend fun update(e: DiaryEntryEntity)
    @Query("DELETE FROM diary_entry WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM diary_entry WHERE id IN (:ids)") suspend fun deleteAll(ids: List<Long>)
    @Query("SELECT * FROM diary_entry WHERE id = :id") suspend fun get(id: Long): DiaryEntryEntity?
    @Query("SELECT * FROM diary_entry WHERE id IN (:ids)") suspend fun getAll(ids: List<Long>): List<DiaryEntryEntity>

    @Query("SELECT * FROM diary_entry WHERE date = :date ORDER BY at")
    fun observeDay(date: LocalDate): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entry WHERE date = :date ORDER BY at")
    suspend fun day(date: LocalDate): List<DiaryEntryEntity>

    @Query("SELECT * FROM diary_entry WHERE date BETWEEN :from AND :to ORDER BY date, at")
    fun observeRange(from: LocalDate, to: LocalDate): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entry WHERE date BETWEEN :from AND :to ORDER BY date, at")
    suspend fun range(from: LocalDate, to: LocalDate): List<DiaryEntryEntity>

    @Query("SELECT * FROM diary_entry WHERE date = :date AND meal = :meal ORDER BY at")
    suspend fun meal(date: LocalDate, meal: Meal): List<DiaryEntryEntity>

    @Query("SELECT * FROM diary_entry WHERE foodId = :foodId ORDER BY at DESC LIMIT 1")
    suspend fun lastFor(foodId: Long): DiaryEntryEntity?

    @Query("SELECT DISTINCT date FROM diary_entry WHERE date BETWEEN :from AND :to")
    fun observeLoggedDays(from: LocalDate, to: LocalDate): Flow<List<LocalDate>>

    @Query("SELECT * FROM diary_entry WHERE name LIKE '%' || :q || '%' ORDER BY date DESC LIMIT :limit")
    suspend fun searchHistory(q: String, limit: Int = 40): List<DiaryEntryEntity>
}

@Dao
interface RecipeDao {
    @Insert suspend fun insert(r: RecipeEntity): Long
    @Update suspend fun update(r: RecipeEntity)
    @Query("DELETE FROM recipe WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM recipe WHERE id = :id") suspend fun get(id: Long): RecipeEntity?
    @Query("SELECT * FROM recipe ORDER BY updatedAt DESC") fun observeAll(): Flow<List<RecipeEntity>>
    @Query("SELECT * FROM recipe_ingredient WHERE recipeId = :id ORDER BY id") suspend fun ingredients(id: Long): List<RecipeIngredientEntity>
    @Insert suspend fun insertIngredients(items: List<RecipeIngredientEntity>)
    @Query("DELETE FROM recipe_ingredient WHERE recipeId = :id") suspend fun clearIngredients(id: Long)
}

@Dao
interface TemplateDao {
    @Insert suspend fun insert(t: MealTemplateEntity): Long
    @Insert suspend fun insertItems(items: List<MealTemplateItemEntity>)
    @Query("DELETE FROM meal_template WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM meal_template_item WHERE templateId = :id") suspend fun deleteItems(id: Long)
    @Query("SELECT * FROM meal_template ORDER BY name") fun observeAll(): Flow<List<MealTemplateEntity>>
    @Query("SELECT * FROM meal_template_item ORDER BY id") fun observeItems(): Flow<List<MealTemplateItemEntity>>
    @Query("SELECT * FROM meal_template_item WHERE templateId = :id ORDER BY id") suspend fun items(id: Long): List<MealTemplateItemEntity>
}

@Dao
interface DayLogDao {
    @Upsert suspend fun upsert(d: DayLogEntity)
    @Query("DELETE FROM day_log WHERE date = :date") suspend fun clear(date: LocalDate)
    @Query("SELECT * FROM day_log WHERE date = :date") fun observe(date: LocalDate): Flow<DayLogEntity?>
    @Query("SELECT * FROM day_log WHERE date BETWEEN :from AND :to") fun observeRange(from: LocalDate, to: LocalDate): Flow<List<DayLogEntity>>
    @Query("SELECT * FROM day_log WHERE date BETWEEN :from AND :to") suspend fun range(from: LocalDate, to: LocalDate): List<DayLogEntity>
}
