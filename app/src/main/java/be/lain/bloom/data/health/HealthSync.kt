package be.lain.bloom.data.health

import android.content.Context
import android.content.pm.PackageManager
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import be.lain.bloom.data.SettingsRepository
import be.lain.bloom.data.db.BloomDatabase
import be.lain.bloom.data.db.DataSource
import be.lain.bloom.data.db.WeightEntryEntity
import be.lain.bloom.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

@Entity(tableName = "hc_daily")
data class HcDailyEntity(
    @PrimaryKey val date: LocalDate,
    val steps: Long,
    /** Steps that fell inside runs recorded in Bloom (shown separately, never double counted). */
    val stepsDuringRuns: Long = 0,
    val updatedAt: Instant,
)

@Entity(tableName = "hc_session")
data class HcSessionEntity(
    @PrimaryKey val id: String,
    val date: LocalDate,
    val start: Instant,
    val end: Instant,
    val exerciseType: Int,
    val title: String,
    val sourcePackage: String,
    val distanceM: Double? = null,
    val kcal: Double? = null,
    /** "run:<id>" or "workout:<id>" when it overlaps a session recorded in Bloom. */
    val duplicateOf: String? = null,
)

@Dao
interface HealthDao {
    @Upsert suspend fun upsertDaily(d: List<HcDailyEntity>)
    @Query("SELECT * FROM hc_daily WHERE date = :date") fun observeDay(date: LocalDate): Flow<HcDailyEntity?>
    @Query("SELECT * FROM hc_daily WHERE date BETWEEN :from AND :to ORDER BY date") suspend fun days(from: LocalDate, to: LocalDate): List<HcDailyEntity>
    @Query("SELECT * FROM hc_daily WHERE date BETWEEN :from AND :to ORDER BY date") fun observeDays(from: LocalDate, to: LocalDate): Flow<List<HcDailyEntity>>
    @Upsert suspend fun upsertSessions(s: List<HcSessionEntity>)
    @Query("DELETE FROM hc_session WHERE date >= :from") suspend fun clearSessionsFrom(from: LocalDate)
    @Query("SELECT * FROM hc_session WHERE date = :date AND duplicateOf IS NULL ORDER BY start") fun observeSessions(date: LocalDate): Flow<List<HcSessionEntity>>
    @Query("SELECT * FROM hc_session WHERE date BETWEEN :from AND :to AND duplicateOf IS NULL ORDER BY start") suspend fun sessions(from: LocalDate, to: LocalDate): List<HcSessionEntity>
    @Query("SELECT COUNT(*) FROM weight_entry WHERE externalId = :id") suspend fun weightExists(id: String): Int
}

enum class HcStatus { UNAVAILABLE, NEEDS_INSTALL, NOT_CONNECTED, CONNECTED }

/** Two time windows overlap by at least half of the shorter one. */
fun overlapsMostly(aStart: Instant, aEnd: Instant, bStart: Instant, bEnd: Instant): Boolean {
    val overlap = Duration.between(maxOf(aStart, bStart), minOf(aEnd, bEnd)).toMillis()
    if (overlap <= 0) return false
    val shorter = min(Duration.between(aStart, aEnd).toMillis(), Duration.between(bStart, bEnd).toMillis())
    return shorter > 0 && overlap >= shorter / 2
}

/**
 * Health Connect sync. Rules that keep numbers honest:
 * 1. Records written by Bloom itself are ignored when reading back.
 * 2. Another app's session that overlaps a Bloom run/workout is marked as a duplicate and hidden.
 * 3. Steps during Bloom runs are reported separately ("incl. N during your run").
 * 4. Exercise never adds to the food budget — expenditure is learned weekly from the weight trend.
 */
@Singleton
class HealthSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: BloomDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock,
    @AppScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(HcStatus.NOT_CONNECTED)
    val status = _status.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError = _lastError.asStateFlow()
    val dao get() = db.healthDao()

    val readPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )
    val writePermissions = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
    )
    val allPermissions = readPermissions + writePermissions

    private fun client(): HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(context) else null

    suspend fun refreshStatus(): HcStatus {
        val sdk = HealthConnectClient.getSdkStatus(context)
        val s = when {
            sdk == HealthConnectClient.SDK_UNAVAILABLE -> HcStatus.UNAVAILABLE
            sdk == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcStatus.NEEDS_INSTALL
            else -> {
                val granted = runCatching { client()!!.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
                if (granted.containsAll(readPermissions)) HcStatus.CONNECTED else HcStatus.NOT_CONNECTED
            }
        }
        _status.value = s
        return s
    }

    fun syncSoon() = scope.launch { runCatching { sync() } }

    suspend fun sync(days: Long = 30) = mutex.withLock {
        if (refreshStatus() != HcStatus.CONNECTED) return@withLock
        val c = client() ?: return@withLock
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(clock)
        val from = today.minusDays(days - 1)
        val start = from.atStartOfDay(zone).toInstant()
        val end = clock.instant()
        val me = context.packageName
        try {
            // Steps per day (Health Connect de-duplicates between step sources by priority).
            val buckets = c.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(from.atStartOfDay(), today.plusDays(1).atStartOfDay()), Period.ofDays(1)),
            )
            val runs = db.runDao().runs(from, today)
            val daily = buckets.map { b ->
                val date = b.startTime.toLocalDate()
                val steps = b.result[StepsRecord.COUNT_TOTAL] ?: 0L
                val during = runs.filter { it.date == date }.sumOf { r ->
                    runCatching {
                        c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(r.startedAt, r.endedAt)))[StepsRecord.COUNT_TOTAL] ?: 0L
                    }.getOrDefault(0L)
                }
                HcDailyEntity(date, steps, min(during, steps), clock.instant())
            }
            dao.upsertDaily(daily)

            // Weigh-ins from scales/other apps.
            readAll(c, WeightRecord::class, start, end).filter { it.metadata.dataOrigin.packageName != me }.forEach { w ->
                if (dao.weightExists(w.metadata.id) == 0) {
                    db.bodyDao().insertWeight(
                        WeightEntryEntity(
                            date = w.time.atZone(zone).toLocalDate(), at = w.time, kg = w.weight.inKilograms,
                            source = DataSource.HEALTH_CONNECT, externalId = w.metadata.id,
                        ),
                    )
                }
            }

            // Other apps' exercise sessions, flagged when they duplicate one recorded here.
            val workouts = db.trainingDao().workouts(from, today)
            val sessions = readAll(c, ExerciseSessionRecord::class, start, end).filter { it.metadata.dataOrigin.packageName != me }.map { s ->
                val dup = runs.firstOrNull { overlapsMostly(s.startTime, s.endTime, it.startedAt, it.endedAt) }?.let { "run:${it.id}" }
                    ?: workouts.firstOrNull { overlapsMostly(s.startTime, s.endTime, it.startedAt, it.endedAt) }?.let { "workout:${it.id}" }
                val agg = runCatching {
                    c.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                        TimeRangeFilter.between(s.startTime, s.endTime)))
                }.getOrNull()
                HcSessionEntity(
                    id = s.metadata.id, date = s.startTime.atZone(zone).toLocalDate(), start = s.startTime, end = s.endTime,
                    exerciseType = s.exerciseType, title = s.title ?: exerciseName(s.exerciseType), sourcePackage = s.metadata.dataOrigin.packageName,
                    distanceM = agg?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters, kcal = agg?.get(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories,
                    duplicateOf = dup,
                )
            }
            dao.clearSessionsFrom(from)
            dao.upsertSessions(sessions)
            _lastError.value = null
        } catch (e: Exception) {
            _lastError.value = e.message
        }
    }

    private suspend fun <T : androidx.health.connect.client.records.Record> readAll(
        c: HealthConnectClient, type: kotlin.reflect.KClass<T>, start: Instant, end: Instant,
    ): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val r = c.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(start, end), pageToken = token))
            out += r.records
            token = r.pageToken
        } while (token != null)
        return out
    }

    private fun offset(t: Instant): ZoneOffset = ZoneId.systemDefault().rules.getOffset(t)

    /** Writes a Bloom run (session + route + distance + calories). Returns the session record id. */
    suspend fun writeRun(id: Long): String? {
        if (refreshStatus() != HcStatus.CONNECTED) return null
        val c = client() ?: return null
        val granted = c.permissionController.getGrantedPermissions()
        if (!granted.contains(HealthPermission.getWritePermission(ExerciseSessionRecord::class))) return null
        val run = db.runDao().run(id) ?: return null
        val points = db.runDao().points(id)
        val device = Device(type = Device.TYPE_PHONE)
        val route = if (granted.contains(HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE) && points.size >= 2) {
            ExerciseRoute(points.filter { Instant.ofEpochMilli(it.t) in run.startedAt..run.endedAt }.map {
                ExerciseRoute.Location(time = Instant.ofEpochMilli(it.t), latitude = it.lat, longitude = it.lon,
                    horizontalAccuracy = Length.meters(it.accuracy.toDouble()), altitude = it.alt?.let(Length::meters))
            })
        } else null
        val records = buildList {
            add(
                ExerciseSessionRecord(
                    startTime = run.startedAt, startZoneOffset = offset(run.startedAt), endTime = run.endedAt, endZoneOffset = offset(run.endedAt),
                    metadata = Metadata.activelyRecorded(device, "bloom-run-$id", 1), exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
                    title = run.title, exerciseRoute = route,
                ),
            )
            if (run.distanceM > 0) add(DistanceRecord(run.startedAt, offset(run.startedAt), run.endedAt, offset(run.endedAt),
                Length.meters(run.distanceM), Metadata.activelyRecorded(device, "bloom-run-dist-$id", 1)))
            if (run.kcal > 0) add(ActiveCaloriesBurnedRecord(run.startedAt, offset(run.startedAt), run.endedAt, offset(run.endedAt),
                Energy.kilocalories(run.kcal.toDouble()), Metadata.activelyRecorded(device, "bloom-run-kcal-$id", 1)))
        }
        return runCatching { c.insertRecords(records).recordIdsList.firstOrNull() }.onFailure { _lastError.value = it.message }.getOrNull()
    }

    /** Writes a strength workout as a calisthenics session + active calories. */
    suspend fun writeWorkout(id: Long): String? {
        if (refreshStatus() != HcStatus.CONNECTED) return null
        val c = client() ?: return null
        if (!c.permissionController.getGrantedPermissions().contains(HealthPermission.getWritePermission(ExerciseSessionRecord::class))) return null
        val w = db.trainingDao().workout(id) ?: return null
        val device = Device(type = Device.TYPE_PHONE)
        val records = listOf(
            ExerciseSessionRecord(
                startTime = w.startedAt, startZoneOffset = offset(w.startedAt), endTime = w.endedAt, endZoneOffset = offset(w.endedAt),
                metadata = Metadata.activelyRecorded(device, "bloom-workout-$id", 1),
                exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS, title = w.name,
            ),
            ActiveCaloriesBurnedRecord(w.startedAt, offset(w.startedAt), w.endedAt, offset(w.endedAt),
                Energy.kilocalories(max(1, w.kcal).toDouble()), Metadata.activelyRecorded(device, "bloom-workout-kcal-$id", 1)),
        )
        return runCatching { c.insertRecords(records).recordIdsList.firstOrNull() }.onFailure { _lastError.value = it.message }.getOrNull()
    }

    suspend fun writeWeight(kg: Double, at: Instant) {
        if (!settings.settings.first().shareWeight || refreshStatus() != HcStatus.CONNECTED) return
        val c = client() ?: return
        runCatching {
            c.insertRecords(listOf(WeightRecord(at, offset(at), Mass.kilograms(kg), Metadata.manualEntry(Device(type = Device.TYPE_PHONE)))))
        }
    }

    fun isInstalled(): Boolean = runCatching { context.packageManager.getPackageInfo("com.google.android.apps.healthdata", 0); true }
        .getOrElse { it !is PackageManager.NameNotFoundException }

    companion object {
        fun exerciseName(type: Int): String = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Run"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walk"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Bike ride"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Swim"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hike"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "Strength"
            else -> "Workout"
        }
    }
}
