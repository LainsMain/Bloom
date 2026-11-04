package be.lain.bloom.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.lain.bloom.BuildConfig
import be.lain.bloom.core.designsystem.BloomCard
import be.lain.bloom.core.designsystem.ConnectedChoice
import be.lain.bloom.core.designsystem.SectionHeader
import be.lain.bloom.core.designsystem.dataColors
import be.lain.bloom.core.designsystem.paletteSwatch
import be.lain.bloom.core.util.fmt
import be.lain.bloom.core.util.shortWithYear
import be.lain.bloom.data.Palette
import be.lain.bloom.data.ThemeMode
import be.lain.bloom.data.enabled
import be.lain.bloom.data.timeOf
import be.lain.bloom.domain.PlanSource
import be.lain.bloom.ui.Route

@Composable
fun SettingsScreen(onBack: () -> Unit, navigate: (Route) -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var showHistory by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        if (!state.loaded) return@Scaffold
        val s = state.settings
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.profile?.let { p ->
                item {
                    BloomCard(Modifier.fillMaxWidth(), onClick = { navigate(Route.EditProfile) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = MaterialShapes.Cookie9Sided.toShape(), color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.size(56.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(p.name.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "${p.goal.label} · goal ${p.goalWeightKg.fmt()} kg",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { navigate(Route.EditProfile) }) { Text("Edit") }
                        }
                    }
                }
            }
            state.activePlan?.let { plan ->
                item { SectionHeader("Your plan") { TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "Hide history" else "History") } } }
                item {
                    val c = MaterialTheme.dataColors
                    BloomCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(plan.targets.kcal.fmt(), style = MaterialTheme.typography.displaySmall)
                            Text(" kcal / day", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            MacroLabel("Protein", plan.targets.proteinG, c.protein)
                            MacroLabel("Carbs", plan.targets.carbsG, c.carbs)
                            MacroLabel("Fat", plan.targets.fatG, c.fat)
                            MacroLabel("Fibre", plan.targets.fiberG, c.fiber)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Since ${plan.effectiveDate.shortWithYear()} · ${plan.reason}. Estimated burn ${plan.expenditureKcal.fmt()} kcal/day.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (showHistory) {
                    state.plans.forEach { p ->
                        item(key = "plan-${p.id}") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.effectiveDate.shortWithYear(), style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        when (p.source) {
                                            PlanSource.ONBOARDING -> "Setup"
                                            PlanSource.MANUAL -> "Manual"
                                            PlanSource.WEEKLY_REVIEW -> "Weekly review"
                                        } + " · " + p.reason,
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text("${p.targets.kcal.fmt()} kcal", style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
            }

            item { SectionHeader("Appearance") }
            item {
                BloomCard(Modifier.fillMaxWidth()) {
                    Text("Theme", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    ConnectedChoice(ThemeMode.entries, s.themeMode, { it.label }, vm::setTheme)
                    run {
                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier.toggleable(value = s.dynamicColor, role = Role.Switch, onValueChange = vm::setDynamic),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Colours from wallpaper", style = MaterialTheme.typography.titleMedium)
                                Text("Material You dynamic colour", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = s.dynamicColor, onCheckedChange = null)
                        }
                    }
                    if (!s.dynamicColor) {
                        Spacer(Modifier.height(16.dp))
                        Text("Palette", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Palette.entries.forEach { p ->
                                val selected = p == s.palette
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Surface(
                                        onClick = { vm.setPalette(p) },
                                        shape = (if (selected) MaterialShapes.Cookie9Sided else MaterialShapes.Circle).toShape(),
                                        color = paletteSwatch(p),
                                        modifier = Modifier.size(52.dp).semantics { contentDescription = "${p.label} palette" },
                                    ) {
                                        if (selected) Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = androidx.compose.ui.graphics.Color.White) }
                                    }
                                    Text(p.label, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }

            item { SectionHeader("AI coach") }
            item {
                val hasKey by vm.hasKey.collectAsStateWithLifecycle()
                if (hasKey) {
                    BloomCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("DeepSeek connected", style = MaterialTheme.typography.titleMedium)
                                Text("Key stored encrypted on this phone. Coach chats, photos and the data the coach reads are sent to DeepSeek.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = vm::removeKey) { Text("Remove") }
                        }
                    }
                } else {
                    be.lain.bloom.feature.coach.ApiKeyCard(vm::saveKey)
                }
            }

            item { SectionHeader("Health Connect") }
            item { HealthConnectCard(vm, s) }

            item { SectionHeader("Weekly review & reminders") }
            item { RemindersCard(vm, s) }

            item { SectionHeader("Cycle") }
            item {
                BloomCard(Modifier.fillMaxWidth()) {
                    SwitchRow(
                        "Track my cycle",
                        "Log your period so your coach and weekly review can tell water weight from fat loss. Shows a card on Today.",
                        s.cycleTracking, vm::setCycleTracking,
                    )
                    if (s.cycleTracking) TextButton(onClick = { navigate(Route.Cycle) }) { Text("Open cycle calendar") }
                }
            }

            item { SectionHeader("Training") }
            item {
                BloomCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.toggleable(s.voiceCues, role = Role.Switch, onValueChange = vm::setVoice), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Voice cues", style = MaterialTheme.typography.titleMedium)
                            Text("Spoken guidance during workouts and runs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = s.voiceCues, onCheckedChange = null)
                    }
                }
            }

            item { SectionHeader("Water") }
            item {
                BloomCard(Modifier.fillMaxWidth()) {
                    Text("Daily goal", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1500, 2000, 2500, 3000).forEach { ml ->
                            FilterChip(selected = s.waterGoalMl == ml, onClick = { vm.setWaterGoal(ml) },
                                label = { Text("${(ml / 1000.0).fmt(1)} L") })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Glass size", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(200, 250, 330, 500).forEach { ml ->
                            FilterChip(selected = s.waterServingMl == ml, onClick = { vm.setWaterServing(ml) }, label = { Text("$ml ml") })
                        }
                    }
                }
            }

            item { SectionHeader("Privacy & backup") }
            item { SecurityCard(vm, s) }
            item { BackupCard(vm, s) }
            item {
                BloomCard(Modifier.fillMaxWidth()) {
                    Row {
                        Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Everything you log stays on this phone. There's no account and no cloud. " +
                                "Online features (food search, AI coach, maps, exercise videos) only connect when you use them.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            item { SectionHeader("App updates") }
            item { UpdatesCard(vm, s) }
        }
    }
}

@Composable
private fun MacroLabel(label: String, grams: Int, color: androidx.compose.ui.graphics.Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Canvas(Modifier.size(8.dp)) { drawCircle(color) }
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("$grams g", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun HealthConnectCard(vm: SettingsViewModel, s: be.lain.bloom.data.AppSettings) {
    val status by vm.hcStatus.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract(),
    ) { vm.onHealthPermissions() }
    BloomCard(Modifier.fillMaxWidth()) {
        when (status) {
            be.lain.bloom.data.health.HcStatus.UNAVAILABLE ->
                Text("Health Connect isn't available on this phone.", style = MaterialTheme.typography.bodyMedium)
            be.lain.bloom.data.health.HcStatus.NEEDS_INSTALL -> {
                Text("Install or update Health Connect to sync steps and workouts.", style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Button(onClick = {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("market://details?id=com.google.android.apps.healthdata")))
                }) { Text("Get Health Connect") }
            }
            be.lain.bloom.data.health.HcStatus.NOT_CONNECTED -> {
                Text("Connect to see your steps and other apps' workouts, and to share the runs and workouts you record here. " +
                    "Runs are never counted twice.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.Button(onClick = { launcher.launch(vm.health.allPermissions) }) { Text("Connect") }
            }
            be.lain.bloom.data.health.HcStatus.CONNECTED -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Connected", style = MaterialTheme.typography.titleMedium)
                        Text("Steps, weigh-ins and workouts sync when you open Bloom.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = vm::syncHealth) { Text("Sync now") }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.toggleable(s.shareWeight, role = Role.Switch, onValueChange = vm::setShareWeight), verticalAlignment = Alignment.CenterVertically) {
                    Text("Share weigh-ins with Health Connect", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = s.shareWeight, onCheckedChange = null)
                }
                TextButton(onClick = { launcher.launch(vm.health.allPermissions) }) { Text("Review permissions") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Daily step goal", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(6000, 8000, 10000, 12000).forEach { n ->
                FilterChip(selected = s.stepGoal == n, onClick = { vm.setStepGoal(n) }, label = { Text("${n / 1000}k") })
            }
        }
    }
}

@Composable
private fun RemindersCard(vm: SettingsViewModel, s: be.lain.bloom.data.AppSettings) {
    val canNotify by vm.canNotify.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { vm.refreshNotify(); onPauseOrDispose { } }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        vm.refreshNotify()
        if (!granted) {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
    }
    fun askPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    var editing by rememberSaveable { mutableStateOf<be.lain.bloom.data.Reminder?>(null) }

    BloomCard(Modifier.fillMaxWidth()) {
        Text("Review day", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        be.lain.bloom.core.designsystem.DayPicker(selected = setOf(s.reviewDay), onToggle = vm::setReviewDay)
        Text(
            "Your weekly review covers the 7 days before it and becomes ready on this day.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        val anyOn = be.lain.bloom.data.Reminder.entries.any { s.enabled(it) }
        if (anyOn && !canNotify) {
            Spacer(Modifier.height(12.dp))
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Notifications are off for Bloom, so reminders can't appear.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                    TextButton(onClick = ::askPermission) { Text("Allow") }
                }
            }
        }
        be.lain.bloom.data.Reminder.entries.forEach { r ->
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).toggleable(s.enabled(r), role = Role.Switch) { on ->
                        vm.setReminder(r, on)
                        if (on && !canNotify) askPermission()
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(r.label, style = MaterialTheme.typography.titleMedium)
                        Text(r.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = s.enabled(r), onCheckedChange = null)
                }
            }
            if (s.enabled(r)) {
                val m = s.timeOf(r)
                androidx.compose.material3.FilledTonalButton(onClick = { editing = r }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("At %02d:%02d".format(m / 60, m % 60))
                }
            }
        }
    }
    editing?.let { r ->
        val m = s.timeOf(r)
        val state = androidx.compose.material3.rememberTimePickerState(m / 60, m % 60, is24Hour = true)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("${r.label} time") },
            text = { androidx.compose.material3.TimePicker(state) },
            confirmButton = { TextButton(onClick = { vm.setReminderTime(r, state.hour * 60 + state.minute); editing = null }) { Text("Set") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SecurityCard(vm: SettingsViewModel, s: be.lain.bloom.data.AppSettings) {
    BloomCard(Modifier.fillMaxWidth()) {
        SwitchRow("App lock", "Ask for your fingerprint, face or screen lock when opening Bloom", s.appLock, vm::setAppLock)
    }
}

private enum class PasswordMode { EXPORT, RESTORE }

@Composable
private fun BackupCard(vm: SettingsViewModel, s: be.lain.bloom.data.AppSettings) {
    val busy by vm.backupBusy.collectAsStateWithLifecycle()
    val restored by vm.restored.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf<PasswordMode?>(null) }
    var restoreUri by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val create = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> if (uri != null) vm.export(uri) else vm.dropPassword() }
    val open = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) { restoreUri = uri; mode = PasswordMode.RESTORE } }

    BloomCard(Modifier.fillMaxWidth()) {
        Text("Backup", style = MaterialTheme.typography.titleMedium)
        Text(
            "Saves everything you've logged, your settings and photos into one password-protected file. " +
                "Keep it somewhere safe, like your cloud drive. Your AI key isn't included.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            s.lastBackupAt?.let { "Last backup: " + java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().shortWithYear() }
                ?: "No backup made yet",
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (busy != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.LoadingIndicator(Modifier.size(40.dp))
                Spacer(Modifier.width(8.dp))
                Text(busy!!, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = { mode = PasswordMode.EXPORT }) { Text("Back up now") }
                androidx.compose.material3.OutlinedButton(onClick = { open.launch(arrayOf("*/*")) }) { Text("Restore") }
            }
        }
    }

    mode?.let { m ->
        PasswordDialog(
            mode = m,
            onDismiss = { mode = null; vm.dropPassword() },
            onConfirm = { pw ->
                mode = null
                if (m == PasswordMode.EXPORT) { vm.holdPassword(pw); create.launch(vm.backupName()) }
                else restoreUri?.let { vm.restore(it, pw) }
            },
        )
    }
    if (restored) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            title = { Text("Backup restored") },
            text = { Text("Bloom will restart with your restored data. Your DeepSeek key is never part of a backup, so on a new phone add it again in Settings.") },
            confirmButton = { androidx.compose.material3.Button(onClick = vm::restart) { Text("Restart") } },
        )
    }
}

@Composable
private fun PasswordDialog(mode: PasswordMode, onDismiss: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val export = mode == PasswordMode.EXPORT
    val error = when {
        export && pw.isNotEmpty() && pw.length < 8 -> "Use at least 8 characters"
        export && again.isNotEmpty() && again != pw -> "The passwords don't match"
        else -> null
    }
    val ok = if (export) pw.length >= 8 && pw == again else pw.isNotEmpty()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (export) "Protect your backup" else "Restore from backup") },
        text = {
            Column {
                Text(
                    if (export) "Choose a password. You'll need it to restore, and it can't be recovered if you forget it."
                    else "This replaces everything currently in Bloom on this phone with the backup.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (export) {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = again, onValueChange = { again = it }, label = { Text("Repeat password") }, singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(pw.toCharArray()); pw = ""; again = "" }, enabled = ok) { Text(if (export) "Choose where to save" else "Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UpdatesCard(vm: SettingsViewModel, s: be.lain.bloom.data.AppSettings) {
    val state by vm.updater.state.collectAsStateWithLifecycle()
    BloomCard(Modifier.fillMaxWidth()) {
        Text("Bloom ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
        if (!vm.updater.supported) {
            Text("This is a development build. Updates install over the release version from GitHub.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@BloomCard
        }
        Spacer(Modifier.height(8.dp))
        SwitchRow("Check for updates", "Looks for a new version on GitHub when Bloom opens", s.updateCheck, vm::setUpdateCheck)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (val st = state) {
                    be.lain.bloom.data.update.UpdateState.Checking -> "Checking…"
                    be.lain.bloom.data.update.UpdateState.UpToDate -> "You have the latest version."
                    is be.lain.bloom.data.update.UpdateState.Failed -> st.message
                    is be.lain.bloom.data.update.UpdateState.Available -> "Version ${st.release.version} is available."
                    else -> "Releases come from github.com/${BuildConfig.UPDATE_REPO}"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = vm.updater::checkNow, enabled = state != be.lain.bloom.data.update.UpdateState.Checking) { Text("Check now") }
        }
    }
}
