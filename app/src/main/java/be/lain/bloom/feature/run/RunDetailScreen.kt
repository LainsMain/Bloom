package be.lain.bloom.feature.run

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.AppMessages
import be.lain.bloom.core.designsystem.BloomCard
import be.lain.bloom.core.designsystem.RouteLine
import be.lain.bloom.core.designsystem.RouteMap
import be.lain.bloom.core.designsystem.StatTile
import be.lain.bloom.core.util.long
import be.lain.bloom.data.db.RunPointEntity
import be.lain.bloom.data.db.RunSessionEntity
import be.lain.bloom.data.training.RunRepository
import be.lain.bloom.domain.running.Geo
import be.lain.bloom.domain.running.Split
import be.lain.bloom.domain.running.Splits
import be.lain.bloom.ui.Route
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

data class RunDetail(val run: RunSessionEntity, val lines: List<RouteLine>, val splits: List<Split>)

/** Groups points into lines per segment kind, breaking at pauses (gaps in time). */
fun routeLines(points: List<RunPointEntity>): List<RouteLine> {
    val out = mutableListOf<RouteLine>()
    var cur = mutableListOf<LatLng>()
    var kind: String? = null
    var prev: RunPointEntity? = null
    points.forEach { p ->
        val gap = prev?.let { p.t - it.t > 15_000 } ?: false
        if (prev != null && (p.segmentKind != kind || gap)) {
            if (cur.size >= 2) out += RouteLine(kind, cur)
            cur = if (gap) mutableListOf() else mutableListOf(cur.last())
        }
        cur += LatLng(p.lat, p.lon)
        kind = p.segmentKind
        prev = p
    }
    if (cur.size >= 2) out += RouteLine(kind, cur)
    return out
}

@HiltViewModel(assistedFactory = RunDetailViewModel.Factory::class)
class RunDetailViewModel @AssistedInject constructor(
    @Assisted val key: Route.RunDetail,
    private val repo: RunRepository,
    private val messages: AppMessages,
) : ViewModel() {
    @AssistedFactory interface Factory { fun create(key: Route.RunDetail): RunDetailViewModel }

    val detail = MutableStateFlow<RunDetail?>(null)

    init {
        viewModelScope.launch {
            val run = repo.run(key.id) ?: return@launch
            val pts = repo.points(key.id)
            detail.value = RunDetail(run, routeLines(pts), Splits.compute(pts.map { it.activeSec to it.distanceM }))
        }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch { repo.deleteRun(key.id); messages.show("Run deleted"); onDone() }
}

@Composable
fun RunDetailScreen(
    key: Route.RunDetail,
    onBack: () -> Unit,
    vm: RunDetailViewModel = hiltViewModel<RunDetailViewModel, RunDetailViewModel.Factory>(key = key.toString()) { it.create(key) },
) {
    val d by vm.detail.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(d?.run?.title ?: "Run") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { confirm = true }) { Icon(Icons.Rounded.DeleteOutline, "Delete run") } },
            )
        },
    ) { padding ->
        val det = d ?: return@Scaffold
        val r = det.run
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(r.date.long(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Box(Modifier.fillMaxWidth().height(280.dp).clip(MaterialTheme.shapes.extraLarge)) {
                if (det.lines.isEmpty()) Text("No GPS route recorded", modifier = Modifier.align(Alignment.Center))
                else RouteMap(det.lines)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Distance", "%.2f".format(r.distanceM / 1000), Modifier.weight(1f), unit = "km")
                StatTile("Moving time", Geo.formatClock(r.movingSec), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Avg pace", Geo.formatPace(Geo.pace(r.movingSec.toDouble(), r.distanceM)), Modifier.weight(1f), unit = "/km")
                StatTile("Energy", "${r.kcal}", Modifier.weight(1f), unit = "kcal")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("Climb", "${r.elevationGainM.toInt()}", Modifier.weight(1f), unit = "m")
                StatTile("Effort", r.rpe?.let { "$it/10" } ?: "–", Modifier.weight(1f), supporting = if (r.planCompleted) "Plan completed ✓" else null)
            }
            if (det.splits.isNotEmpty()) SplitsCard(det.splits)
            if (r.notes.isNotBlank()) BloomCard(Modifier.fillMaxWidth()) { Text(r.notes, style = MaterialTheme.typography.bodyLarge) }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete this run?") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.delete(onBack) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep") } },
        )
    }
}

/** Per-km pace as horizontal bars (single series; the pace label sits outside each bar). */
@Composable
private fun SplitsCard(splits: List<Split>) {
    val paces = splits.map { it.seconds / it.partial }
    val max = paces.max()
    val fastest = paces.min()
    BloomCard(Modifier.fillMaxWidth()) {
        Text("Splits", style = MaterialTheme.typography.titleLarge)
        Text("Pace per kilometre — shorter bar is faster", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        splits.forEachIndexed { i, s ->
            val pace = paces[i]
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (s.partial < 1) "%.1f".format(s.km - 1 + s.partial) else "${s.km}", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.width(36.dp))
                Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 4.dp)) {
                    Box(
                        Modifier.fillMaxWidth((pace / max).toFloat().coerceIn(0.05f, 1f)).fillMaxHeight()
                            .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                            .background(if (pace == fastest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(Geo.formatPace(pace), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(48.dp))
            }
        }
    }
}
