package be.lain.bloom.data.review

import be.lain.bloom.data.ProfileRepository
import be.lain.bloom.data.SettingsRepository
import be.lain.bloom.data.db.BloomDatabase
import be.lain.bloom.data.db.PlanVersionEntity
import be.lain.bloom.domain.Adaptive
import be.lain.bloom.domain.Energy
import be.lain.bloom.domain.PlanSource
import be.lain.bloom.domain.Trend
import be.lain.bloom.domain.WeightPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

data class DietBreakState(val start: LocalDate, val end: LocalDate, val today: LocalDate) {
    /** Accepted, but starting tomorrow (food was already logged on the review day). */
    val upcoming: Boolean get() = today.isBefore(start)
    val day: Int get() = ChronoUnit.DAYS.between(start, today).toInt() + 1
    val length: Int get() = ChronoUnit.DAYS.between(start, end).toInt() + 1
    val daysLeft: Int get() = ChronoUnit.DAYS.between(today, end).toInt() + 1
}

/** Optional diet breaks: maintenance for a while, then automatically back to the fat-loss plan. */
@Singleton
class DietBreaks @Inject constructor(
    private val db: BloomDatabase,
    private val settings: SettingsRepository,
    private val profiles: ProfileRepository,
    private val clock: Clock,
) {
    private fun today() = LocalDate.now(clock)

    /** App start and Today can both notice an expired break; only one may restore the plan. */
    private val lock = Mutex()

    val state: Flow<DietBreakState?> = settings.settings.map { s ->
        val a = s.dietBreakStart; val b = s.dietBreakEnd
        if (a == null || b == null) null else DietBreakState(LocalDate.ofEpochDay(a), LocalDate.ofEpochDay(b), today())
    }

    suspend fun current(): DietBreakState? = state.first()?.takeIf { !it.today.isAfter(it.end) }

    suspend fun lastEnd(): LocalDate? = settings.settings.first().lastDietBreakEnd?.let(LocalDate::ofEpochDay)

    /** Saves the maintenance plan and the break window (called when the user accepts it in the review). */
    suspend fun start(b: Adaptive.DietBreak, from: LocalDate, expenditure: Int) {
        val t = b.targets
        val end = from.plusDays(b.days.toLong() - 1)
        db.planDao().insert(
            PlanVersionEntity(
                effectiveDate = from, kcal = t.kcal, proteinG = t.proteinG, carbsG = t.carbsG, fatG = t.fatG, fiberG = t.fiberG,
                expenditureKcal = expenditure, source = PlanSource.WEEKLY_REVIEW,
                reason = "Diet break until ${end.dayOfMonth} ${end.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}: eating at maintenance",
                createdAt = clock.instant(),
            ),
        )
        settings.startDietBreak(from.toEpochDay(), end.toEpochDay())
    }

    /** Ends a finished break (or one the user stops early) and restores the fat-loss targets. */
    suspend fun finishIfDue(): Boolean = lock.withLock {
        val s = state.first() ?: return false
        if (!s.today.isAfter(s.end)) return false
        restore(s, "Diet break over: back to your plan")
        return true
    }

    suspend fun finish(reason: String = "Diet break over: back to your plan") = lock.withLock {
        state.first()?.let { restore(it, reason) }
    }

    /**
     * Restores fat-loss targets from today, or from the break's first day when it hasn't started yet
     * (so a break cancelled the evening it was accepted never kicks in).
     */
    private suspend fun restore(b: DietBreakState, reason: String) {
        val today = today()
        val from = if (b.upcoming) b.start else today
        val profile = profiles.profile.first()
        val plan = db.planDao().activeOn(from)
        if (profile != null && plan != null) {
            val kg = Trend.at(db.bodyDao().weights().map { WeightPoint(it.date, it.kg) }, today) ?: profile.startWeightKg
            val r = Energy.targets(profile, kg, from, expenditureOverride = plan.expenditureKcal.takeIf { it > 0 }?.toDouble())
            val t = r.targets
            db.planDao().insert(
                PlanVersionEntity(
                    effectiveDate = from, kcal = t.kcal, proteinG = t.proteinG, carbsG = t.carbsG, fatG = t.fatG, fiberG = t.fiberG,
                    expenditureKcal = r.expenditure, source = PlanSource.WEEKLY_REVIEW, reason = reason, createdAt = clock.instant(),
                ),
            )
        }
        settings.finishDietBreak(today.toEpochDay())
    }
}
