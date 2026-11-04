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

@Entity(tableName = "run_session", indices = [Index("date")])
data class RunSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val startedAt: Instant,
    val endedAt: Instant,
    val title: String,
    /** "program:<index>", "plan:<id>" or "free". */
    val planKey: String,
    val distanceM: Double = 0.0,
    val movingSec: Int = 0,
    val elapsedSec: Int = 0,
    val elevationGainM: Double = 0.0,
    val kcal: Int = 0,
    /** Planned intervals all completed. */
    val planCompleted: Boolean = false,
    val finished: Boolean = false,
    val rpe: Int? = null,
    val notes: String = "",
    val healthConnectId: String? = null,
)

@Entity(tableName = "run_point", indices = [Index("runId")])
data class RunPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val runId: Long,
    val t: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double? = null,
    val accuracy: Float,
    /** Active (non-paused) seconds and cumulative metres at this point. */
    val activeSec: Double,
    val distanceM: Double,
    val segmentIndex: Int = 0,
    /** SegKind name, or null for a free run. */
    val segmentKind: String? = null,
)

@Entity(tableName = "run_plan")
data class RunPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** JSON-encoded RunPlan. */
    val planJson: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Dao
interface RunDao {
    @Insert suspend fun insertRun(r: RunSessionEntity): Long
    @Update suspend fun updateRun(r: RunSessionEntity)
    @Query("SELECT * FROM run_session WHERE id = :id") suspend fun run(id: Long): RunSessionEntity?
    @Query("SELECT * FROM run_session WHERE id = :id") fun observeRun(id: Long): Flow<RunSessionEntity?>
    @Query("SELECT * FROM run_session WHERE finished = 1 ORDER BY startedAt DESC") fun observeRuns(): Flow<List<RunSessionEntity>>
    @Query("SELECT * FROM run_session WHERE finished = 1 AND date BETWEEN :from AND :to ORDER BY startedAt") suspend fun runs(from: LocalDate, to: LocalDate): List<RunSessionEntity>
    @Query("DELETE FROM run_session WHERE id = :id") suspend fun deleteRun(id: Long)
    @Query("DELETE FROM run_session WHERE finished = 0") suspend fun deleteUnfinished()
    @Query("SELECT id FROM run_session WHERE finished = 0") suspend fun unfinishedIds(): List<Long>

    @Insert suspend fun insertPoints(p: List<RunPointEntity>)
    @Query("SELECT * FROM run_point WHERE runId = :runId ORDER BY t") suspend fun points(runId: Long): List<RunPointEntity>
    @Query("DELETE FROM run_point WHERE runId = :runId") suspend fun deletePoints(runId: Long)

    @Insert suspend fun insertPlan(p: RunPlanEntity): Long
    @Update suspend fun updatePlan(p: RunPlanEntity)
    @Query("DELETE FROM run_plan WHERE id = :id") suspend fun deletePlan(id: Long)
    @Query("SELECT * FROM run_plan ORDER BY updatedAt DESC") fun observePlans(): Flow<List<RunPlanEntity>>
    @Query("SELECT * FROM run_plan WHERE id = :id") suspend fun plan(id: Long): RunPlanEntity?
}
