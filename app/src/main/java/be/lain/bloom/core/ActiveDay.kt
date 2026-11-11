package be.lain.bloom.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The day the whole app is working on: today, unless another day was picked (in the food diary or
 * on Today). Food, water, weigh-ins, workouts and runs then count for that day, and Today shows it.
 * A picked day is forgotten when the app has been away for a few hours, so the next morning starts
 * on the right day again.
 */
@Singleton
class ActiveDay @Inject constructor(
    private val clock: Clock,
    @be.lain.bloom.di.AppScope scope: CoroutineScope,
) {
    private val picked = MutableStateFlow<LocalDate?>(null)
    private val today = MutableStateFlow(LocalDate.now(clock))
    private var lastSeenMs = clock.millis()

    val date: StateFlow<LocalDate> = combine(picked, today) { p, t -> p ?: t }
        .stateIn(scope, SharingStarted.Eagerly, LocalDate.now(clock))

    /** True while a day other than today is picked. */
    val isOtherDay: StateFlow<Boolean> = picked.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    fun current(): LocalDate = picked.value ?: LocalDate.now(clock)
    fun realToday(): LocalDate = LocalDate.now(clock)

    fun select(day: LocalDate) {
        val t = realToday()
        today.value = t
        picked.value = if (day == t) null else day
        lastSeenMs = clock.millis()
    }

    fun shift(days: Long) = select(current().plusDays(days))

    fun backToToday() = select(realToday())

    /** The app is in use: catch a new calendar day, and drop a picked day after [AWAY_MS] away. */
    fun refresh() {
        val now = clock.millis()
        if (picked.value != null && now - lastSeenMs > AWAY_MS) picked.value = null
        today.value = realToday()
        lastSeenMs = now
    }

    /** The app went to the background. */
    fun markSeen() { lastSeenMs = clock.millis() }

    companion object {
        const val AWAY_MS = 3 * 60 * 60 * 1000L
    }
}
