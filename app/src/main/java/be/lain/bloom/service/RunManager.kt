package be.lain.bloom.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import be.lain.bloom.MainActivity
import be.lain.bloom.R
import be.lain.bloom.data.BodyRepository
import be.lain.bloom.data.SettingsRepository
import be.lain.bloom.data.db.RunPointEntity
import be.lain.bloom.data.training.RunRepository
import be.lain.bloom.di.AppScope
import be.lain.bloom.domain.running.Geo
import be.lain.bloom.domain.running.GeoPoint
import be.lain.bloom.domain.running.IntervalTracker
import be.lain.bloom.domain.running.RunCue
import be.lain.bloom.domain.running.RunPlan
import be.lain.bloom.domain.running.SegKind
import be.lain.bloom.domain.running.Segment
import be.lain.bloom.domain.running.formatDuration
import be.lain.bloom.domain.running.formatMeters
import be.lain.bloom.core.designsystem.RouteLine
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

enum class RunStatus { ACQUIRING, RUNNING, PAUSED, FINISHED }

data class LiveRun(
    val runId: Long? = null,
    val title: String,
    val planKey: String,
    val plan: RunPlan?,
    val status: RunStatus = RunStatus.ACQUIRING,
    val activeMs: Long = 0,
    val distanceM: Double = 0.0,
    val currentPace: Double? = null,
    val segIndex: Int = 0,
    val segment: Segment? = null,
    val nextSegment: Segment? = null,
    /** Remaining seconds (timed segment) or metres (distance segment). */
    val segRemaining: Double? = null,
    val planDone: Boolean = false,
    val lines: List<RouteLine> = emptyList(),
    val position: LatLng? = null,
    val accuracy: Float? = null,
    val kcal: Int = 0,
    val elevationGain: Double = 0.0,
) {
    val avgPace: Double? get() = Geo.pace(activeMs / 1000.0, distanceM)
    val segmentsTotal: Int get() = plan?.segments()?.size ?: 0
}

/** Owns the active run: GPS fixes, distance, interval cues and saving. */
@Singleton
class RunManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: RunRepository,
    private val body: BodyRepository,
    private val settings: SettingsRepository,
    private val speaker: CueSpeaker,
    private val health: be.lain.bloom.data.health.HealthSync,
    private val clock: Clock,
    @AppScope private val scope: CoroutineScope,
) {
    private val _live = MutableStateFlow<LiveRun?>(null)
    val live: StateFlow<LiveRun?> = _live.asStateFlow()

    private var tracker: IntervalTracker? = null
    private var lastPoint: GeoPoint? = null
    private var activeBase = 0L
    private var resumedAt = 0L
    private val buffer = mutableListOf<RunPointEntity>()
    private val samples = mutableListOf<Pair<Double, Double>>() // (activeSec, metres) for pace/splits
    private val altitudes = mutableListOf<Double>()
    private var kmAnnounced = 0
    private var weightKg = 70.0

    private fun activeMs(): Long = activeBase + if (_live.value?.status == RunStatus.RUNNING) SystemClock.elapsedRealtime() - resumedAt else 0

    /** Opens the run screen state and starts GPS warm-up. */
    suspend fun prepare(title: String, planKey: String, plan: RunPlan?) {
        if (_live.value != null && _live.value?.status != RunStatus.FINISHED) return
        speaker.enabled = settings.settings.first().voiceCues
        speaker.warmUp()
        weightKg = body.weights.first().lastOrNull()?.kg ?: 70.0
        tracker = null; lastPoint = null; activeBase = 0; buffer.clear(); samples.clear(); altitudes.clear(); kmAnnounced = 0
        val segs = plan?.segments().orEmpty()
        _live.value = LiveRun(title = title, planKey = planKey, plan = plan, segment = segs.firstOrNull(), nextSegment = segs.getOrNull(1))
        context.startForegroundService(Intent(context, RunService::class.java))
    }

    fun begin() = scope.launch {
        val l = _live.value ?: return@launch
        if (l.status != RunStatus.ACQUIRING) return@launch
        val id = repo.createRun(l.title, l.planKey)
        tracker = l.plan?.let { IntervalTracker(it.segments()) }
        resumedAt = SystemClock.elapsedRealtime()
        _live.value = l.copy(runId = id, status = RunStatus.RUNNING)
        speaker.speak("Let's go!")
        speaker.buzz(Buzz.START)
        tracker?.begin()?.let(::handle)
    }

    fun togglePause() {
        val l = _live.value ?: return
        when (l.status) {
            RunStatus.RUNNING -> {
                activeBase = activeMs()
                lastPoint = null // don't connect a line across the pause
                _live.value = l.copy(status = RunStatus.PAUSED, activeMs = activeBase, lines = l.lines + RouteLine(l.segment?.kind?.name, emptyList()))
                speaker.speak("Paused"); flush()
            }
            RunStatus.PAUSED -> {
                resumedAt = SystemClock.elapsedRealtime()
                _live.value = l.copy(status = RunStatus.RUNNING)
                speaker.speak("Resuming")
            }
            else -> Unit
        }
    }

    fun onLocation(loc: Location) {
        val l = _live.value ?: return
        val p = GeoPoint(loc.time, loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else null, loc.accuracy)
        val pos = LatLng(loc.latitude, loc.longitude)
        if (l.status != RunStatus.RUNNING) {
            _live.value = l.copy(position = pos, accuracy = loc.accuracy)
            return
        }
        val add = Geo.accept(lastPoint, p) ?: run { _live.value = l.copy(accuracy = loc.accuracy); return }
        lastPoint = p
        val dist = l.distanceM + add
        val act = activeMs()
        samples += act / 1000.0 to dist
        p.alt?.let { altitudes += it }
        val kind = tracker?.current?.kind?.name
        buffer += RunPointEntity(
            runId = l.runId ?: return, t = p.t, lat = p.lat, lon = p.lon, alt = p.alt, accuracy = p.accuracy,
            activeSec = act / 1000.0, distanceM = dist, segmentIndex = tracker?.index ?: 0, segmentKind = kind,
        )
        if (buffer.size >= 10) flush()
        // Extend the current line, or start a new one when the segment kind changes.
        val lines = l.lines.toMutableList()
        val last = lines.lastOrNull()
        if (last == null || last.kind != kind) {
            val start = last?.points?.lastOrNull()?.takeIf { last.points.isNotEmpty() }
            lines += RouteLine(kind, listOfNotNull(start, pos))
        } else lines[lines.lastIndex] = last.copy(points = last.points + pos)
        _live.value = l.copy(distanceM = dist, lines = lines, position = pos, accuracy = loc.accuracy, currentPace = recentPace(),
            kcal = Geo.kcal(weightKg, dist / 1000.0, Geo.pace(act / 1000.0, dist)), elevationGain = Geo.elevationGain(altitudes))
        val km = (dist / 1000).toInt()
        if (km > kmAnnounced) {
            kmAnnounced = km
            val sec = (act / 1000).toInt()
            speaker.speak("$km kilometre${if (km > 1) "s" else ""}. Time ${sec / 60} minutes ${sec % 60}. Average pace ${Geo.formatPace(Geo.pace(act / 1000.0, dist)).replace(":", " ")} per kilometre.", flush = false)
        }
    }

    /** Pace over the last ~30 s of movement. */
    private fun recentPace(): Double? {
        if (samples.size < 2) return null
        val (tEnd, dEnd) = samples.last()
        val start = samples.lastOrNull { tEnd - it.first >= 30 } ?: samples.first()
        return Geo.pace(tEnd - start.first, dEnd - start.second)
    }

    fun tick() {
        val l = _live.value ?: return
        if (l.status != RunStatus.RUNNING) return
        val act = activeMs()
        val t = tracker
        val cues = t?.update(act, l.distanceM).orEmpty()
        _live.update {
            it?.copy(
                activeMs = act, segIndex = t?.index ?: 0, segment = t?.current, nextSegment = t?.segments?.getOrNull((t.index) + 1),
                segRemaining = t?.remaining(act, l.distanceM), planDone = t?.done ?: false,
            )
        }
        handle(cues)
    }

    private fun handle(cues: List<RunCue>) {
        cues.forEach { c ->
            when (c) {
                is RunCue.Segment -> {
                    val s = c.segment
                    val amount = s.seconds?.let { spokenDuration(it) } ?: s.meters?.let { formatMeters(it).replace("km", "kilometres").replace(" m", " metres") } ?: ""
                    speaker.speak("${s.kind.spoken} for $amount.")
                    speaker.buzz(if (s.kind == SegKind.RUN) Buzz.START else Buzz.TICK)
                }
                RunCue.Halfway -> speaker.speak("Halfway there.", flush = false)
                is RunCue.Count -> { speaker.speak("${c.n}"); speaker.buzz(Buzz.TICK) }
                RunCue.PlanDone -> {
                    speaker.speak("That's the plan done! Brilliant work. Finish whenever you're ready.")
                    speaker.buzz(Buzz.DONE)
                }
            }
        }
    }

    private fun spokenDuration(sec: Int) = when {
        sec < 60 -> "$sec seconds"
        sec % 60 == 0 -> "${sec / 60} minute${if (sec >= 120) "s" else ""}"
        else -> "${sec / 60} minute${if (sec >= 120) "s" else ""} ${sec % 60} seconds"
    }

    private fun flush() {
        if (buffer.isEmpty()) return
        val copy = buffer.toList(); buffer.clear()
        scope.launch { repo.insertPoints(copy) }
    }

    fun finish() {
        val l = _live.value ?: return
        if (l.status == RunStatus.ACQUIRING) { discard(); return }
        if (l.status == RunStatus.RUNNING) activeBase = activeMs()
        flush()
        _live.value = l.copy(status = RunStatus.FINISHED, activeMs = activeBase)
        speaker.speak("Run finished. Well done!")
        context.stopService(Intent(context, RunService::class.java))
    }

    /** Saves the finished run. Returns its id. */
    suspend fun save(rpe: Int?, notes: String): Long? {
        val l = _live.value ?: return null
        val id = l.runId ?: return null
        val run = repo.run(id) ?: return null
        val now = clock.instant()
        repo.updateRun(
            run.copy(
                endedAt = now, distanceM = l.distanceM, movingSec = (l.activeMs / 1000).toInt(),
                elapsedSec = ((now.toEpochMilli() - run.startedAt.toEpochMilli()) / 1000).toInt(),
                elevationGainM = l.elevationGain, kcal = l.kcal, planCompleted = l.planDone, finished = true, rpe = rpe, notes = notes.trim(),
            ),
        )
        if (l.planKey.startsWith("program:") && l.planDone) {
            val idx = l.planKey.removePrefix("program:").toInt()
            if (idx >= repo.programIndex.first()) repo.setProgramIndex(idx + 1)
        }
        _live.value = null
        scope.launch {
            health.writeRun(id)?.let { hcId -> repo.run(id)?.let { repo.updateRun(it.copy(healthConnectId = hcId)) } }
            health.syncSoon()
        }
        return id
    }

    fun discard() {
        val id = _live.value?.runId
        _live.value = null
        buffer.clear()
        context.stopService(Intent(context, RunService::class.java))
        if (id != null) scope.launch { repo.deleteRun(id) }
    }

    fun statusLine(l: LiveRun): String = when (l.status) {
        RunStatus.ACQUIRING -> "Waiting for GPS…"
        RunStatus.PAUSED -> "Paused · ${formatMeters(l.distanceM.roundToInt())}"
        RunStatus.FINISHED -> "Run finished"
        RunStatus.RUNNING -> "${Geo.formatClock((l.activeMs / 1000).toInt())} · ${"%.2f".format(l.distanceM / 1000)} km" +
            (l.segment?.let { s -> " · ${s.kind.label}" + (l.segRemaining?.let { r -> if (s.seconds != null) " ${formatDuration(r.roundToInt())}" else " ${formatMeters(r.roundToInt())}" } ?: "") } ?: "")
    }
}

@AndroidEntryPoint
class RunService : LifecycleService() {
    @Inject lateinit var manager: RunManager
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.locations.forEach(manager::onLocation) }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Active run", NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bloom:run")
            .apply { acquire(4 * 60 * 60 * 1000L) }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PAUSE -> manager.togglePause()
            ACTION_FINISH -> manager.finish()
        }
        startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        if (loop == null) {
            runCatching {
                client.requestLocationUpdates(
                    LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).setMinUpdateIntervalMillis(500).build(),
                    callback, Looper.getMainLooper(),
                )
            }
            loop = lifecycleScope.launch {
                var lastNotify = 0L
                while (isActive) {
                    manager.tick()
                    if (manager.live.value == null) { stopSelf(); break }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotify > 1000) {
                        getSystemService(NotificationManager::class.java).notify(ID, notification()); lastNotify = now
                    }
                    delay(250)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val l = manager.live.value
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String, label: String) = NotificationCompat.Action(0, label,
            PendingIntent.getService(this, a.hashCode(), Intent(this, RunService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE))
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(l?.title ?: "Run")
            .setContentText(l?.let(manager::statusLine) ?: "")
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .apply {
                if (l?.status == RunStatus.RUNNING || l?.status == RunStatus.PAUSED) {
                    addAction(action(ACTION_PAUSE, if (l.status == RunStatus.PAUSED) "Resume" else "Pause"))
                    addAction(action(ACTION_FINISH, "Finish"))
                }
            }
            .build()
    }

    override fun onDestroy() {
        runCatching { client.removeLocationUpdates(callback) }
        loop?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "run"
        const val ID = 42
        const val ACTION_PAUSE = "be.lain.bloom.run.PAUSE"
        const val ACTION_FINISH = "be.lain.bloom.run.FINISH"
    }
}
