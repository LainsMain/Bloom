package be.lain.bloom.feature.run

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.designsystem.BloomCard
import be.lain.bloom.core.designsystem.SectionHeader
import be.lain.bloom.core.designsystem.ShapeIcon
import be.lain.bloom.core.designsystem.StatTile
import be.lain.bloom.core.util.fmt
import be.lain.bloom.core.util.relative
import be.lain.bloom.data.training.RunRepository
import be.lain.bloom.domain.running.Geo
import be.lain.bloom.domain.running.RunProgram
import be.lain.bloom.domain.running.formatDuration
import be.lain.bloom.feature.food.FoodRow
import be.lain.bloom.service.RunManager
import be.lain.bloom.ui.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class RunningViewModel @Inject constructor(
    private val repo: RunRepository,
    manager: RunManager,
) : ViewModel() {
    val programIndex = repo.programIndex.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val plans = repo.plans.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val runs = repo.runs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val live = manager.live

    init {
        // Drop half-recorded runs from a killed session unless one is in progress now.
        viewModelScope.launch { if (manager.live.value == null) repo.cleanUnfinished() }
    }

    fun setProgramIndex(i: Int) = viewModelScope.launch { repo.setProgramIndex(i) }
}

@Composable
fun RunningPane(navigate: (Route) -> Unit, vm: RunningViewModel = hiltViewModel()) {
    val idx by vm.programIndex.collectAsStateWithLifecycle()
    val plans by vm.plans.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val week = runs.filter { !it.date.isBefore(monday) }
    val next = RunProgram.at(idx)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (live != null) {
            item {
                BloomCard(Modifier.fillMaxWidth(), onClick = { navigate(Route.RunActive(live!!.planKey, live!!.title)) },
                    color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Text("Run in progress", style = MaterialTheme.typography.titleMedium)
                    Text("Tap to return to ${live!!.title}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            BloomCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShapeIcon(MaterialShapes.Clover4Leaf, Icons.AutoMirrored.Rounded.DirectionsRun, size = 52.dp,
                        container = MaterialTheme.colorScheme.tertiary, content = MaterialTheme.colorScheme.onTertiary)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(RunProgram.NAME, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                        Text(next.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text("≈ ${formatDuration(next.totalSeconds)} · run ${idx + 1} of ${RunProgram.runs.size}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                Spacer(Modifier.height(10.dp))
                LinearWavyProgressIndicator(progress = { idx.toFloat() / RunProgram.runs.size }, modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.height(10.dp))
                Text(next.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { navigate(Route.RunActive("program:$idx", next.name)) },
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary),
                    modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp))
                    Text("Start run", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
                Row {
                    if (idx > 0) TextButton(onClick = { vm.setProgramIndex(idx - 1) }) { Text("Repeat previous") }
                    Spacer(Modifier.weight(1f))
                    if (idx < RunProgram.runs.lastIndex) TextButton(onClick = { vm.setProgramIndex(idx + 1) }) { Text("Skip ahead") }
                }
                Text("Repeat any run as often as you like — moving on when it feels comfortable is the whole idea.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("This week", (week.sumOf { it.distanceM } / 1000).fmt(1), Modifier.weight(1f), unit = "km", supporting = "${week.size} run${if (week.size == 1) "" else "s"}")
                StatTile("All runs", (runs.sumOf { it.distanceM } / 1000).fmt(1), Modifier.weight(1f), unit = "km", supporting = "${runs.size} total")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { navigate(Route.RunActive("free", "Free run")) }) {
                    Icon(Icons.AutoMirrored.Rounded.DirectionsRun, null); Spacer(Modifier.width(6.dp)); Text("Free run")
                }
                FilledTonalButton(onClick = { navigate(Route.RunPlanEditor()) }) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("New intervals")
                }
            }
        }
        if (plans.isNotEmpty()) {
            item { SectionHeader("My interval plans") }
            items(plans, key = { "plan-${it.first.id}" }) { (e, plan) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FoodRow(plan.name, "≈ ${formatDuration(plan.totalSeconds)} · ${plan.summary()}", Modifier.weight(1f),
                        onClick = { navigate(Route.RunPlanEditor(e.id)) })
                    FilledTonalButton(onClick = { navigate(Route.RunActive("plan:${e.id}", plan.name)) }) { Icon(Icons.Rounded.Timer, null); Text("Go") }
                }
            }
        }
        if (runs.isNotEmpty()) {
            item { SectionHeader("History") }
            items(runs.take(30), key = { "run-${it.id}" }) { r ->
                FoodRow(
                    r.title,
                    "${r.date.relative(today)} · ${(r.distanceM / 1000).fmt(2)} km · ${Geo.formatClock(r.movingSec)} · ${Geo.formatPace(Geo.pace(r.movingSec.toDouble(), r.distanceM))} /km",
                    onClick = { navigate(Route.RunDetail(r.id)) },
                )
            }
        }
    }
}
