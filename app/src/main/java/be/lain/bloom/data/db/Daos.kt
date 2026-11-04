package be.lain.bloom.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile WHERE id = 1")
    fun observe(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profile WHERE id = 1")
    suspend fun get(): ProfileEntity?

    @Upsert
    suspend fun upsert(profile: ProfileEntity)
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM plan_version ORDER BY effectiveDate DESC, id DESC")
    fun observeAll(): Flow<List<PlanVersionEntity>>

    @Query("SELECT * FROM plan_version WHERE effectiveDate <= :date ORDER BY effectiveDate DESC, id DESC LIMIT 1")
    suspend fun activeOn(date: LocalDate): PlanVersionEntity?

    @Insert
    suspend fun insert(plan: PlanVersionEntity): Long
}

@Dao
interface BodyDao {
    @Query("SELECT * FROM weight_entry ORDER BY date ASC, at ASC")
    fun observeWeights(): Flow<List<WeightEntryEntity>>

    @Query("SELECT * FROM weight_entry ORDER BY date ASC, at ASC")
    suspend fun weights(): List<WeightEntryEntity>

    @Insert
    suspend fun insertWeight(entry: WeightEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWeightsIgnoring(entries: List<WeightEntryEntity>)

    @Query("DELETE FROM weight_entry WHERE id = :id")
    suspend fun deleteWeight(id: Long)

    @Query("SELECT * FROM water_entry WHERE date = :date ORDER BY at ASC")
    fun observeWater(date: LocalDate): Flow<List<WaterEntryEntity>>

    @Insert
    suspend fun insertWater(entry: WaterEntryEntity): Long

    @Query("DELETE FROM water_entry WHERE id = (SELECT id FROM water_entry WHERE date = :date ORDER BY at DESC LIMIT 1)")
    suspend fun deleteLastWater(date: LocalDate)

    @Query("SELECT * FROM measurement ORDER BY date ASC")
    fun observeMeasurements(): Flow<List<MeasurementEntity>>

    @Insert
    suspend fun insertMeasurement(m: MeasurementEntity): Long

    @Query("DELETE FROM measurement WHERE id = :id")
    suspend fun deleteMeasurement(id: Long)
}
