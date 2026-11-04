package be.lain.bloom.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import be.lain.bloom.data.AppSettings
import be.lain.bloom.data.BodyRepository
import be.lain.bloom.data.Palette
import be.lain.bloom.data.ProfileRepository
import be.lain.bloom.data.SettingsRepository
import be.lain.bloom.data.ThemeMode
import be.lain.bloom.data.activeOn
import be.lain.bloom.domain.PlanVersion
import be.lain.bloom.domain.Profile
import be.lain.bloom.domain.Trend
import be.lain.bloom.domain.WeightPoint
import be.lain.bloom.feature.onboarding.ProfileDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

data class SettingsState(
    val loaded: Boolean = false,
    val profile: Profile? = null,
    val currentKg: Double? = null,
    val activePlan: PlanVersion? = null,
    val plans: List<PlanVersion> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    body: BodyRepository,
    private val settingsRepo: SettingsRepository,
    private val secrets: be.lain.bloom.data.ai.SecretStore,
    private val messages: be.lain.bloom.core.AppMessages,
    val health: be.lain.bloom.data.health.HealthSync,
    private val reminders: be.lain.bloom.service.Reminders,
    private val backups: be.lain.bloom.data.backup.BackupManager,
    val appLock: be.lain.bloom.core.AppLock,
    val updater: be.lain.bloom.data.update.Updater,
    private val clock: Clock,
) : ViewModel() {
    // ---- app lock ----
    fun setAppLock(on: Boolean) = viewModelScope.launch {
        if (on && !appLock.available()) { messages.show("Set up a screen lock or fingerprint on your phone first"); return@launch }
        settingsRepo.setAppLock(on)
    }

    // ---- updates ----
    fun saveNotes(text: String) = viewModelScope.launch { profiles.setNotes(text); messages.show("Saved. Your coach will take this into account.") }
    fun setCycleTracking(on: Boolean) = viewModelScope.launch { settingsRepo.setCycleTracking(on) }
    fun setUpdateCheck(on: Boolean) = viewModelScope.launch { settingsRepo.setUpdateCheck(on) }

    // ---- backup ----
    val backupBusy = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val restored = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var pendingPassword: CharArray? = null
    fun backupName() = backups.suggestedName()
    fun holdPassword(pw: CharArray) { pendingPassword?.fill(' '); pendingPassword = pw }
    fun dropPassword() { pendingPassword?.fill(' '); pendingPassword = null }

    fun export(uri: android.net.Uri) = viewModelScope.launch {
        val pw = pendingPassword ?: return@launch
        pendingPassword = null
        backupBusy.value = "Saving backup…"
        runCatching { backups.export(uri, pw) }
            .onSuccess { settingsRepo.setLastBackup(clock.millis()); messages.show("Backup saved") }
            .onFailure { messages.show("Backup failed: ${it.message}") }
        backupBusy.value = null
    }

    fun restore(uri: android.net.Uri, pw: CharArray) = viewModelScope.launch {
        backupBusy.value = "Restoring…"
        runCatching { backups.restore(uri, pw) }
            .onSuccess { restored.value = true }
            .onFailure { messages.show(it.message ?: "Restore failed") }
        backupBusy.value = null
    }

    fun restart() = backups.restart()
    val canNotify = kotlinx.coroutines.flow.MutableStateFlow(reminders.canNotify())
    fun refreshNotify() { canNotify.value = reminders.canNotify() }

    fun setReminder(r: be.lain.bloom.data.Reminder, on: Boolean) = viewModelScope.launch {
        settingsRepo.setReminder(r, on); reminders.rescheduleAll()
    }
    fun setReminderTime(r: be.lain.bloom.data.Reminder, minutes: Int) = viewModelScope.launch {
        settingsRepo.setReminderTime(r, minutes); reminders.rescheduleAll()
    }
    fun setReviewDay(d: java.time.DayOfWeek) = viewModelScope.launch {
        settingsRepo.setReviewDay(d); reminders.rescheduleAll()
    }
    val hcStatus = health.status
    init { viewModelScope.launch { health.refreshStatus() } }
    fun onHealthPermissions() = viewModelScope.launch {
        if (health.refreshStatus() == be.lain.bloom.data.health.HcStatus.CONNECTED) { health.sync(); messages.show("Health Connect connected") }
    }
    fun syncHealth() = viewModelScope.launch { health.sync(); messages.show(health.lastError.value?.let { "Sync problem: $it" } ?: "Synced with Health Connect") }
    fun setShareWeight(on: Boolean) = viewModelScope.launch { settingsRepo.setShareWeight(on) }
    fun setStepGoal(n: Int) = viewModelScope.launch { settingsRepo.setStepGoal(n) }
    fun setVoice(on: Boolean) = viewModelScope.launch { settingsRepo.setVoiceCues(on) }
    val hasKey = secrets.hasDeepSeekKey.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun saveKey(k: String) = viewModelScope.launch { secrets.setDeepSeekKey(k); messages.show("API key saved") }
    fun removeKey() = viewModelScope.launch { secrets.setDeepSeekKey(null); messages.show("API key removed") }

    val today: LocalDate get() = LocalDate.now(clock)

    val state: StateFlow<SettingsState> = combine(
        profiles.profile, profiles.plans, body.weights, settingsRepo.settings,
    ) { profile, plans, weights, settings ->
        SettingsState(
            loaded = true,
            profile = profile,
            currentKg = Trend.at(weights.map { WeightPoint(it.date, it.kg) }, today),
            activePlan = plans.activeOn(today),
            plans = plans,
            settings = settings,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    fun draft(): ProfileDraft? = state.value.profile?.let { ProfileDraft.from(it, state.value.currentKg ?: it.startWeightKg) }

    fun saveProfile(draft: ProfileDraft, onDone: () -> Unit) = viewModelScope.launch {
        val p = state.value.profile ?: return@launch
        profiles.updateProfile(draft.toProfile(startDate = p.startDate, startWeightKg = p.startWeightKg).copy(notes = p.notes))
        onDone()
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsRepo.setThemeMode(mode) }
    fun setDynamic(on: Boolean) = viewModelScope.launch { settingsRepo.setDynamicColor(on) }
    fun setPalette(p: Palette) = viewModelScope.launch { settingsRepo.setPalette(p) }
    fun setWaterGoal(ml: Int) = viewModelScope.launch { settingsRepo.setWaterGoal(ml) }
    fun setWaterServing(ml: Int) = viewModelScope.launch { settingsRepo.setWaterServing(ml) }
}
