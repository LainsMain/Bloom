package be.lain.bloom.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import be.lain.bloom.domain.training.Effort
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

@Entity(tableName = "workout_session", indices = [Index("date")])
data class WorkoutSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val startedAt: Instant,
    val endedAt: Instant,
    val phase: Int,
    /** "A" / "B" */
    val sessionId: String,
    val name: String,
    val activeSeconds: Int,
    val kcal: Int,
    val setsDone: Int,
    val setsPlanned: Int,
    /** 1–10 session RPE. */
    val rpe: Int? = null,
    val notes: String = "",
    val healthConnectId: String? = null,
    /** Which strength program this belonged to ("manual" sessions keep the program they were logged under). */
    @androidx.room.ColumnInfo(defaultValue = "feminine-shape") val programId: String = be.lain.bloom.domain.training.Program.ID,
)

@Entity(tableName = "set_log", indices = [Index("workoutId"), Index("slotKey")])
data class SetLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val exerciseId: String,
    val slotKey: String,
    val setIndex: Int,
    val side: String? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val effort: Effort? = null,
    val skipped: Boolean = false,
)

/** The variation chosen for a program slot (e.g. switched to a harder push-up). */
@Entity(tableName = "slot_choice")
data class SlotChoiceEntity(@PrimaryKey val slotKey: String, val exerciseId: String)

/** User-chosen demo video for an exercise. */
@Entity(tableName = "video_override")
data class VideoOverrideEntity(@PrimaryKey val exerciseId: String, val youtubeId: String, val title: String = "")

@Dao
interface TrainingDao {
    @Insert suspend fun insertWorkout(w: WorkoutSessionEntity): Long
    @Update suspend fun updateWorkout(w: WorkoutSessionEntity)
    @Query("DELETE FROM workout_session WHERE id = :id") suspend fun deleteWorkout(id: Long)
    @Query("DELETE FROM set_log WHERE workoutId = :id") suspend fun deleteSets(id: Long)
    @Insert suspend fun insertSets(s: List<SetLogEntity>)
    @Query("SELECT * FROM workout_session ORDER BY startedAt DESC") fun observeWorkouts(): Flow<List<WorkoutSessionEntity>>
    @Query("SELECT * FROM workout_session WHERE date BETWEEN :from AND :to ORDER BY startedAt") suspend fun workouts(from: LocalDate, to: LocalDate): List<WorkoutSessionEntity>
    @Query("SELECT * FROM workout_session WHERE id = :id") suspend fun workout(id: Long): WorkoutSessionEntity?
    @Query("SELECT * FROM set_log WHERE workoutId = :id ORDER BY id") suspend fun sets(id: Long): List<SetLogEntity>
    @Query("SELECT COUNT(*) FROM workout_session WHERE programId = :programId AND phase = :phase AND date >= :since")
    fun observeCountInPhase(programId: String, phase: Int, since: LocalDate): Flow<Int>

    /** Sets from the most recent workout that included this slot. */
    @Query(
        """SELECT * FROM set_log WHERE slotKey = :slot AND workoutId =
           (SELECT s.workoutId FROM set_log s JOIN workout_session w ON w.id = s.workoutId WHERE s.slotKey = :slot ORDER BY w.startedAt DESC LIMIT 1)
           ORDER BY id""",
    )
    suspend fun lastForSlot(slot: String): List<SetLogEntity>

    @Query("SELECT * FROM slot_choice") fun observeChoices(): Flow<List<SlotChoiceEntity>>
    @Upsert suspend fun setChoice(c: SlotChoiceEntity)
    @Query("DELETE FROM slot_choice WHERE slotKey = :slot") suspend fun clearChoice(slot: String)

    @Query("SELECT * FROM video_override") fun observeVideos(): Flow<List<VideoOverrideEntity>>
    @Upsert suspend fun setVideo(v: VideoOverrideEntity)
    @Query("DELETE FROM video_override WHERE exerciseId = :id") suspend fun clearVideo(id: String)
}
