package be.lain.bloom.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import be.lain.bloom.domain.EnergyFormula
import be.lain.bloom.domain.Goal
import be.lain.bloom.domain.Lifestyle
import be.lain.bloom.domain.PlanSource
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/** Single-row table (id = 1). */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,
    val name: String,
    val goal: Goal,
    val formula: EnergyFormula,
    val birthYear: Int,
    val heightCm: Double,
    val startWeightKg: Double,
    val goalWeightKg: Double,
    val lifestyle: Lifestyle,
    val paceKgPerWeek: Double,
    val strengthDays: Set<DayOfWeek>,
    val runDays: Set<DayOfWeek>,
    val startDate: LocalDate,
    val updatedAt: Instant,
    @androidx.room.ColumnInfo(defaultValue = "") val notes: String = "",
)

@Entity(tableName = "plan_version", indices = [Index("effectiveDate")])
data class PlanVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val effectiveDate: LocalDate,
    val kcal: Int,
    val proteinG: Int,
    val carbsG: Int,
    val fatG: Int,
    val fiberG: Int,
    val expenditureKcal: Int,
    val source: PlanSource,
    val reason: String,
    val createdAt: Instant,
)

enum class DataSource { MANUAL, HEALTH_CONNECT, AI }

@Entity(tableName = "weight_entry", indices = [Index("date")])
data class WeightEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val at: Instant,
    val kg: Double,
    val source: DataSource = DataSource.MANUAL,
    /** Health Connect record id when imported, to avoid duplicates. */
    val externalId: String? = null,
)

@Entity(tableName = "water_entry", indices = [Index("date")])
data class WaterEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val at: Instant,
    val ml: Int,
)

@Entity(tableName = "measurement", indices = [Index("date")])
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: LocalDate,
    val waistCm: Double? = null,
    val hipsCm: Double? = null,
    val chestCm: Double? = null,
    val thighCm: Double? = null,
    val armCm: Double? = null,
    val note: String = "",
)
