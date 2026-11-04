package be.lain.bloom.data

import androidx.room.withTransaction
import be.lain.bloom.data.db.BloomDatabase
import be.lain.bloom.data.db.MeasurementEntity
import be.lain.bloom.data.db.PlanVersionEntity
import be.lain.bloom.data.db.ProfileEntity
import be.lain.bloom.data.db.WaterEntryEntity
import be.lain.bloom.data.db.WeightEntryEntity
import be.lain.bloom.domain.Energy
import be.lain.bloom.domain.PlanSource
import be.lain.bloom.domain.PlanVersion
import be.lain.bloom.domain.Profile
import be.lain.bloom.domain.Targets
import be.lain.bloom.domain.Trend
import be.lain.bloom.domain.WeightPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

fun ProfileEntity.toDomain() = Profile(
    name = name, goal = goal, formula = formula, birthYear = birthYear, heightCm = heightCm,
    startWeightKg = startWeightKg, goalWeightKg = goalWeightKg, lifestyle = lifestyle,
    paceKgPerWeek = paceKgPerWeek, strengthDays = strengthDays, runDays = runDays, startDate = startDate,
)

fun Profile.toEntity(now: Instant) = ProfileEntity(
    name = name, goal = goal, formula = formula, birthYear = birthYear, heightCm = heightCm,
    startWeightKg = startWeightKg, goalWeightKg = goalWeightKg, lifestyle = lifestyle,
    paceKgPerWeek = paceKgPerWeek, strengthDays = strengthDays, runDays = runDays, startDate = startDate,
    updatedAt = now,
)

fun PlanVersionEntity.toDomain() = PlanVersion(
    id = id, effectiveDate = effectiveDate,
    targets = Targets(kcal, proteinG, carbsG, fatG, fiberG),
    expenditureKcal = expenditureKcal, source = source, reason = reason,
)

/** Picks the plan in force on [date] from a list sorted newest first. */
fun List<PlanVersion>.activeOn(date: LocalDate): PlanVersion? =
    filter { !it.effectiveDate.isAfter(date) }.maxWithOrNull(compareBy<PlanVersion> { it.effectiveDate }.thenBy { it.id })
        ?: minByOrNull { it.effectiveDate }

@Singleton
class ProfileRepository @Inject constructor(
    private val db: BloomDatabase,
    private val clock: Clock,
) {
    val profile: Flow<Profile?> = db.profileDao().observe().map { it?.toDomain() }
    val plans: Flow<List<PlanVersion>> = db.planDao().observeAll().map { list -> list.map { it.toDomain() } }

    /** Saves the onboarding result: profile, first plan and first weigh-in, atomically. */
    suspend fun completeOnboarding(profile: Profile) {
        val now = clock.instant()
        val today = LocalDate.now(clock)
        val result = Energy.targets(profile, profile.startWeightKg, today)
        db.withTransaction {
            db.profileDao().upsert(profile.toEntity(now))
            db.planDao().insert(planEntity(result, today, PlanSource.ONBOARDING, "Initial plan from your setup", now))
            db.bodyDao().insertWeight(WeightEntryEntity(date = today, at = now, kg = profile.startWeightKg))
        }
    }

    /** Updates the profile and starts a new plan from today using the current trend weight. */
    suspend fun updateProfile(profile: Profile, reason: String = "You edited your profile") {
        val now = clock.instant()
        val today = LocalDate.now(clock)
        val weights = db.bodyDao().weights().map { WeightPoint(it.date, it.kg) }
        val current = Trend.at(weights, today) ?: profile.startWeightKg
        val result = Energy.targets(profile, current, today)
        db.withTransaction {
            db.profileDao().upsert(profile.toEntity(now))
            db.planDao().insert(planEntity(result, today, PlanSource.MANUAL, reason, now))
        }
    }

    /** Changes only the training days; the calorie plan is left to the weekly review. */
    suspend fun updateTrainingDays(strength: Set<java.time.DayOfWeek>, run: Set<java.time.DayOfWeek>) {
        val current = db.profileDao().get() ?: return
        if (strength.isEmpty()) return
        db.profileDao().upsert(current.copy(strengthDays = strength, runDays = run - strength, updatedAt = clock.instant()))
    }

    /** Stores manually chosen targets (e.g. a custom calorie goal). */
    suspend fun setManualTargets(targets: Targets, expenditure: Int, reason: String) {
        val now = clock.instant()
        db.planDao().insert(
            PlanVersionEntity(
                effectiveDate = LocalDate.now(clock), kcal = targets.kcal, proteinG = targets.proteinG,
                carbsG = targets.carbsG, fatG = targets.fatG, fiberG = targets.fiberG,
                expenditureKcal = expenditure, source = PlanSource.MANUAL, reason = reason, createdAt = now,
            ),
        )
    }

    private fun planEntity(r: Energy.Result, date: LocalDate, source: PlanSource, reason: String, now: Instant) =
        PlanVersionEntity(
            effectiveDate = date, kcal = r.targets.kcal, proteinG = r.targets.proteinG, carbsG = r.targets.carbsG,
            fatG = r.targets.fatG, fiberG = r.targets.fiberG, expenditureKcal = r.expenditure,
            source = source, reason = reason, createdAt = now,
        )
}

data class WeightEntry(val id: Long, val date: LocalDate, val at: Instant, val kg: Double)

@Singleton
class BodyRepository @Inject constructor(
    private val db: BloomDatabase,
    private val health: dagger.Lazy<be.lain.bloom.data.health.HealthSync>,
    private val clock: Clock,
) {
    private val dao get() = db.bodyDao()

    val weights: Flow<List<WeightEntry>> =
        dao.observeWeights().map { list -> list.map { WeightEntry(it.id, it.date, it.at, it.kg) } }

    val measurements: Flow<List<MeasurementEntity>> = dao.observeMeasurements()

    fun water(date: LocalDate): Flow<Int> = dao.observeWater(date).map { list -> list.sumOf { it.ml } }

    suspend fun logWeight(kg: Double, date: LocalDate = LocalDate.now(clock)): Long {
        val at = clock.instant()
        val id = dao.insertWeight(WeightEntryEntity(date = date, at = at, kg = kg))
        runCatching { health.get().writeWeight(kg, at) }
        return id
    }

    suspend fun deleteWeight(id: Long) = dao.deleteWeight(id)

    suspend fun addWater(ml: Int, date: LocalDate = LocalDate.now(clock)) {
        dao.insertWater(WaterEntryEntity(date = date, at = clock.instant(), ml = ml))
    }

    suspend fun undoWater(date: LocalDate = LocalDate.now(clock)) = dao.deleteLastWater(date)

    suspend fun logMeasurement(m: MeasurementEntity) = dao.insertMeasurement(m)
    suspend fun deleteMeasurement(id: Long) = dao.deleteMeasurement(id)
}
