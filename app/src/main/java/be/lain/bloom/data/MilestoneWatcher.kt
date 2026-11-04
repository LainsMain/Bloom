package be.lain.bloom.data

import be.lain.bloom.domain.Milestone
import be.lain.bloom.domain.Milestones
import be.lain.bloom.domain.Trend
import be.lain.bloom.domain.WeightPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class MilestoneState(val reached: List<Milestone>, val next: Pair<String, Double>?)

@Singleton
class MilestoneWatcher @Inject constructor(
    private val profiles: ProfileRepository,
    private val body: BodyRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    private fun trendNow(weights: List<WeightEntry>) = Trend.at(weights.map { WeightPoint(it.date, it.kg) }, LocalDate.now(clock))

    val state: Flow<MilestoneState> = combine(profiles.profile, body.weights) { p, w ->
        val t = trendNow(w)
        if (p == null || t == null) MilestoneState(emptyList(), null)
        else MilestoneState(Milestones.reached(p, t).sortedBy { it.rank }, Milestones.next(p, t))
    }

    /** The milestone to celebrate now (the biggest new one), or null. */
    val pending: Flow<Milestone?> = combine(state, settings.settings) { st, s ->
        Milestones.pick(st.reached.filter { it.id !in s.celebratedMilestones })
    }

    /** Marks everything reached so far as celebrated (smaller ones reached at the same time don't pop up later). */
    suspend fun dismiss() = settings.markCelebrated(state.first().reached.map { it.id }.toSet())
}
