package be.lain.bloom.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.lain.bloom.data.AppSettings
import be.lain.bloom.data.BodyRepository
import be.lain.bloom.data.ProfileRepository
import be.lain.bloom.data.SettingsRepository
import be.lain.bloom.data.WeightEntry
import be.lain.bloom.data.activeOn
import be.lain.bloom.data.food.FoodRepository
import be.lain.bloom.domain.Meal
import be.lain.bloom.domain.Nutrients
import be.lain.bloom.domain.Profile
import be.lain.bloom.domain.Targets
import be.lain.bloom.domain.Trend
import be.lain.bloom.domain.WeightPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

enum class TrainingKind { STRENGTH, RUN, REST }

/** Sessions already finished today (latest of each kind). */
data class DoneToday(
    val run: be.lain.bloom.data.db.RunSessionEntity? = null,
    val workout: be.lain.bloom.data.db.WorkoutSessionEntity? = null,
)

data class TodayState(
    val loaded: Boolean = false,
    val date: LocalDate = LocalDate.now(),
    val profile: Profile? = null,
    val targets: Targets? = null,
    val eatenKcal: Int = 0,
    val protein: Double? = null,
    val carbs: Double? = null,
    val fat: Double? = null,
    val fiber: Double? = null,
    val latestWeight: WeightEntry? = null,
    val trendKg: Double? = null,
    val weeklyRate: Double? = null,
    val weighedToday: Boolean = false,
    val waterMl: Int = 0,
    val settings: AppSettings = AppSettings(),
    val training: TrainingKind = TrainingKind.REST,
    val nextTraining: Pair<DayOfWeek, TrainingKind>? = null,
) {
    val greeting: String
        get() = when (LocalTime.now().hour) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
}

fun Profile.trainingOn(day: DayOfWeek): TrainingKind = when (day) {
    in strengthDays -> TrainingKind.STRENGTH
    in runDays -> TrainingKind.RUN
    else -> TrainingKind.REST
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val body: BodyRepository,
    private val food: FoodRepository,
    training: be.lain.bloom.data.training.TrainingRepository,
    health: be.lain.bloom.data.health.HealthSync,
    reviews: be.lain.bloom.data.review.ReviewRepository,
    runs: be.lain.bloom.data.training.RunRepository,
    private val cycle: be.lain.bloom.data.cycle.CycleRepository,
    settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {
    private val date = MutableStateFlow(LocalDate.now(clock))

    val state: StateFlow<TodayState> = date.flatMapLatest { day ->
        combine(
            combine(profiles.profile, profiles.plans, ::Pair),
            body.weights,
            body.water(day),
            settings.settings,
            food.observeDay(day),
        ) { (profile, plans), weights, water, appSettings, entries ->
            val eaten = Nutrients.sum(entries.map { it.nutrients })
            val points = weights.map { WeightPoint(it.date, it.kg) }
            val training = profile?.trainingOn(day.dayOfWeek) ?: TrainingKind.REST
            val next = profile?.let { p ->
                (1..7).map { day.plusDays(it.toLong()).dayOfWeek }
                    .firstOrNull { p.trainingOn(it) != TrainingKind.REST }
                    ?.let { it to p.trainingOn(it) }
            }
            TodayState(
                loaded = true,
                date = day,
                profile = profile,
                targets = plans.activeOn(day)?.targets,
                eatenKcal = (eaten.kcal ?: 0.0).toInt(),
                protein = eaten.protein ?: 0.0,
                carbs = eaten.carbs ?: 0.0,
                fat = eaten.fat ?: 0.0,
                fiber = eaten.fiber ?: 0.0,
                latestWeight = weights.lastOrNull(),
                trendKg = Trend.at(points, day),
                weeklyRate = Trend.weeklyRate(points, day, days = 14),
                weighedToday = weights.any { it.date == day },
                waterMl = water,
                settings = appSettings,
                training = training,
                nextTraining = next,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    val program = training.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val hcStatus = health.status
    val steps = date.flatMapLatest { health.dao.observeDay(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val otherSessions = date.flatMapLatest { health.dao.observeSessions(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val cycleState = date.flatMapLatest { cycle.state(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun setPeriodToday(on: Boolean) = viewModelScope.launch { cycle.setPeriod(date.value, on) }
    val runPlan = runs.activePlan.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val doneToday = date.flatMapLatest { day ->
        combine(runs.runs, training.workouts) { r, w -> DoneToday(r.firstOrNull { it.date == day }, w.firstOrNull { it.date == day }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DoneToday())
    val reviewDue = date.flatMapLatest { reviews.dueOn(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun defaultMeal(): Meal = food.defaultMeal()

    fun refreshDate() {
        date.value = LocalDate.now(clock)
    }

    fun logWeight(kg: Double) = viewModelScope.launch { body.logWeight(kg) }
    fun addWater(ml: Int) = viewModelScope.launch { body.addWater(ml) }
    fun undoWater() = viewModelScope.launch { body.undoWater() }
}
