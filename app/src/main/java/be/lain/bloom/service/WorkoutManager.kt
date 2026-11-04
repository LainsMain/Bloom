package be.lain.bloom.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import be.lain.bloom.MainActivity
import be.lain.bloom.R
import be.lain.bloom.data.BodyRepository
import be.lain.bloom.data.SettingsRepository
import be.lain.bloom.data.db.WorkoutSessionEntity
import be.lain.bloom.data.training.TrainingRepository
import be.lain.bloom.domain.training.Cue
import be.lain.bloom.domain.training.Effort
import be.lain.bloom.domain.training.Exercises
import be.lain.bloom.domain.training.PlayerState
import be.lain.bloom.domain.training.Progression
import be.lain.bloom.domain.training.Session
import be.lain.bloom.domain.training.StepType
import be.lain.bloom.domain.training.WorkoutEngine
import be.lain.bloom.domain.training.WorkoutPlan
import be.lain.bloom.domain.training.WorkoutSpeech
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

data class ActiveWorkout(
    val phase: Int,
    val session: Session,
    val startedAt: Instant,
    val player: PlayerState,
)

/** Owns the running workout: engine, cues and saving. The foreground service keeps it ticking. */
@Singleton
class WorkoutManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val speaker: CueSpeaker,
    private val training: TrainingRepository,
    private val body: BodyRepository,
    private val settings: SettingsRepository,
    private val health: be.lain.bloom.data.health.HealthSync,
    @be.lain.bloom.di.AppScope private val scope: kotlinx.coroutines.CoroutineScope,
    private val clock: Clock,
) {
    private val engine = WorkoutEngine { SystemClock.elapsedRealtime() }
    private val _active = MutableStateFlow<ActiveWorkout?>(null)
    val active: StateFlow<ActiveWorkout?> = _active.asStateFlow()
    @Volatile var pauseBeforeExercise = false

    suspend fun start(phase: Int, session: Session) {
        training.ensureStarted()
        val prefs = settings.settings.first()
        speaker.enabled = prefs.voiceCues
        pauseBeforeExercise = prefs.pauseBeforeExercise
        speaker.warmUp()
        val choices = training.choices.first()
        val steps = WorkoutPlan.steps(session, phase, training.choicesFor(choices))
        val cues = engine.start(steps)
        if (pauseBeforeExercise) engine.setPaused(true)
        _active.value = ActiveWorkout(phase, session, clock.instant(), engine.state!!)
        handle(cues)
        context.startForegroundService(Intent(context, WorkoutService::class.java))
    }

    fun tick() = act { engine.tick() }
    fun complete(reps: Int? = null) = act { engine.complete(reps) }
    fun skip() = act { engine.skip() }
    fun previous() = act { engine.previous() }
    fun addTime(sec: Int) = act { engine.addTime(sec); emptyList() }
    fun rate(stepIndex: Int, effort: Effort) = act { engine.rateEffort(stepIndex, effort); emptyList() }
    fun togglePause() = act {
        val paused = !(engine.state?.paused ?: false)
        engine.setPaused(paused)
        if (paused) speaker.stopSpeaking()
        emptyList()
    }
    fun finishEarly() = act { engine.finishEarly() }

    private fun act(f: () -> List<Cue>) {
        val a = _active.value ?: return
        val cues = f()
        // Optionally hold the clock at each new exercise so the demo can be watched first.
        if (pauseBeforeExercise && cues.any { it is Cue.Enter && it.step.type == StepType.GET_READY }) engine.setPaused(true)
        engine.state?.let { _active.value = a.copy(player = it) }
        handle(cues)
    }

    private fun handle(cues: List<Cue>) {
        cues.forEach { c ->
            when (c) {
                is Cue.Enter -> {
                    speaker.speak(WorkoutSpeech.enter(c.step))
                    speaker.buzz(if (c.step.type == StepType.WORK) Buzz.START else Buzz.TICK)
                }
                is Cue.Count -> { speaker.speak("${c.n}"); speaker.buzz(Buzz.TICK) }
                Cue.Finished -> { speaker.speak("Workout complete. Amazing work!"); speaker.buzz(Buzz.DONE) }
            }
        }
    }

    fun activeSeconds(): Int = (engine.activeMs() / 1000).toInt()

    /** Saves the finished workout and clears the session. Returns the new workout id. */
    suspend fun save(rpe: Int?, notes: String): Long? {
        val a = _active.value ?: return null
        val p = a.player
        val work = p.steps.withIndex().filter { it.value.type == StepType.WORK && it.value.block.let { b -> b.name == "MAIN" || b.name == "CORE" } }
        val done = work.count { (i, _) -> p.results[i]?.skipped == false }
        val weight = body.weights.first().lastOrNull()?.kg ?: 70.0
        val seconds = activeSeconds()
        val id = training.saveWorkout(
            WorkoutSessionEntity(
                date = LocalDate.now(clock), startedAt = a.startedAt, endedAt = clock.instant(), phase = a.phase,
                sessionId = a.session.id, name = a.session.name, activeSeconds = seconds,
                kcal = Progression.kcal(weight, seconds / 60.0), setsDone = done, setsPlanned = work.size, rpe = rpe, notes = notes.trim(),
            ),
            p.results.values,
        )
        discard()
        scope.launch {
            health.writeWorkout(id)?.let { hcId -> training.workout(id)?.let { training.updateWorkout(it.copy(healthConnectId = hcId)) } }
        }
        return id
    }

    fun discard() {
        _active.value = null
        speaker.stopSpeaking()
        context.stopService(Intent(context, WorkoutService::class.java))
    }

    fun summaryLine(p: PlayerState): String {
        val step = p.current
        val ex = Exercises[step.exerciseId]
        return when {
            p.finished -> "Workout complete"
            step.type == StepType.REST -> "Rest · next ${ex.name}"
            step.type == StepType.GET_READY -> "Get ready · ${ex.name}"
            step.timed -> "${ex.name}${step.side?.let { " ($it)" } ?: ""}"
            else -> "${ex.name} · set ${step.setIndex}/${step.setCount}"
        }
    }

    @Suppress("MemberVisibilityCanBePrivate")
    fun remainingText(p: PlayerState): String =
        if (p.current.timed) "${(p.remainingMs / 1000.0).roundToInt()} s left" else "Tap Done when finished"
}

@AndroidEntryPoint
class WorkoutService : LifecycleService() {
    @Inject lateinit var manager: WorkoutManager
    private var loop: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        // Keep the CPU awake so timers and voice cues run with the screen off.
        wakeLock = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "bloom:workout").apply { acquire(3 * 60 * 60 * 1000L) }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Active workout", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PAUSE -> manager.togglePause()
            ACTION_SKIP -> manager.skip()
        }
        // The specialUse type exists from Android 14; earlier versions take no type here.
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, notification())
        if (loop == null) {
            loop = lifecycleScope.launch {
                var lastNotified = 0L
                while (isActive) {
                    manager.tick()
                    val a = manager.active.value
                    if (a == null) { stopSelf(); break }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotified > 1000) {
                        getSystemService(NotificationManager::class.java).notify(ID, notification())
                        lastNotified = now
                    }
                    delay(200)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val a = manager.active.value
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        fun action(act: String, label: String) = NotificationCompat.Action(0, label,
            PendingIntent.getService(this, act.hashCode(), Intent(this, WorkoutService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE))
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(a?.let { manager.summaryLine(it.player) } ?: "Workout")
            .setContentText(a?.let { if (it.player.paused) "Paused" else manager.remainingText(it.player) } ?: "")
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (a != null && !a.player.finished) {
            b.addAction(action(ACTION_PAUSE, if (a.player.paused) "Resume" else "Pause"))
            b.addAction(action(ACTION_SKIP, "Skip"))
        }
        return b.build()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        loop?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "workout"
        const val ID = 41
        const val ACTION_PAUSE = "be.lain.bloom.workout.PAUSE"
        const val ACTION_SKIP = "be.lain.bloom.workout.SKIP"
    }
}
