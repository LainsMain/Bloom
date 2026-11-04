package be.lain.bloom.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/** One day of cycle tracking: a period day (with flow) and/or symptoms. */
@Entity(tableName = "cycle_day")
data class CycleDayEntity(
    @PrimaryKey val date: LocalDate,
    val period: Boolean,
    /** 1 light, 2 medium, 3 heavy; null if not given. */
    val flow: Int? = null,
    /** Comma-separated symptom keys, e.g. "cramps,bloating". */
    val symptoms: String = "",
    val updatedAt: Instant,
)

@Dao
interface CycleDao {
    @Upsert suspend fun upsert(d: CycleDayEntity)
    @Query("DELETE FROM cycle_day WHERE date = :date") suspend fun delete(date: LocalDate)
    @Query("SELECT * FROM cycle_day WHERE date = :date") suspend fun get(date: LocalDate): CycleDayEntity?
    @Query("SELECT * FROM cycle_day ORDER BY date") fun observeAll(): Flow<List<CycleDayEntity>>
    @Query("SELECT * FROM cycle_day ORDER BY date") suspend fun all(): List<CycleDayEntity>
}
