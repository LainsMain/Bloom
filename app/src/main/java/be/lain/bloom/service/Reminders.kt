package be.lain.bloom.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import be.lain.bloom.MainActivity
import be.lain.bloom.R
import be.lain.bloom.data.AppSettings
import be.lain.bloom.data.BodyRepository
import be.lain.bloom.data.ProfileRepository
import be.lain.bloom.data.Reminder
import be.lain.bloom.data.SettingsRepository
import be.lain.bloom.data.enabled
import be.lain.bloom.data.food.FoodRepository
import be.lain.bloom.data.review.ReviewRepository
import be.lain.bloom.data.timeOf
import be.lain.bloom.data.training.RunRepository
import be.lain.bloom.data.training.TrainingRepository
import be.lain.bloom.domain.DayStatus
import be.lain.bloom.feature.today.TrainingKind
import be.lain.bloom.feature.today.trainingOn
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

/** Where a notification tap should land; consumed once by the main shell. */
@Singleton
class DeepLinks @Inject constructor() {
    val pending = MutableStateFlow<String?>(null)

    companion object {
        const val EXTRA = "be.lain.bloom.OPEN"
        const val REVIEW = "review"
        const val WEIGH_IN = "weigh_in"
        const val FOOD = "food"
        const val TRAIN = "train"
        const val COACH = "coach"
    }
}

/** Next time a reminder should fire, strictly after [after]. Pure, for tests. */
fun nextReminderTime(r: Reminder, s: AppSettings, after: LocalDateTime): LocalDateTime {
    val time = LocalTime.of(s.timeOf(r) / 60, s.timeOf(r) % 60)
    return if (r == Reminder.REVIEW) {
        var d = after.toLocalDate().with(TemporalAdjusters.nextOrSame(s.reviewDay)).atTime(time)
        if (!d.isAfter(after)) d = d.plusWeeks(1)
        d
    } else {
        var d = after.toLocalDate().atTime(time)
        if (!d.isAfter(after)) d = d.plusDays(1)
        d
    }
}

@Singleton
class Reminders @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    /** Before Android 13 there's no runtime permission; only the per-app notification switch. */
    fun canNotify(): Boolean =
        (android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    suspend fun rescheduleAll() {
        val s = settings.settings.first()
        Reminder.entries.forEach { schedule(it, s) }
    }

    fun schedule(r: Reminder, s: AppSettings) {
        val wm = WorkManager.getInstance(context)
        val name = "reminder_${r.name.lowercase()}"
        if (!s.enabled(r)) {
            wm.cancelUniqueWork(name)
            return
        }
        val now = LocalDateTime.now(clock)
        val next = nextReminderTime(r, s, now)
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(Duration.between(now, next))
            .setInputData(workDataOf(ReminderWorker.TYPE to r.name))
            .addTag("reminder")
            .build()
        wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
    }

    /** A one-off nudge the coach scheduled at the user's request. */
    fun scheduleCustom(actionId: Long, at: LocalDateTime, message: String) {
        val delay = Duration.between(LocalDateTime.now(clock), at).coerceAtLeast(Duration.ZERO)
        val req = OneTimeWorkRequestBuilder<CoachReminderWorker>()
            .setInitialDelay(delay)
            .setInputData(workDataOf(CoachReminderWorker.MESSAGE to message, CoachReminderWorker.ID to actionId))
            .addTag("coach_reminder")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("coach_reminder_$actionId", ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelCustom(workName: String) { WorkManager.getInstance(context).cancelUniqueWork(workName) }

    @android.annotation.SuppressLint("MissingPermission") // canNotify() checks POST_NOTIFICATIONS first.
    fun postCustom(id: Long, text: String) {
        if (!canNotify()) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(DeepLinks.EXTRA, DeepLinks.COACH)
        }
        val pi = PendingIntent.getActivity(context, (5000 + id % 1000).toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("From your coach")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify((NOTIFY_BASE + 100 + id % 1000).toInt(), n) }
    }

    @android.annotation.SuppressLint("MissingPermission") // canNotify() checks POST_NOTIFICATIONS first.
    fun post(r: Reminder, title: String, text: String) {
        if (!canNotify()) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Weekly review, weigh-in, meal and training reminders"
        })
        val open = when (r) {
            Reminder.REVIEW -> DeepLinks.REVIEW
            Reminder.WEIGH_IN -> DeepLinks.WEIGH_IN
            Reminder.MEALS -> DeepLinks.FOOD
            Reminder.TRAINING -> DeepLinks.TRAIN
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(DeepLinks.EXTRA, open)
        }
        val pi = PendingIntent.getActivity(context, r.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFY_BASE + r.ordinal, n) }
    }

    companion object {
        const val CHANNEL = "reminders"
        private const val NOTIFY_BASE = 4100
    }
}

/** Fires one reminder (only if it's still relevant) and schedules the next one. */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reminders: Reminders,
    private val settings: SettingsRepository,
    private val reviews: ReviewRepository,
    private val body: BodyRepository,
    private val food: FoodRepository,
    private val profiles: ProfileRepository,
    private val training: TrainingRepository,
    private val runs: RunRepository,
    private val clock: Clock,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val r = inputData.getString(TYPE)?.let { runCatching { Reminder.valueOf(it) }.getOrNull() } ?: return Result.success()
        val s = settings.settings.first()
        if (s.enabled(r)) runCatching { message(r)?.let { (title, text) -> reminders.post(r, title, text) } }
        reminders.schedule(r, settings.settings.first())
        return Result.success()
    }

    private suspend fun message(r: Reminder): Pair<String, String>? {
        val today = LocalDate.now(clock)
        val profile = profiles.profile.first() ?: return null
        return when (r) {
            Reminder.REVIEW ->
                if (reviews.isDue(today)) "Your weekly review is ready" to "Three minutes to see how your week went and fine-tune your plan." else null
            Reminder.WEIGH_IN ->
                if (body.weights.first().none { it.date == today }) "Morning weigh-in" to "Step on the scale before breakfast. The trend smooths out daily swings." else null
            Reminder.MEALS -> {
                if (food.dayStatuses(today, today).firstOrNull()?.status == DayStatus.COMPLETE) return null
                val entries = food.range(today, today)
                if (entries.isEmpty()) "Nothing logged today yet" to "Add what you ate today. Even a rough log helps your weekly review."
                else "Anything else to log?" to "Add any snacks or drinks, then mark today complete in your diary."
            }
            Reminder.TRAINING -> when (profile.trainingOn(today.dayOfWeek)) {
                TrainingKind.STRENGTH -> {
                    if (training.workoutsBetween(today, today).isNotEmpty()) return null
                    val next = training.status.first().next
                    "Strength day" to "${next.name}: ${next.focus}. About ${next.estimateMinutes()} minutes at home."
                }
                TrainingKind.RUN -> {
                    if (runs.runsBetween(today, today).isNotEmpty()) return null
                    val plan = runs.activePlan.first().plan
                    "Run day" to "${plan.name} today, about ${plan.totalSeconds / 60} minutes. Conversation pace is perfect."
                }
                TrainingKind.REST -> null
            }
        }
    }

    companion object { const val TYPE = "type" }
}

@HiltWorker
class CoachReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reminders: Reminders,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val text = inputData.getString(MESSAGE) ?: return Result.success()
        reminders.postCustom(inputData.getLong(ID, 0), text)
        return Result.success()
    }

    companion object {
        const val MESSAGE = "message"
        const val ID = "id"
    }
}
