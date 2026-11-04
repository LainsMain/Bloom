package be.lain.bloom.data.cycle

import be.lain.bloom.data.SettingsRepository
import be.lain.bloom.data.db.BloomDatabase
import be.lain.bloom.data.db.CycleDayEntity
import be.lain.bloom.domain.Cycle
import be.lain.bloom.domain.CycleDay
import be.lain.bloom.domain.CycleStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

enum class Symptom(val key: String, val label: String) {
    CRAMPS("cramps", "Cramps"), BLOATING("bloating", "Bloating"), CRAVINGS("cravings", "Cravings"),
    TIRED("tired", "Tired"), HEADACHE("headache", "Headache"), TENDER("tender_breasts", "Tender breasts"),
    MOOD("low_mood", "Low mood"), SPOTTING("spotting", "Spotting");

    companion object {
        fun parse(s: String): Set<Symptom> = s.split(',').mapNotNull { k -> entries.firstOrNull { it.key == k.trim() } }.toSet()
    }
}

data class CycleState(
    val enabled: Boolean,
    val days: List<CycleDayEntity>,
    val stats: CycleStats,
    val today: CycleDay?,
    val periodDays: Set<LocalDate>,
)

@Singleton
class CycleRepository @Inject constructor(
    private val db: BloomDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    private val dao get() = db.cycleDao()

    val enabled: Flow<Boolean> = settings.settings.map { it.cycleTracking }

    fun state(date: LocalDate): Flow<CycleState> = combine(enabled, dao.observeAll()) { on, days ->
        val period = days.filter { it.period }.map { it.date }.toSet()
        val stats = Cycle.stats(period)
        CycleState(on, days, stats, Cycle.on(date, period, stats), period)
    }

    val periodDays: Flow<Set<LocalDate>> = combine(enabled, dao.observeAll()) { on, days ->
        if (on) days.filter { it.period }.map { it.date }.toSet() else emptySet()
    }

    suspend fun day(date: LocalDate) = dao.get(date)

    /** Marks or clears a period day, keeping any symptoms. */
    suspend fun setPeriod(date: LocalDate, on: Boolean, flow: Int? = null) {
        val cur = dao.get(date)
        val next = (cur ?: CycleDayEntity(date = date, period = false, updatedAt = clock.instant()))
            .copy(period = on, flow = if (on) flow ?: cur?.flow else null, updatedAt = clock.instant())
        if (!next.period && next.symptoms.isBlank()) dao.delete(date) else dao.upsert(next)
    }

    suspend fun setSymptoms(date: LocalDate, symptoms: Set<Symptom>) {
        val cur = dao.get(date)
        val next = (cur ?: CycleDayEntity(date = date, period = false, updatedAt = clock.instant()))
            .copy(symptoms = symptoms.joinToString(",") { it.key }, updatedAt = clock.instant())
        if (!next.period && next.symptoms.isBlank()) dao.delete(date) else dao.upsert(next)
    }

    suspend fun restore(date: LocalDate, before: CycleDayEntity?) {
        if (before == null) dao.delete(date) else dao.upsert(before)
    }

    /** Water retention likely on [date] (period just started or due soon); false when tracking is off. */
    suspend fun waterRetention(date: LocalDate): Boolean {
        if (!enabled.first()) return false
        val period = dao.all().filter { it.period }.map { it.date }.toSet()
        return Cycle.on(date, period)?.waterRetention == true
    }

    /** What the coach and the weekly review get to see (information, not instructions). */
    suspend fun summary(today: LocalDate = LocalDate.now(clock)): JsonObject {
        if (!enabled.first()) return buildJsonObject { put("tracking", false); put("note", "Cycle tracking is off. The user can turn it on in Settings → Cycle.") }
        val days = dao.all()
        val period = days.filter { it.period }.map { it.date }.toSet()
        val stats = Cycle.stats(period)
        val c = Cycle.on(today, period, stats)
        return buildJsonObject {
            put("tracking", true)
            if (c == null) put("note", "No period logged yet.")
            c?.let {
                put("cycle_day", it.day); it.phase?.let { p -> put("phase", p.label) }
                put("next_period_expected", it.nextStart.toString()); put("days_until_next", it.daysUntilNext)
                put("late", it.late); put("water_retention_likely_now", it.waterRetention)
            }
            put("average_cycle_days", stats.avgCycle); put("average_period_days", stats.avgPeriod)
            put("averages_are_defaults", !stats.lengthsKnown)
            put("recent_cycle_lengths", buildJsonArray { stats.cycleLengths.forEach { add(JsonPrimitive(it)) } })
            put("period_starts", buildJsonArray { stats.starts.takeLast(6).forEach { add(JsonPrimitive(it.toString())) } })
            put("recent_symptoms", buildJsonArray {
                days.filter { it.symptoms.isNotBlank() && !it.date.isBefore(today.minusDays(35)) }.forEach { d ->
                    add(JsonPrimitive("${d.date}: ${Symptom.parse(d.symptoms).joinToString { s -> s.label.lowercase() }}"))
                }
            })
        }
    }
}
