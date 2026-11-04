package be.lain.bloom.data.training

import androidx.room.withTransaction
import be.lain.bloom.data.BodyRepository
import be.lain.bloom.data.db.BloomDatabase
import be.lain.bloom.data.db.RunPointEntity
import be.lain.bloom.data.health.HealthSync
import be.lain.bloom.domain.running.Track
import be.lain.bloom.domain.running.TrackPoint
import kotlinx.coroutines.flow.first
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max

fun RunPointEntity.toTrack() = TrackPoint(t, lat, lon, alt, pressureAlt, accuracy, activeSec, segmentIndex)

/**
 * One-time fix for runs recorded by Bloom 1.2.1 and earlier, whose distance filter dropped almost every
 * GPS step (a 39-minute run showed 0.15 km). The full route was always saved, so distance, splits,
 * energy and climb are recomputed from it. Runs without a route are flagged as unreliable instead.
 */
@Singleton
class RunRepair @Inject constructor(
    private val db: BloomDatabase,
    private val runs: RunRepository,
    private val body: BodyRepository,
    private val health: dagger.Lazy<HealthSync>,
    private val clock: Clock,
) {
    suspend fun runOnce() {
        if (runs.repairVersion() >= VERSION) return
        val dao = db.runDao()
        val weights = body.weights.first().sortedBy { it.date }
        val now = clock.instant()
        val rewrite = mutableListOf<Long>()
        dao.finishedRuns().forEach { r ->
            val pts = dao.points(r.id)
            if (pts.size < 2) {
                if (r.movingSec >= 60 && !r.distanceUnreliable) dao.updateRun(r.copy(distanceUnreliable = true))
                return@forEach
            }
            val weight = weights.lastOrNull { it.date <= r.date }?.kg ?: weights.firstOrNull()?.kg ?: 70.0
            val s = Track.compute(pts.map { it.toTrack() }, weight)
            val distChanged = abs(s.distanceM - r.distanceM) > max(20.0, r.distanceM * 0.02)
            val changed = distChanged || abs(s.climbM - r.elevationGainM) >= 5 || abs(s.kcal - r.kcal) >= 10
            if (!changed) return@forEach
            db.withTransaction {
                dao.updatePoints(pts.mapIndexed { i, p -> p.copy(distanceM = s.cumulative[i]) })
                dao.updateRun(
                    r.copy(
                        distanceM = s.distanceM, elevationGainM = s.climbM, kcal = s.kcal, recalculatedAt = now,
                        originalDistanceM = if (distChanged) r.originalDistanceM ?: r.distanceM else r.originalDistanceM,
                    ),
                )
            }
            if (r.healthConnectId != null) rewrite += r.id
        }
        runs.setRepairVersion(VERSION)
        // Same client record ids with a higher version replace the wrong records in Health Connect.
        rewrite.forEach { id ->
            runCatching {
                health.get().writeRun(id, version = now.toEpochMilli())?.let { hcId ->
                    dao.run(id)?.let { dao.updateRun(it.copy(healthConnectId = hcId)) }
                }
            }
        }
    }

    companion object {
        const val VERSION = 1
    }
}
