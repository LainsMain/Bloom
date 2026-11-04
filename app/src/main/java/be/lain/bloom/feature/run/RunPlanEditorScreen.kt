package be.lain.bloom.feature.run

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.AppMessages
import be.lain.bloom.core.designsystem.ConnectedChoice
import be.lain.bloom.core.designsystem.NumberStepper
import be.lain.bloom.data.training.RunRepository
import be.lain.bloom.domain.running.PlanBlock
import be.lain.bloom.domain.running.RunPlan
import be.lain.bloom.domain.running.SegKind
import be.lain.bloom.domain.running.Segment
import be.lain.bloom.domain.running.formatDuration
import be.lain.bloom.ui.Route
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@HiltViewModel(assistedFactory = RunPlanEditorViewModel.Factory::class)
class RunPlanEditorViewModel @AssistedInject constructor(
    @Assisted val key: Route.RunPlanEditor,
    private val repo: RunRepository,
    private val messages: AppMessages,
) : ViewModel() {
    @AssistedFactory interface Factory { fun create(key: Route.RunPlanEditor): RunPlanEditorViewModel }

    val plan = MutableStateFlow(
        RunPlan(
            "My intervals",
            listOf(
                PlanBlock(Segment(SegKind.WARMUP, seconds = 300)),
                PlanBlock(times = 6, group = listOf(Segment(SegKind.RUN, seconds = 60), Segment(SegKind.WALK, seconds = 90))),
                PlanBlock(Segment(SegKind.COOLDOWN, seconds = 300)),
            ),
        ),
    )
    private val _done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val done = _done.asSharedFlow()

    init { key.id?.let { id -> viewModelScope.launch { repo.plan(id)?.let { plan.value = it } } } }

    fun update(f: (RunPlan) -> RunPlan) = plan.update(f)

    fun save() = viewModelScope.launch {
        repo.savePlan(key.id, plan.value)
        messages.show("Saved ${plan.value.name}")
        _done.tryEmit(Unit)
    }

    fun delete() = viewModelScope.launch {
        key.id?.let { repo.deletePlan(it) }
        _done.tryEmit(Unit)
    }
}

/** Editing target: a top-level block, or a step inside a repeat group. */
private data class EditTarget(val block: Int, val inGroup: Int? = null, val adding: Boolean = false)

@Composable
fun RunPlanEditorScreen(
    key: Route.RunPlanEditor,
    onBack: () -> Unit,
    vm: RunPlanEditorViewModel = hiltViewModel<RunPlanEditorViewModel, RunPlanEditorViewModel.Factory>(key = key.toString()) { it.create(key) },
) {
    val plan by vm.plan.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    LaunchedEffect(Unit) { vm.done.collect { onBack() } }

    fun setBlocks(b: List<PlanBlock>) = vm.update { it.copy(blocks = b) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (key.id == null) "New interval plan" else "Edit plan") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { if (key.id != null) IconButton(onClick = vm::delete) { Icon(Icons.Rounded.DeleteOutline, "Delete plan") } },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding().padding(16.dp)) {
                Text("Total ≈ ${formatDuration(plan.totalSeconds)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                Button(onClick = vm::save, enabled = plan.blocks.isNotEmpty() && plan.name.isNotBlank(), shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight)) {
                    Text("Save plan", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                OutlinedTextField(plan.name, { n -> vm.update { it.copy(name = n) } }, label = { Text("Plan name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            plan.blocks.forEachIndexed { i, b ->
                item(key = "b$i-${b.hashCode()}") {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (b.segment != null) {
                                    TextButton(onClick = { editing = EditTarget(i) }, modifier = Modifier.weight(1f)) {
                                        Text(b.segment.describe(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
                                    }
                                } else {
                                    Icon(Icons.Rounded.Repeat, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Repeat ${b.times}×", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { setBlocks(plan.blocks.toMutableList().also { l -> l[i] = b.copy(times = (b.times - 1).coerceAtLeast(2)) }) }) { Text("−") }
                                    IconButton(onClick = { setBlocks(plan.blocks.toMutableList().also { l -> l[i] = b.copy(times = (b.times + 1).coerceAtMost(30)) }) }) { Text("+") }
                                }
                                IconButton(onClick = { if (i > 0) setBlocks(plan.blocks.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }) }) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up") }
                                IconButton(onClick = { if (i < plan.blocks.lastIndex) setBlocks(plan.blocks.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) }) }) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down") }
                                IconButton(onClick = { setBlocks(plan.blocks.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Rounded.Close, "Remove") }
                            }
                            if (b.segment == null) {
                                b.group.forEachIndexed { g, s ->
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 24.dp)) {
                                        TextButton(onClick = { editing = EditTarget(i, g) }, modifier = Modifier.weight(1f)) {
                                            Text(s.describe(), modifier = Modifier.fillMaxWidth())
                                        }
                                        IconButton(onClick = {
                                            setBlocks(plan.blocks.toMutableList().also { l -> l[i] = b.copy(group = b.group.filterIndexed { k, _ -> k != g }) })
                                        }) { Icon(Icons.Rounded.Close, "Remove step") }
                                    }
                                }
                                TextButton(onClick = { editing = EditTarget(i, b.group.size, adding = true) }, modifier = Modifier.padding(start = 24.dp)) {
                                    Icon(Icons.Rounded.Add, null); Text("Add step to repeat")
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { editing = EditTarget(plan.blocks.size, adding = true) }) { Icon(Icons.Rounded.Add, null); Text("Step") }
                    FilledTonalButton(onClick = {
                        setBlocks(plan.blocks + PlanBlock(times = 4, group = listOf(Segment(SegKind.RUN, seconds = 60), Segment(SegKind.WALK, seconds = 60))))
                    }) { Icon(Icons.Rounded.Repeat, null); Text("Repeat") }
                }
            }
        }
    }

    editing?.let { t ->
        val b = plan.blocks.getOrNull(t.block)
        val existing = when {
            t.adding -> null
            t.inGroup != null -> b?.group?.getOrNull(t.inGroup)
            else -> b?.segment
        }
        SegmentDialog(existing, onDismiss = { editing = null }) { seg ->
            val blocks = plan.blocks.toMutableList()
            when {
                t.inGroup != null && b != null -> {
                    val g = b.group.toMutableList()
                    if (t.adding) g += seg else g[t.inGroup] = seg
                    blocks[t.block] = b.copy(group = g)
                }
                t.adding -> blocks += PlanBlock(seg)
                else -> blocks[t.block] = PlanBlock(seg)
            }
            setBlocks(blocks)
            editing = null
        }
    }
}

@Composable
private fun SegmentDialog(initial: Segment?, onDismiss: () -> Unit, onSave: (Segment) -> Unit) {
    var kind by remember { mutableStateOf(initial?.kind ?: SegKind.RUN) }
    var byDistance by remember { mutableStateOf(initial?.meters != null) }
    var minutes by remember { mutableIntStateOf((initial?.seconds ?: 60) / 60) }
    var seconds by remember { mutableIntStateOf((initial?.seconds ?: 60) % 60) }
    var meters by remember { mutableIntStateOf(initial?.meters ?: 400) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add step" else "Edit step") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SegKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
                }
                ConnectedChoice(listOf(false, true), byDistance, { if (it) "Distance" else "Time" }, { byDistance = it })
                if (byDistance) {
                    NumberStepper(meters.toDouble(), { meters = it.roundToInt() }, "m", step = 100.0, min = 50.0, max = 20000.0)
                } else {
                    NumberStepper(minutes.toDouble(), { minutes = it.roundToInt() }, "min", min = 0.0, max = 120.0)
                    NumberStepper(seconds.toDouble(), { seconds = it.roundToInt() }, "sec", step = 15.0, min = 0.0, max = 59.0)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(if (byDistance) Segment(kind, meters = meters) else Segment(kind, seconds = (minutes * 60 + seconds).coerceAtLeast(10)))
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
