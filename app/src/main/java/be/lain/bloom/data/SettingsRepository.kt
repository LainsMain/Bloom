package be.lain.bloom.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** Fallback palettes used when dynamic colour is off (or unavailable). */
enum class Palette(val label: String) { ROSE("Rose"), LILAC("Lilac"), SAGE("Sage"), PEACH("Peach") }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val palette: Palette = Palette.ROSE,
    val waterGoalMl: Int = 2000,
    val waterServingMl: Int = 250,
    /** Strength program phase (0-based) and the day it started; null = not started yet. */
    val programPhase: Int = 0,
    val phaseStartEpochDay: Long? = null,
    val voiceCues: Boolean = true,
    /** Stop the clock before every new exercise so the demo can be watched first. */
    val pauseBeforeExercise: Boolean = false,
    /** Also write weigh-ins to Health Connect. */
    val shareWeight: Boolean = false,
    val stepGoal: Int = 8000,
    /** Day the weekly review becomes due. */
    val reviewDay: java.time.DayOfWeek = java.time.DayOfWeek.MONDAY,
    /** Reminders; times are minutes after midnight. */
    val reviewReminder: Boolean = true,
    val reviewTime: Int = 9 * 60,
    val weighInReminder: Boolean = false,
    val weighInTime: Int = 7 * 60 + 30,
    val mealReminder: Boolean = false,
    val mealTime: Int = 20 * 60,
    val trainingReminder: Boolean = false,
    val trainingTime: Int = 9 * 60,
    /** Ask for the fingerprint / screen lock when opening the app. */
    val appLock: Boolean = false,
    /** Look for a newer release on GitHub when the app starts. */
    val updateCheck: Boolean = true,
    /** A release the user chose to skip. */
    val skippedVersion: String? = null,
    val lastBackupAt: Long? = null,
)

enum class Reminder(val label: String, val description: String) {
    REVIEW("Weekly review", "On your review day"),
    WEIGH_IN("Weigh-in", "Every morning, unless you already weighed in"),
    MEALS("Log your meals", "In the evening, if the day isn't logged yet"),
    TRAINING("Training", "On your strength and run days"),
}

fun AppSettings.enabled(r: Reminder): Boolean = when (r) {
    Reminder.REVIEW -> reviewReminder
    Reminder.WEIGH_IN -> weighInReminder
    Reminder.MEALS -> mealReminder
    Reminder.TRAINING -> trainingReminder
}

fun AppSettings.timeOf(r: Reminder): Int = when (r) {
    Reminder.REVIEW -> reviewTime
    Reminder.WEIGH_IN -> weighInTime
    Reminder.MEALS -> mealTime
    Reminder.TRAINING -> trainingTime
}

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val palette = stringPreferencesKey("palette")
        val waterGoal = intPreferencesKey("water_goal_ml")
        val waterServing = intPreferencesKey("water_serving_ml")
        val phase = intPreferencesKey("program_phase")
        val phaseStart = androidx.datastore.preferences.core.longPreferencesKey("phase_start")
        val voice = booleanPreferencesKey("voice_cues")
        val pauseBefore = booleanPreferencesKey("pause_before_exercise")
        val shareWeight = booleanPreferencesKey("share_weight")
        val stepGoal = intPreferencesKey("step_goal")
        val reviewDay = stringPreferencesKey("review_day")
        val appLock = booleanPreferencesKey("app_lock")
        val updateCheck = booleanPreferencesKey("update_check")
        val skippedVersion = stringPreferencesKey("skipped_version")
        val lastBackup = androidx.datastore.preferences.core.longPreferencesKey("last_backup_at")
        fun on(r: Reminder) = booleanPreferencesKey("reminder_${r.name.lowercase()}")
        fun time(r: Reminder) = intPreferencesKey("reminder_${r.name.lowercase()}_time")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings() = AppSettings(
        themeMode = this[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        dynamicColor = this[Keys.dynamic] ?: true,
        palette = this[Keys.palette]?.let { runCatching { Palette.valueOf(it) }.getOrNull() } ?: Palette.ROSE,
        waterGoalMl = this[Keys.waterGoal] ?: 2000,
        waterServingMl = this[Keys.waterServing] ?: 250,
        programPhase = this[Keys.phase] ?: 0,
        phaseStartEpochDay = this[Keys.phaseStart],
        voiceCues = this[Keys.voice] ?: true,
        pauseBeforeExercise = this[Keys.pauseBefore] ?: false,
        shareWeight = this[Keys.shareWeight] ?: false,
        stepGoal = this[Keys.stepGoal] ?: 8000,
        reviewDay = this[Keys.reviewDay]?.let { runCatching { java.time.DayOfWeek.valueOf(it) }.getOrNull() } ?: java.time.DayOfWeek.MONDAY,
        reviewReminder = this[Keys.on(Reminder.REVIEW)] ?: true,
        reviewTime = this[Keys.time(Reminder.REVIEW)] ?: (9 * 60),
        weighInReminder = this[Keys.on(Reminder.WEIGH_IN)] ?: false,
        weighInTime = this[Keys.time(Reminder.WEIGH_IN)] ?: (7 * 60 + 30),
        mealReminder = this[Keys.on(Reminder.MEALS)] ?: false,
        mealTime = this[Keys.time(Reminder.MEALS)] ?: (20 * 60),
        trainingReminder = this[Keys.on(Reminder.TRAINING)] ?: false,
        trainingTime = this[Keys.time(Reminder.TRAINING)] ?: (9 * 60),
        appLock = this[Keys.appLock] ?: false,
        updateCheck = this[Keys.updateCheck] ?: true,
        skippedVersion = this[Keys.skippedVersion],
        lastBackupAt = this[Keys.lastBackup],
    )

    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.theme] = mode.name }
    suspend fun setDynamicColor(on: Boolean) = context.dataStore.edit { it[Keys.dynamic] = on }
    suspend fun setPalette(p: Palette) = context.dataStore.edit { it[Keys.palette] = p.name }
    suspend fun setWaterGoal(ml: Int) = context.dataStore.edit { it[Keys.waterGoal] = ml.coerceIn(500, 6000) }
    suspend fun setPhase(phase: Int, startEpochDay: Long) = context.dataStore.edit {
        it[Keys.phase] = phase; it[Keys.phaseStart] = startEpochDay
    }
    suspend fun setShareWeight(on: Boolean) = context.dataStore.edit { it[Keys.shareWeight] = on }
    suspend fun setStepGoal(steps: Int) = context.dataStore.edit { it[Keys.stepGoal] = steps.coerceIn(1000, 30000) }
    suspend fun setPauseBeforeExercise(on: Boolean) = context.dataStore.edit { it[Keys.pauseBefore] = on }
    suspend fun setVoiceCues(on: Boolean) = context.dataStore.edit { it[Keys.voice] = on }
    suspend fun setReviewDay(d: java.time.DayOfWeek) = context.dataStore.edit { it[Keys.reviewDay] = d.name }
    suspend fun setReminder(r: Reminder, on: Boolean) = context.dataStore.edit { it[Keys.on(r)] = on }
    suspend fun setReminderTime(r: Reminder, minutes: Int) = context.dataStore.edit { it[Keys.time(r)] = minutes.coerceIn(0, 24 * 60 - 1) }
    suspend fun setAppLock(on: Boolean) = context.dataStore.edit { it[Keys.appLock] = on }
    suspend fun setUpdateCheck(on: Boolean) = context.dataStore.edit { it[Keys.updateCheck] = on }
    suspend fun setSkippedVersion(v: String?) = context.dataStore.edit { if (v == null) it.remove(Keys.skippedVersion) else it[Keys.skippedVersion] = v }
    suspend fun setLastBackup(at: Long) = context.dataStore.edit { it[Keys.lastBackup] = at }
    suspend fun setWaterServing(ml: Int) = context.dataStore.edit { it[Keys.waterServing] = ml.coerceIn(50, 1000) }
}
