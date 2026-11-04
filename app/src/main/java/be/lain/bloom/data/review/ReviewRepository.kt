package be.lain.bloom.data.review

import androidx.room.withTransaction
import be.lain.bloom.data.BodyRepository
import be.lain.bloom.data.ProfileRepository
import be.lain.bloom.data.SettingsRepository
import be.lain.bloom.data.activeOn
import be.lain.bloom.data.db.BloomDatabase
import be.lain.bloom.data.db.MeasurementEntity
import be.lain.bloom.data.db.PlanVersionEntity
import be.lain.bloom.data.db.RunSessionEntity
import be.lain.bloom.data.db.WeeklyReviewEntity
import be.lain.bloom.data.db.WorkoutSessionEntity
import be.lain.bloom.data.food.FoodRepository
import be.lain.bloom.data.health.HcSessionEntity
import be.lain.bloom.data.training.RunRepository
import be.lain.bloom.data.training.TrainingRepository
import be.lain.bloom.domain.Adaptive
import be.lain.bloom.domain.DayStatus
import be.lain.bloom.domain.Nutrients
import be.lain.bloom.domain.PlanSource
import be.lain.bloom.domain.PlanVersion
import be.lain.bloom.domain.Profile
import be.lain.bloom.domain.Trend
import be.lain.bloom.domain.WeightPoint
import be.lain.bloom.domain.training.Program
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

data class ReviewDay(
    val date: LocalDate,
    val kcal: Double,
    val entries: Int,
    val meals: Int,
    val targetKcal: Int?,
    /** What the user marked earlier in the diary, if anything. */
    val saved: DayStatus?,
    val guess: DayStatus,
) {
    val initial: DayStatus get() = saved ?: guess
}

/** Everything the review shows about one week. */
data class WeekData(
    val reviewDate: LocalDate,
    val weekStart: LocalDate,
    val weekEnd: LocalDate,
    val profile: Profile,
    val plan: PlanVersion?,
    val days: List<ReviewDay>,
    val trendStart: Double?,
    val trendEnd: Double?,
    val weeklyRate: Double?,
    val weighInsThisWeek: Int,
    val latestKg: Double?,
    val weighedToday: Boolean,
    val workouts: List<WorkoutSessionEntity>,
    val runs: List<RunSessionEntity>,
    val otherSessions: List<HcSessionEntity>,
    val avgSteps: Int?,
    val stepDays: Int,
    val latestMeasurement: MeasurementEntity?,
    val previousMeasurement: MeasurementEntity?,
    val strengthPhase: String,
    val strengthReady: Boolean,
    /** Cycle summary when tracking is on (null otherwise). */
    val cycle: kotlinx.serialization.json.JsonObject? = null,
) {
    val workoutsPlanned: Int get() = profile.strengthDays.size
    val runsPlanned: Int get() = profile.runDays.size
    val runKm: Double get() = runs.sumOf { it.distanceM } / 1000.0
    val loggedDays: Int get() = days.count { it.entries > 0 }
    /** Average over every day with any food logged. */
    val avgLoggedKcal: Int? get() = days.filter { it.entries > 0 }.takeIf { it.isNotEmpty() }?.map { it.kcal }?.average()?.roundToInt()
    val trendChange: Double? get() = if (trendStart != null && trendEnd != null) trendEnd - trendStart else null
}

data class CheckIn(
    val energy: Int? = null,
    val hunger: Int? = null,
    val sleep: Int? = null,
    val soreness: Int? = null,
    val notes: String = "",
)

@Singleton
class ReviewRepository @Inject constructor(
    private val db: BloomDatabase,
    private val profiles: ProfileRepository,
    private val body: BodyRepository,
    private val food: FoodRepository,
    private val training: TrainingRepository,
    private val runs: RunRepository,
    private val settings: SettingsRepository,
    private val cycle: be.lain.bloom.data.cycle.CycleRepository,
    private val clock: Clock,
) {
    private val dao get() = db.reviewDao()

    val reviews: Flow<List<WeeklyReviewEntity>> = dao.observeAll()

    fun today(): LocalDate = LocalDate.now(clock)

    /** Whether a review is waiting on [date]. */
    fun dueOn(date: LocalDate): Flow<Boolean> = combine(profiles.profile, settings.settings, dao.observeLatest()) { p, s, last ->
        p != null && Adaptive.isDue(date, s.reviewDay, p.startDate, last?.reviewDate)
    }

    suspend fun isDue(date: LocalDate = today()): Boolean = dueOn(date).first()

    suspend fun review(id: Long) = dao.get(id)

    suspend fun loadWeek(reviewDate: LocalDate = today()): WeekData? {
        val profile = profiles.profile.first() ?: return null
        val plans = profiles.plans.first()
        val start = reviewDate.minusDays(7)
        val end = reviewDate.minusDays(1)
        val entries = food.range(start, end)
        val statuses = food.dayStatuses(start, end).associate { it.date to it.status }
        val days = (0L..6L).map { i ->
            val d = start.plusDays(i)
            val day = entries.filter { it.date == d }
            val kcal = Nutrients.sum(day.map { it.nutrients }).kcal ?: 0.0
            val target = plans.activeOn(d)?.targets?.kcal
            ReviewDay(
                date = d, kcal = kcal, entries = day.size, meals = day.map { it.meal }.distinct().size, targetKcal = target,
                saved = statuses[d], guess = Adaptive.guessStatus(kcal, day.size, day.map { it.meal }.distinct().size, target),
            )
        }
        val weights = body.weights.first()
        val pts = weights.map { WeightPoint(it.date, it.kg) }
        val hc = db.healthDao().days(start, end).filter { it.steps > 0 }
        val measurements = body.measurements.first().sortedBy { it.date }
        val st = training.status.first()
        val phase = st.phase
        return WeekData(
            reviewDate = reviewDate, weekStart = start, weekEnd = end, profile = profile,
            plan = plans.activeOn(reviewDate), days = days,
            trendStart = Trend.at(pts, start), trendEnd = Trend.at(pts, reviewDate),
            weeklyRate = Trend.weeklyRate(pts, reviewDate, Adaptive.RATE_WINDOW_DAYS),
            weighInsThisWeek = weights.filter { !it.date.isBefore(start) && !it.date.isAfter(reviewDate) }.map { it.date }.distinct().size,
            latestKg = weights.lastOrNull()?.kg,
            weighedToday = weights.any { it.date == reviewDate },
            workouts = training.workoutsBetween(start, end),
            runs = runs.runsBetween(start, end),
            otherSessions = db.healthDao().sessions(start, end),
            avgSteps = hc.takeIf { it.isNotEmpty() }?.map { it.steps }?.average()?.roundToInt(),
            stepDays = hc.size,
            latestMeasurement = measurements.lastOrNull(),
            previousMeasurement = measurements.dropLast(1).lastOrNull(),
            strengthPhase = "${phase.name} (phase ${phase.index + 1} of ${Program.phases.size}, week ${st.week} of ${phase.weeks})",
            strengthReady = st.readyToAdvance,
            cycle = cycle.summary(reviewDate).takeIf { cycle.enabled.first() },
        )
    }

    /** Runs the adaptive engine with the user's day markings and the latest weigh-ins. */
    suspend fun compute(week: WeekData, statuses: Map<LocalDate, DayStatus>): Adaptive.Result {
        val weights = body.weights.first().map { WeightPoint(it.date, it.kg) }
        val plan = profiles.plans.first().activeOn(week.reviewDate)
        return Adaptive.review(
            Adaptive.Input(
                profile = week.profile,
                weights = weights,
                days = week.days.map { Adaptive.Day(it.date, it.kcal, statuses[it.date] ?: it.initial) },
                currentTargetKcal = plan?.targets?.kcal ?: week.plan?.targets?.kcal ?: 0,
                priorExpenditure = plan?.expenditureKcal ?: 0,
                reviewDate = week.reviewDate,
                cycleSwing = cycle.waterRetention(week.reviewDate) || cycle.waterRetention(week.weekStart),
            ),
        )
    }

    /** New targets start today if nothing is logged yet, otherwise tomorrow (so today isn't judged twice). */
    suspend fun effectiveDate(date: LocalDate = today()): LocalDate =
        if (food.range(date, date).isEmpty()) date else date.plusDays(1)

    /** Saves the review and, if accepted, the new plan. Returns the review id. */
    suspend fun complete(
        week: WeekData,
        statuses: Map<LocalDate, DayStatus>,
        checkIn: CheckIn,
        result: Adaptive.Result,
        accept: Boolean,
        chatId: Long?,
    ): Long {
        val now = clock.instant()
        val effective = effectiveDate(week.reviewDate)
        return db.withTransaction {
            statuses.forEach { (d, s) -> food.setDayStatus(d, s) }
            val changes = accept && result.proposedKcal != result.currentKcal
            if (changes || (accept && result.newExpenditure != result.priorExpenditure)) {
                val t = result.targets
                db.planDao().insert(
                    PlanVersionEntity(
                        effectiveDate = effective, kcal = t.kcal, proteinG = t.proteinG, carbsG = t.carbsG, fatG = t.fatG, fiberG = t.fiberG,
                        expenditureKcal = result.newExpenditure, source = PlanSource.WEEKLY_REVIEW,
                        reason = "Weekly review: you burn about ${"%,d".format(java.util.Locale.UK, result.newExpenditure)} kcal a day", createdAt = now,
                    ),
                )
            }
            dao.insert(
                WeeklyReviewEntity(
                    reviewDate = week.reviewDate, weekStart = week.weekStart, weekEnd = week.weekEnd, createdAt = now,
                    completeDays = result.completeDays, partialDays = result.partialDays,
                    avgIntakeKcal = result.avgIntake?.roundToInt(), trendKg = result.trendKg, weeklyRateKg = result.weeklyRateKg,
                    weighIns = result.weighIns,
                    workoutsDone = week.workouts.size, workoutsPlanned = week.workoutsPlanned,
                    runsDone = week.runs.size, runsPlanned = week.runsPlanned, runKm = week.runKm, avgSteps = week.avgSteps,
                    energy = checkIn.energy, hunger = checkIn.hunger, sleep = checkIn.sleep, soreness = checkIn.soreness,
                    notes = checkIn.notes.trim(),
                    measuredExpenditure = result.measuredExpenditure, newExpenditure = result.newExpenditure,
                    oldKcal = result.currentKcal, proposedKcal = result.proposedKcal, applied = accept, chatId = chatId,
                ),
            )
        }
    }

    val photos = dao.observePhotos()

    suspend fun addPhoto(path: String, date: LocalDate = today()) =
        dao.insertPhoto(be.lain.bloom.data.db.ProgressPhotoEntity(date = date, path = path, createdAt = clock.instant()))

    suspend fun deletePhoto(id: Long) {
        dao.photo(id)?.let { runCatching { java.io.File(it.path).delete() } }
        dao.deletePhoto(id)
    }

    suspend fun attachChat(reviewId: Long, chatId: Long) {
        dao.get(reviewId)?.let { dao.update(it.copy(chatId = chatId)) }
    }

    /** The data block handed to the AI for its write-up (information, not instructions). */
    fun aiBrief(week: WeekData, statuses: Map<LocalDate, DayStatus>, checkIn: CheckIn, r: Adaptive.Result, accepted: Boolean?): JsonObject =
        buildJsonObject {
            put("week", "${week.weekStart} to ${week.weekEnd}")
            put("days", buildJsonArray {
                week.days.forEach { d ->
                    add(buildJsonObject {
                        put("date", d.date.toString()); put("weekday", d.date.dayOfWeek.name.lowercase())
                        put("kcal", d.kcal.roundToInt()); put("entries", d.entries)
                        put("logged", (statuses[d.date] ?: d.initial).label)
                        d.targetKcal?.let { put("target", it) }
                    })
                }
            })
            put("engine", buildJsonObject {
                put("complete_days", r.completeDays)
                r.avgIntake?.let { put("avg_intake_complete_days", it.roundToInt()) }
                r.trendKg?.let { put("trend_kg", (it * 10).roundToInt() / 10.0) }
                r.weeklyRateKg?.let { put("rate_kg_per_week_14d", (it * 100).roundToInt() / 100.0) }
                put("weigh_ins_14d", r.weighIns)
                r.measuredExpenditure?.let { put("measured_burn", it) }
                put("previous_burn_estimate", r.priorExpenditure)
                put("new_burn_estimate", r.newExpenditure)
                put("current_target_kcal", r.currentKcal)
                put("new_target_kcal", r.proposedKcal)
                put("new_macros", "P ${r.targets.proteinG} g, C ${r.targets.carbsG} g, F ${r.targets.fatG} g, fibre ${r.targets.fiberG} g")
                put("safety_floor_kcal", r.floor)
                put("capped_to_150", r.capped)
                accepted?.let { put("user_accepted_new_target", it) }
                put("engine_notes", buildJsonArray { r.notes.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            })
            put("training", buildJsonObject {
                put("strength_done", week.workouts.size); put("strength_planned", week.workoutsPlanned)
                put("strength_phase", week.strengthPhase)
                put("ready_to_advance", week.strengthReady)
                put("strength_sessions", buildJsonArray {
                    week.workouts.forEach { w ->
                        add(buildJsonObject {
                            put("date", w.date.toString()); put("name", w.name); put("sets", "${w.setsDone}/${w.setsPlanned}")
                            w.rpe?.let { put("rpe", it) }
                        })
                    }
                })
                put("runs_done", week.runs.size); put("runs_planned", week.runsPlanned)
                put("run_km", (week.runKm * 10).roundToInt() / 10.0)
                put("runs", buildJsonArray {
                    week.runs.forEach { r2 ->
                        add(buildJsonObject {
                            put("date", r2.date.toString()); put("title", r2.title); put("km", (r2.distanceM / 100).roundToInt() / 10.0)
                            put("minutes", r2.movingSec / 60); put("completed_plan", r2.planCompleted); r2.rpe?.let { put("rpe", it) }
                        })
                    }
                })
                if (week.otherSessions.isNotEmpty()) put("other_apps", buildJsonArray {
                    week.otherSessions.forEach { s -> add(buildJsonObject { put("date", s.date.toString()); put("title", s.title) }) }
                })
                week.avgSteps?.let { put("avg_steps", it) }
            })
            week.latestMeasurement?.let { m ->
                put("measurements", buildJsonObject {
                    put("date", m.date.toString())
                    m.waistCm?.let { put("waist_cm", it) }; m.hipsCm?.let { put("hips_cm", it) }
                    week.previousMeasurement?.let { prev ->
                        put("previous_date", prev.date.toString())
                        prev.waistCm?.let { put("previous_waist_cm", it) }; prev.hipsCm?.let { put("previous_hips_cm", it) }
                    }
                })
            }
            week.cycle?.let { put("cycle", it) }
            if (week.profile.notes.isNotBlank()) put("about_the_user", week.profile.notes)
            put("check_in_1_to_5", buildJsonObject {
                checkIn.energy?.let { put("energy", it) }; checkIn.hunger?.let { put("hunger", it) }
                checkIn.sleep?.let { put("sleep", it) }; checkIn.soreness?.let { put("soreness", it) }
                if (checkIn.notes.isNotBlank()) put("notes", checkIn.notes.trim())
            })
        }
}
