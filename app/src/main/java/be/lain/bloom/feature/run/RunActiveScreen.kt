package be.lain.bloom.feature.run

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.GpsNotFixed
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.AppMessages
import be.lain.bloom.core.designsystem.RouteMap
import be.lain.bloom.core.designsystem.StatTile
import be.lain.bloom.data.training.RunRepository
import be.lain.bloom.domain.running.Geo
import be.lain.bloom.domain.running.SegKind
import be.lain.bloom.domain.running.formatDuration
import be.lain.bloom.domain.running.formatMeters
import be.lain.bloom.service.LiveRun
import be.lain.bloom.service.RunManager
import be.lain.bloom.service.RunStatus
import be.lain.bloom.ui.Route
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@HiltViewModel(assistedFactory = RunActiveViewModel.Factory::class)
class RunActiveViewModel @AssistedInject constructor(
    @Assisted val key: Route.RunActive,
    val manager: RunManager,
    private val repo: RunRepository,
    private val messages: AppMessages,
) : ViewModel() {
    @AssistedFactory interface Factory { fun create(key: Route.RunActive): RunActiveViewModel }

    val live = manager.live

    fun prepare() = viewModelScope.launch {
        if (manager.live.value == null) manager.prepare(key.title, key.planKey, repo.resolve(key.planKey))
    }

    fun save(rpe: Int?, notes: String, onSaved: (Long) -> Unit) = viewModelScope.launch {
        manager.save(rpe, notes)?.let { messages.show("Run saved — you're doing brilliantly 🏃"); onSaved(it) }
    }
}

@Composable
fun RunActiveScreen(
    key: Route.RunActive,
    onBack: () -> Unit,
    replace: (Route) -> Unit,
    vm: RunActiveViewModel = hiltViewModel<RunActiveViewModel, RunActiveViewModel.Factory>(key = key.toString()) { it.create(key) },
) {
    val context = LocalContext.current
    val live by vm.live.collectAsStateWithLifecycle()
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    var hasLocation by remember { mutableStateOf(granted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { hasLocation = granted() }
    LaunchedEffect(Unit) {
        if (!hasLocation) launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
    }
    LaunchedEffect(hasLocation) { if (hasLocation) vm.prepare() }

    if (!hasLocation) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Location is needed to track your run", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Your route stays on this phone.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Allow location") }
            TextButton(onClick = onBack) { Text("Not now") }
        }
        return
    }
    val l = live ?: return
    when (l.status) {
        RunStatus.FINISHED -> RunSummary(l, onSave = { rpe, notes -> vm.save(rpe, notes) { id -> replace(Route.RunDetail(id)) } },
            onDiscard = { vm.manager.discard(); onBack() })
        else -> LiveRunContent(l, vm.manager, onBack)
    }
}

@Composable
private fun LiveRunContent(l: LiveRun, m: RunManager, onBack: () -> Unit) {
    var confirmFinish by remember { mutableStateOf(false) }
    val acquiring = l.status == RunStatus.ACQUIRING
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (acquiring) { m.discard(); onBack() } else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(l.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            GpsChip(l.accuracy)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (acquiring) {
                Text(
                    l.plan?.let { "${it.summary()}\n\nWait for a good GPS signal (green), then start. Voice cues tell you when to run and walk — headphones in, phone in your pocket." }
                        ?: "Free run — no intervals, just go at a comfortable pace. Wait for a good GPS signal, then start.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                SegmentBanner(l)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Time", Geo.formatClock((l.activeMs / 1000).toInt()), Modifier.weight(1f))
                    StatTile("Distance", "%.2f".format(l.distanceM / 1000), Modifier.weight(1f), unit = "km")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Pace now", Geo.formatPace(l.currentPace), Modifier.weight(1f), unit = "/km")
                    StatTile("Average", Geo.formatPace(l.avgPace), Modifier.weight(1f), unit = "/km")
                }
            }
            Box(Modifier.fillMaxWidth().height(if (acquiring) 360.dp else 260.dp).clip(MaterialTheme.shapes.extraLarge)) {
                RouteMap(l.lines, follow = l.position)
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (acquiring) {
                val weak = (l.accuracy ?: 999f) > 25f
                Button(
                    onClick = { m.begin() }, shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.weight(1f).height(ButtonDefaults.LargeContainerHeight),
                ) { Text(if (weak) "Start (weak GPS)" else "Start", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
            } else {
                FilledTonalButton(onClick = { confirmFinish = true }, modifier = Modifier.height(72.dp)) {
                    Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(6.dp)); Text("Finish")
                }
                val paused = l.status == RunStatus.PAUSED
                Button(
                    onClick = m::togglePause, shape = RoundedCornerShape(if (paused) 36.dp else 20.dp),
                    colors = if (paused) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary) else ButtonDefaults.buttonColors(),
                    modifier = Modifier.weight(1f).height(72.dp),
                ) {
                    Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, Modifier.size(28.dp))
                    Spacer(Modifier.width(6.dp)); Text(if (paused) "Resume" else "Pause")
                }
            }
        }
    }
    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Finish run?") },
            text = { if (!l.planDone && l.plan != null) Text("You haven't finished all the intervals yet — that's fine, you can save what you did.") },
            confirmButton = { TextButton(onClick = { confirmFinish = false; m.finish() }) { Text("Finish") } },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Keep going") } },
        )
    }
}

@Composable
private fun GpsChip(accuracy: Float?) {
    val good = accuracy != null && accuracy <= 20f
    Surface(shape = MaterialTheme.shapes.small, color = if (good) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (good) Icons.Rounded.GpsFixed else Icons.Rounded.GpsNotFixed, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(accuracy?.let { "GPS ±${it.roundToInt()} m" } ?: "GPS…", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SegmentBanner(l: LiveRun) {
    val s = l.segment
    val running = s?.kind == SegKind.RUN
    val bg by animateColorAsState(
        when {
            l.status == RunStatus.PAUSED -> MaterialTheme.colorScheme.surfaceContainerHighest
            s == null || l.planDone -> MaterialTheme.colorScheme.primaryContainer
            running -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.tertiaryContainer
        }, label = "seg",
    )
    val fg = when {
        l.status == RunStatus.PAUSED -> MaterialTheme.colorScheme.onSurface
        s == null || l.planDone -> MaterialTheme.colorScheme.onPrimaryContainer
        running -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(shape = MaterialTheme.shapes.extraLarge, color = bg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    l.status == RunStatus.PAUSED -> "PAUSED"
                    l.planDone -> "PLAN DONE 🎉"
                    s == null -> "FREE RUN"
                    else -> s.kind.label.uppercase()
                },
                style = MaterialTheme.typography.titleLarge, color = fg,
            )
            val rem = l.segRemaining
            if (s != null && rem != null && !l.planDone) {
                Text(if (s.seconds != null) Geo.formatClock(rem.roundToInt()) else formatMeters(rem.roundToInt()),
                    style = MaterialTheme.typography.displayLarge, color = fg)
                Text(
                    "Interval ${l.segIndex + 1} of ${l.segmentsTotal}" + (l.nextSegment?.let { " · next: ${it.kind.label.lowercase()} ${it.seconds?.let(::formatDuration) ?: it.meters?.let(::formatMeters) ?: ""}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, color = fg,
                )
            } else {
                Text(Geo.formatClock((l.activeMs / 1000).toInt()), style = MaterialTheme.typography.displayLarge, color = fg)
            }
        }
    }
}

@Composable
private fun RunSummary(l: LiveRun, onSave: (Int?, String) -> Unit, onDiscard: () -> Unit) {
    var rpe by remember { mutableFloatStateOf(5f) }
    var notes by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (l.planDone) "Plan complete! 🎉" else "Nice run!", style = MaterialTheme.typography.headlineMedium)
        Box(Modifier.fillMaxWidth().height(240.dp).clip(MaterialTheme.shapes.extraLarge)) { RouteMap(l.lines) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Distance", "%.2f".format(l.distanceM / 1000), Modifier.weight(1f), unit = "km")
            StatTile("Time", Geo.formatClock((l.activeMs / 1000).toInt()), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Avg pace", Geo.formatPace(Geo.pace((l.activeMs / 1000).toDouble(), l.distanceM)), Modifier.weight(1f), unit = "/km")
            StatTile("Energy", "${l.kcal}", Modifier.weight(1f), unit = "kcal")
        }
        Text("How hard did it feel? ${rpe.roundToInt()}/10", style = MaterialTheme.typography.titleMedium)
        Slider(rpe, { rpe = it }, valueRange = 1f..10f, steps = 8)
        OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { saving = true; onSave(rpe.roundToInt(), notes) }, enabled = !saving, shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
        ) { Text("Save run", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
        TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Discard") }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this run?") },
            confirmButton = { TextButton(onClick = onDiscard) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep") } },
        )
    }
}
