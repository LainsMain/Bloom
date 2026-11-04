package be.lain.bloom.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/** One completed weekly review: what the week looked like, the check-in, and what changed. */
@Entity(tableName = "weekly_review", indices = [Index("reviewDate")])
data class WeeklyReviewEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The day the review was done; the reviewed week is the 7 days before it. */
    val reviewDate: LocalDate,
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val createdAt: Instant,
    val completeDays: Int,
    val partialDays: Int,
    /** Average on complete days. */
    val avgIntakeKcal: Int? = null,
    val trendKg: Double? = null,
    val weeklyRateKg: Double? = null,
    val weighIns: Int = 0,
    val workoutsDone: Int = 0,
    val workoutsPlanned: Int = 0,
    val runsDone: Int = 0,
    val runsPlanned: Int = 0,
    val runKm: Double = 0.0,
    val avgSteps: Int? = null,
    /** Check-in, 1–5 (null = skipped). */
    val energy: Int? = null,
    val hunger: Int? = null,
    val sleep: Int? = null,
    val soreness: Int? = null,
    val notes: String = "",
    val measuredExpenditure: Int? = null,
    val newExpenditure: Int,
    val oldKcal: Int,
    val proposedKcal: Int,
    /** The new target was accepted (a plan version was created). */
    val applied: Boolean,
    /** The AI write-up conversation, if one was made. */
    val chatId: Long? = null,
)

@Entity(tableName = "progress_photo", indices = [Index("date")])
data class ProgressPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    /** File in app-private storage. */
    val path: String,
    val createdAt: Instant,
)

@Dao
interface ReviewDao {
    @Insert suspend fun insert(r: WeeklyReviewEntity): Long
    @Update suspend fun update(r: WeeklyReviewEntity)
    @Query("SELECT * FROM weekly_review WHERE id = :id") suspend fun get(id: Long): WeeklyReviewEntity?
    @Query("SELECT * FROM weekly_review ORDER BY reviewDate DESC, id DESC") fun observeAll(): Flow<List<WeeklyReviewEntity>>
    @Query("SELECT * FROM weekly_review ORDER BY reviewDate DESC, id DESC LIMIT 1") fun observeLatest(): Flow<WeeklyReviewEntity?>
    @Query("SELECT * FROM weekly_review ORDER BY reviewDate DESC, id DESC LIMIT 1") suspend fun latest(): WeeklyReviewEntity?

    @Insert suspend fun insertPhoto(p: ProgressPhotoEntity): Long
    @Query("DELETE FROM progress_photo WHERE id = :id") suspend fun deletePhoto(id: Long)
    @Query("SELECT * FROM progress_photo WHERE id = :id") suspend fun photo(id: Long): ProgressPhotoEntity?
    @Query("SELECT * FROM progress_photo ORDER BY date DESC, id DESC") fun observePhotos(): Flow<List<ProgressPhotoEntity>>
}
