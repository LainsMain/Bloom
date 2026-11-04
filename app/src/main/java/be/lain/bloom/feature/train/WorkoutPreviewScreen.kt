package be.lain.bloom.feature.train

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.data.training.TrainingRepository
import be.lain.bloom.domain.training.Effort
import be.lain.bloom.domain.training.Exercises
import be.lain.bloom.domain.training.Prescription
import be.lain.bloom.domain.training.Program
import be.lain.bloom.domain.training.Progression
import be.lain.bloom.domain.training.SetResult
import be.lain.bloom.domain.training.Suggestion
import be.lain.bloom.domain.training.SuggestionKind
import be.lain.bloom.service.WorkoutManager
import be.lain.bloom.ui.Route
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SlotView(
    val slotKey: String,
    val defaultId: String,
    val exerciseId: String,
    val p: Prescription,
    val last: List<SetResult>,
    val suggestion: Suggestion?,
)

data class BlockView(val title: String, val slots: List<SlotView>, val timedList: Boolean)

@HiltViewModel(assistedFactory = WorkoutPreviewViewModel.Factory::class)
class WorkoutPreviewViewModel @AssistedInject constructor(
    @Assisted val key: Route.WorkoutPreview,
    private val repo: TrainingRepository,
    private val manager: WorkoutManager,
) : ViewModel() {
    @AssistedFactory interface Factory { fun create(key: Route.WorkoutPreview): WorkoutPreviewViewModel }

    val session = Program.phase(key.phase).sessions.first { it.id == key.session }
    private val lastResults = MutableStateFlow<Map<String, List<SetResult>>>(emptyMap())
    private val _started = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val started = _started.asSharedFlow()

    val blocks = combine(repo.choices, lastResults) { choices, last ->
        session.blocks.mapIndexed { b, block ->
            BlockView(
                block.kind.label,
                block.items.mapIndexed { i, p ->
                    val slot = Program.slotKey(key.phase, session.id, b, i)
                    val ex = choices[slot] ?: p.exerciseId
                    val l = last[slot].orEmpty().filter { it.exerciseId == ex }
                    SlotView(slot, p.exerciseId, ex, p, l, Progression.suggest(p, ex, l))
                },
                timedList = block.kind.name == "WARMUP" || block.kind.name == "COOLDOWN",
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val map = HashMap<String, List<SetResult>>()
            session.blocks.forEachIndexed { b, block ->
                block.items.indices.forEach { i ->
                    val slot = Program.slotKey(key.phase, session.id, b, i)
                    map[slot] = repo.lastForSlot(slot).map {
                        SetResult(0, it.exerciseId, it.slotKey, it.setIndex, it.side, it.reps, it.seconds, it.effort, it.skipped)
                    }
                }
            }
            lastResults.value = map
        }
    }

    fun swap(slot: SlotView, to: String) = viewModelScope.launch { repo.setChoice(slot.slotKey, to, slot.defaultId) }

    fun start() = viewModelScope.launch {
        manager.start(key.phase, session)
        _started.tryEmit(Unit)
    }
}

@Composable
fun WorkoutPreviewScreen(
    key: Route.WorkoutPreview,
    onBack: () -> Unit,
    navigate: (Route) -> Unit,
    replace: (Route) -> Unit,
    vm: WorkoutPreviewViewModel = hiltViewModel<WorkoutPreviewViewModel, WorkoutPreviewViewModel.Factory>(key = key.toString()) { it.create(key) },
) {
    val blocks by vm.blocks.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.started.collect { replace(Route.WorkoutPlayer) } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(vm.session.name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                Button(onClick = vm::start, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight)) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(8.dp))
                    Text("Start · ≈ ${vm.session.estimateMinutes()} min", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("${vm.session.focus}. Tip: use ⇡ / ⇣ to switch an exercise to a harder or easier version — it sticks for next time.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            blocks.forEach { b ->
                item(key = "h-${b.title}") { Text(b.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp)) }
                if (b.timedList) {
                    item(key = "l-${b.title}") {
                        Text(b.slots.joinToString(" · ") { "${Exercises[it.exerciseId].name} ${it.p.seconds}s" },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    b.slots.forEach { s -> item(key = s.slotKey) { SlotCard(s, onOpen = { navigate(Route.ExerciseDetail(s.exerciseId)) }, onSwap = { vm.swap(s, it) }) } }
                }
            }
        }
    }
}

@Composable
private fun SlotCard(s: SlotView, onOpen: () -> Unit, onSwap: (String) -> Unit) {
    val ex = Exercises[s.exerciseId]
    val easier = Exercises.easier(s.exerciseId)
    val harder = Exercises.harder(s.exerciseId)
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clickable(onClick = onOpen).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(ex.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${s.p.sets} × ${s.p.target()}${if (ex.perSide) " each side" else ""} · rest ${s.p.restSec}s" + if (s.p.note.isNotBlank()) " · ${s.p.note}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (easier != null) IconButton(onClick = { onSwap(easier) }) { Icon(Icons.Rounded.KeyboardDoubleArrowDown, "Easier: ${Exercises[easier].name}") }
                if (harder != null) IconButton(onClick = { onSwap(harder) }) { Icon(Icons.Rounded.KeyboardDoubleArrowUp, "Harder: ${Exercises[harder].name}") }
            }
            if (s.last.isNotEmpty()) {
                Text(
                    "Last time: " + s.last.filter { !it.skipped }.joinToString(", ") { r ->
                        (r.reps?.let { "$it" } ?: r.seconds?.let { "${it}s" } ?: "–") + (r.effort?.let { e -> if (e == Effort.HARD) " (hard)" else if (e == Effort.EASY) " (easy)" else "" } ?: "")
                    }.ifEmpty { "skipped" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            s.suggestion?.let { sg ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = MaterialTheme.shapes.small,
                        color = if (sg.kind == SuggestionKind.HARDER) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Text(sg.text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                    if (sg.exerciseId != null) {
                        Spacer(Modifier.width(8.dp))
                        Text("Switch", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onSwap(sg.exerciseId) }.padding(4.dp))
                    }
                }
            }
        }
    }
}
