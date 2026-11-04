package be.lain.bloom.data.training

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import be.lain.bloom.data.ai.TrainingContext
import be.lain.bloom.data.db.BloomDatabase
import be.lain.bloom.data.db.RunPlanEntity
import be.lain.bloom.data.db.RunSessionEntity
import be.lain.bloom.domain.running.Geo
import be.lain.bloom.domain.running.RunPlan
import be.lain.bloom.domain.running.RunProgram
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private val Context.runStore by preferencesDataStore(name = "running")

data class PlanRef(val key: String, val plan: RunPlan)

/** The plan shown at the top of Running and started from Today: the built-in program or one of the user's own. */
data class ActivePlan(val key: String, val plan: RunPlan, val planId: Long?) {
    val isProgram: Boolean get() = planId == null
}

@Singleton
class RunRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: BloomDatabase,
    private val clock: Clock,
) {
    private val dao get() = db.runDao()
    private val json = Json { ignoreUnknownKeys = true }
    private val programKey = intPreferencesKey("program_index")

    val runs = dao.observeRuns()
    fun observeRun(id: Long) = dao.observeRun(id)
    suspend fun run(id: Long) = dao.run(id)
    suspend fun points(id: Long) = dao.points(id)
    suspend fun runsBetween(from: LocalDate, to: LocalDate) = dao.runs(from, to)

    /** Index (0..26) of the next run in the built-in program. */
    val programIndex: Flow<Int> = context.runStore.data.map { it[programKey] ?: 0 }

    suspend fun setProgramIndex(i: Int) = context.runStore.edit { it[programKey] = i.coerceIn(0, RunProgram.runs.lastIndex) }

    private val activeKey = androidx.datastore.preferences.core.longPreferencesKey("active_plan_id")

    /** null = the built-in run-walk program. */
    suspend fun setActivePlan(planId: Long?) = context.runStore.edit { if (planId == null) it.remove(activeKey) else it[activeKey] = planId }

    val plans: Flow<List<Pair<RunPlanEntity, RunPlan>>> = dao.observePlans().map { list ->
        list.mapNotNull { e -> runCatching { e to json.decodeFromString(RunPlan.serializer(), e.planJson) }.getOrNull() }
    }

    val activePlan: Flow<ActivePlan> = kotlinx.coroutines.flow.combine(context.runStore.data, plans) { prefs, list ->
        val id = prefs[activeKey]
        val own = list.firstOrNull { it.first.id == id }
        if (own != null) ActivePlan("plan:${own.first.id}", own.second, own.first.id)
        else (prefs[programKey] ?: 0).let { i -> ActivePlan("program:$i", RunProgram.at(i), null) }
    }

    suspend fun plan(id: Long): RunPlan? = dao.plan(id)?.let { runCatching { json.decodeFromString(RunPlan.serializer(), it.planJson) }.getOrNull() }

    suspend fun savePlan(id: Long?, plan: RunPlan): Long {
        val now = clock.instant()
        val text = json.encodeToString(RunPlan.serializer(), plan)
        return if (id == null) dao.insertPlan(RunPlanEntity(name = plan.name, planJson = text, createdAt = now, updatedAt = now))
        else { dao.updatePlan(RunPlanEntity(id, plan.name, text, dao.plan(id)?.createdAt ?: now, now)); id }
    }

    suspend fun deletePlan(id: Long) {
        dao.deletePlan(id)
        context.runStore.edit { if (it[activeKey] == id) it.remove(activeKey) }
    }

    suspend fun resolve(key: String): RunPlan? = when {
        key.startsWith("program:") -> RunProgram.at(key.removePrefix("program:").toInt())
        key.startsWith("plan:") -> plan(key.removePrefix("plan:").toLong())
        else -> null
    }

    suspend fun createRun(title: String, planKey: String): Long {
        val now = clock.instant()
        return dao.insertRun(RunSessionEntity(date = LocalDate.now(clock), startedAt = now, endedAt = now, title = title, planKey = planKey))
    }

    suspend fun insertPoints(p: List<be.lain.bloom.data.db.RunPointEntity>) = dao.insertPoints(p)
    suspend fun updateRun(r: RunSessionEntity) = dao.updateRun(r)

    suspend fun deleteRun(id: Long) = db.withTransaction { dao.deletePoints(id); dao.deleteRun(id) }

    /** Removes runs that were never finished (e.g. the app was killed mid-run and discarded). */
    suspend fun cleanUnfinished() = db.withTransaction {
        dao.unfinishedIds().forEach { dao.deletePoints(it) }
        dao.deleteUnfinished()
    }

    suspend fun unfinished(): List<Long> = dao.unfinishedIds()
}

class RunningContext @Inject constructor(private val repo: RunRepository) : TrainingContext {
    override suspend fun summary(from: LocalDate, to: LocalDate): JsonObject {
        val list = repo.runsBetween(from, to)
        val idx = repo.programIndex.first()
        val active = repo.activePlan.first()
        return buildJsonObject {
            if (active.isProgram) put("run_plan", "Built-in ${RunProgram.NAME}: next is ${RunProgram.at(idx).name} (${idx + 1}/${RunProgram.runs.size})")
            else put("run_plan", "The user's own interval plan \"${active.plan.name}\": ${active.plan.summary()}, about ${active.plan.totalSeconds / 60} min")
            put("runs", buildJsonArray {
                list.forEach { r ->
                    add(buildJsonObject {
                        put("date", r.date.toString()); put("title", r.title)
                        put("km", (r.distanceM / 10).roundToInt() / 100.0)
                        put("minutes", r.movingSec / 60)
                        put("pace_per_km", Geo.formatPace(Geo.pace(r.movingSec.toDouble(), r.distanceM)))
                        put("plan_completed", r.planCompleted)
                        put("kcal", r.kcal)
                        r.rpe?.let { put("rpe", it) }
                    })
                }
            })
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RunningModule {
    @Binds @IntoSet abstract fun running(c: RunningContext): TrainingContext
}
