package be.lain.bloom.feature.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.lain.bloom.data.BodyRepository
import be.lain.bloom.data.ProfileRepository
import be.lain.bloom.data.WeightEntry
import be.lain.bloom.data.activeOn
import be.lain.bloom.data.db.MeasurementEntity
import be.lain.bloom.domain.Energy
import be.lain.bloom.domain.Goal
import be.lain.bloom.domain.Profile
import be.lain.bloom.domain.Trend
import be.lain.bloom.domain.TrendPoint
import be.lain.bloom.domain.WeightPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.ceil

enum class ChartRange(val label: String, val days: Long?) {
    MONTH("1M", 30), QUARTER("3M", 91), HALF("6M", 182), ALL("All", null)
}

data class ProgressState(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val profile: Profile? = null,
    val range: ChartRange = ChartRange.QUARTER,
    val showProjection: Boolean = false,
    val points: List<TrendPoint> = emptyList(),
    val projection: List<Pair<LocalDate, Double>> = emptyList(),
    val currentTrend: Double? = null,
    val changeSinceStart: Double? = null,
    val weeklyRate: Double? = null,
    val toGo: Double? = null,
    val plannedGoalDate: LocalDate? = null,
    val currentRateGoalDate: LocalDate? = null,
    val weights: List<WeightEntry> = emptyList(),
    val measurements: List<MeasurementEntity> = emptyList(),
)

data class IntakeDay(val date: LocalDate, val kcal: Double, val status: be.lain.bloom.domain.DayStatus, val target: Int?)
data class WeekBucket(val start: LocalDate, val workouts: Int, val runs: Int, val km: Double)

data class ProgressExtras(
    val intake: List<IntakeDay> = emptyList(),
    val weeks: List<WeekBucket> = emptyList(),
    val plannedStrength: Int = 0,
    val reviews: List<be.lain.bloom.data.db.WeeklyReviewEntity> = emptyList(),
    val reviewDue: Boolean = false,
    val photos: List<be.lain.bloom.data.db.ProgressPhotoEntity> = emptyList(),
    val nextReview: LocalDate? = null,
    val periodDays: Set<LocalDate> = emptySet(),
)

@HiltViewModel
class ProgressViewModel @Inject constructor(
    profiles: ProfileRepository,
    private val body: BodyRepository,
    food: be.lain.bloom.data.food.FoodRepository,
    training: be.lain.bloom.data.training.TrainingRepository,
    runs: be.lain.bloom.data.training.RunRepository,
    private val reviews: be.lain.bloom.data.review.ReviewRepository,
    private val engine: be.lain.bloom.data.ai.CoachEngine,
    private val messages: be.lain.bloom.core.AppMessages,
    settings: be.lain.bloom.data.SettingsRepository,
    cycle: be.lain.bloom.data.cycle.CycleRepository,
    private val clock: Clock,
) : ViewModel() {
    private val today0 = LocalDate.now(clock)

    /** Intake (last 14 days), training per week (last 8 weeks), reviews and photos. */
    val extras: StateFlow<ProgressExtras> = combine(
        combine(food.observeRange(today0.minusDays(13), today0), food.observeDayStatuses(today0.minusDays(13), today0), profiles.plans, ::Triple),
        combine(training.workouts, runs.runs, ::Pair),
        combine(reviews.reviews, reviews.dueOn(today0), ::Pair),
        reviews.photos,
        combine(profiles.profile, settings.settings, cycle.periodDays, ::Triple),
    ) { (entries, statuses, plans), (workouts, runList), (reviewList, due), photos, (profile, appSettings, periodDays) ->
        val saved = statuses.associate { it.date to it.status }
        val intake = (13 downTo 0).map { back ->
            val d = today0.minusDays(back.toLong())
            val day = entries.filter { it.date == d }
            val kcal = be.lain.bloom.domain.Nutrients.sum(day.map { it.nutrients }).kcal ?: 0.0
            val target = plans.activeOn(d)?.targets?.kcal
            IntakeDay(d, kcal, saved[d] ?: be.lain.bloom.domain.Adaptive.guessStatus(kcal, day.size, day.map { it.meal }.distinct().size, target), target)
        }
        val monday = today0.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val weeks = (7 downTo 0).map { back ->
            val start = monday.minusWeeks(back.toLong())
            val end = start.plusDays(6)
            fun inWeek(d: LocalDate) = !d.isBefore(start) && !d.isAfter(end)
            val rs = runList.filter { inWeek(it.date) }
            WeekBucket(start, workouts.count { inWeek(it.date) }, rs.size, rs.sumOf { it.distanceM } / 1000.0)
        }
        val next = profile?.let { p ->
            val earliest = p.startDate.plusDays(7)
            var d = be.lain.bloom.domain.Adaptive.lastReviewDay(today0, appSettings.reviewDay).plusWeeks(1)
            while (d.isBefore(earliest)) d = d.plusWeeks(1)
            d
        }
        ProgressExtras(intake, weeks, profile?.strengthDays?.size ?: 0, reviewList, due, photos, next, periodDays)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressExtras())

    fun addPhotos(uris: List<android.net.Uri>) = viewModelScope.launch {
        uris.forEach { u ->
            runCatching { engine.importImage(u, folder = "progress_photos") }
                .onSuccess { reviews.addPhoto(it) }
                .onFailure { messages.show("Couldn't save that photo") }
        }
    }

    fun deletePhoto(id: Long) = viewModelScope.launch { reviews.deletePhoto(id) }

    private val range = MutableStateFlow(ChartRange.QUARTER)
    private val projection = MutableStateFlow(false)

    val state: StateFlow<ProgressState> = combine(
        profiles.profile, body.weights, body.measurements, range, projection,
    ) { profile, weights, measurements, range, showProjection ->
        val today = LocalDate.now(clock)
        val pts = weights.map { WeightPoint(it.date, it.kg) }
        val all = Trend.daily(pts, today)
        val from = range.days?.let { today.minusDays(it) }
        val visible = if (from == null) all else all.filter { !it.date.isBefore(from) }
        val trend = all.lastOrNull()?.trend
        val rate = Trend.weeklyRate(pts, today, days = 14)

        var planned: LocalDate? = null
        var proj = emptyList<Pair<LocalDate, Double>>()
        var atRate: LocalDate? = null
        val hasGoal = profile != null && (profile.goal == Goal.LOSE || profile.goal == Goal.GAIN)
        if (profile != null && trend != null && hasGoal) {
            val weeks = Energy.projection(profile, trend, today)
            planned = today.plusWeeks((weeks.size - 1).toLong())
            proj = weeks.mapIndexed { i, kg -> today.plusWeeks(i.toLong()) to kg }
            val remaining = profile.goalWeightKg - trend
            if (rate != null && rate != 0.0 && remaining / rate > 0 && abs(rate) >= 0.05) {
                atRate = today.plusWeeks(ceil(remaining / rate).toLong().coerceAtMost(520))
            }
        }
        ProgressState(
            loaded = true,
            today = today,
            profile = profile,
            range = range,
            showProjection = showProjection && proj.size > 1,
            points = visible,
            projection = if (showProjection) proj else emptyList(),
            currentTrend = trend,
            changeSinceStart = trend?.let { t -> profile?.let { t - it.startWeightKg } },
            weeklyRate = rate,
            toGo = if (hasGoal && trend != null) abs(profile!!.goalWeightKg - trend) else null,
            plannedGoalDate = planned,
            currentRateGoalDate = atRate,
            weights = weights.reversed(),
            measurements = measurements.reversed(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressState())

    fun setRange(r: ChartRange) { range.value = r }
    fun toggleProjection() { projection.value = !projection.value }
    fun logWeight(kg: Double) = viewModelScope.launch { body.logWeight(kg) }
    fun deleteWeight(id: Long) = viewModelScope.launch { body.deleteWeight(id) }
    fun logMeasurement(m: MeasurementEntity) = viewModelScope.launch { body.logMeasurement(m.copy(date = LocalDate.now(clock))) }
    fun deleteMeasurement(id: Long) = viewModelScope.launch { body.deleteMeasurement(id) }
}
