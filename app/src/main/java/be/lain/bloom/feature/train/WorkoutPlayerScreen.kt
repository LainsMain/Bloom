package be.lain.bloom.feature.train

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.AppMessages
import be.lain.bloom.core.designsystem.ConnectedChoice
import be.lain.bloom.core.figure.ExerciseFigure
import be.lain.bloom.core.figure.FigureFrame
import be.lain.bloom.domain.motion.label
import be.lain.bloom.domain.motion.Tempos
import be.lain.bloom.domain.training.GuidedProgress
import be.lain.bloom.domain.training.WorkoutSpeech
import be.lain.bloom.domain.training.figure
import be.lain.bloom.domain.training.guided
import android.os.SystemClock
import androidx.compose.material3.toShape
import androidx.compose.runtime.rememberUpdatedState
import be.lain.bloom.core.designsystem.NumberStepper
import be.lain.bloom.core.designsystem.ShapeIcon
import be.lain.bloom.domain.training.Effort
import be.lain.bloom.domain.training.Exercises
import be.lain.bloom.domain.training.PlayerState
import be.lain.bloom.domain.training.StepType
import be.lain.bloom.service.WorkoutManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class WorkoutPlayerViewModel @Inject constructor(
    val manager: WorkoutManager,
    private val settings: be.lain.bloom.data.SettingsRepository,
    private val messages: AppMessages,
) : ViewModel() {
    val active = manager.active
    val pauseBefore = settings.settings.map { it.pauseBeforeExercise }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setPauseBefore(on: Boolean) = viewModelScope.launch {
        settings.setPauseBeforeExercise(on)
        manager.pauseBeforeExercise = on
    }

    fun save(rpe: Int?, notes: String, date: java.time.LocalDate, onDone: () -> Unit) = viewModelScope.launch {
        manager.save(rpe, notes, date)
        messages.show("Workout saved — lovely work 💪")
        onDone()
    }

    fun discard(onDone: () -> Unit) { manager.discard(); onDone() }
}

@Composable
fun WorkoutPlayerScreen(onBack: () -> Unit, vm: WorkoutPlayerViewModel = hiltViewModel()) {
    val active by vm.active.collectAsStateWithLifecycle()
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val a = active
    LaunchedEffect(a == null) { if (a == null) onBack() }
    if (a == null) return
    val p = a.player
    var confirmEnd by remember { mutableStateOf(false) }

    if (p.finished) {
        Summary(vm, p, a.session.name, a.startedAt, onDone = onBack)
        return
    }
    val step = p.current
    val ex = Exercises[step.exerciseId]
    val m = vm.manager
    val now = SystemClock.elapsedRealtime()
    val guided = p.guided(now)
    val latest by rememberUpdatedState(p)
    val figureFrames = remember { { t: Long -> latest.figure(t).let { (id, f) -> FigureFrame(id, f) } } }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { confirmEnd = true }) { Icon(Icons.Rounded.Close, "End workout") }
            Column(Modifier.weight(1f)) {
                Text(a.session.name, style = MaterialTheme.typography.titleMedium)
                Text(step.block.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text("${p.index + 1}/${p.steps.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 16.dp))
        }
        LinearWavyProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))

        // The figure: in step with the voice during a set, demoing what's next during rests.
        Box(Modifier.fillMaxWidth().weight(0.47f).padding(horizontal = 16.dp, vertical = 4.dp)) {
            ExerciseFigure(figureFrames, Modifier.fillMaxSize(), label = "${ex.name} demonstration")
            if (step.type == StepType.WORK && p.inLeadIn && !p.paused) {
                val left = ((p.leadInEndMs - now) / 1000.0).let { kotlin.math.ceil(it).toInt() }
                if (left in 1..3) {
                    Surface(shape = MaterialShapes.Cookie9Sided.toShape(), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp).size(84.dp).align(Alignment.TopEnd)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("$left", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
            val badge = when {
                step.type == StepType.REST -> "Up next"
                step.type == StepType.GET_READY -> "Up next"
                guided != null && guided.sides == 2 -> if (guided.side == 0) "Left side" else "Right side"
                step.side == "Left" || step.side == "Right" -> "${step.side} side"
                else -> null
            }
            if (badge != null) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.align(Alignment.TopStart).padding(12.dp)) {
                    Text(badge, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
            tempoLabel(step.exerciseId, if (step.type == StepType.WORK) step.note else p.next?.note.orEmpty())?.let { tempo ->
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f), modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp)) {
                    Text(tempo, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                }
            }
        }

        Column(
            Modifier.weight(0.53f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(6.dp))
            val heading = when (step.type) {
                StepType.REST -> "Rest"
                StepType.GET_READY -> "Get ready"
                StepType.WORK -> ex.name
            }
            Text(heading, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            val next = p.next
            Text(
                when (step.type) {
                    StepType.WORK -> buildString {
                        if (step.setCount > 1) append("Set ${step.setIndex} of ${step.setCount}")
                        step.side?.let { if (isNotEmpty()) append(" · "); append(if (it == "each side") "each side" else "$it side") }
                        if (step.note.isNotBlank()) { if (isNotEmpty()) append(" · "); append(step.note) }
                    }
                    else -> "Next: ${ex.name}" + (next?.takeIf { it.type == StepType.WORK }?.let { n ->
                        (if (n.setCount > 1) " · set ${n.setIndex} of ${n.setCount}" else "") + " · " + WorkoutSpeech.target(n)
                    } ?: "")
                },
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            val pauseBefore by vm.pauseBefore.collectAsStateWithLifecycle()
            when {
                guided != null -> GuidedPanel(guided, p.inLeadIn, p.paused)
                step.timed -> TimerRing(p, if (step.type == StepType.WORK) 190.dp else 150.dp)
                else -> {
                    var reps by remember(p.index) { mutableIntStateOf(step.repsMax) }
                    Text(if (step.repsMin == step.repsMax) "${step.repsMax} reps" else "${step.repsMin}–${step.repsMax} reps",
                        style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    NumberStepper(reps.toDouble(), { reps = it.roundToInt() }, "done", min = 0.0, max = 100.0, label = "Reps you did")
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { m.complete(reps) }, shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().height(ButtonDefaults.LargeContainerHeight),
                        contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.LargeContainerHeight),
                    ) { Text("Done", style = ButtonDefaults.textStyleFor(ButtonDefaults.LargeContainerHeight)) }
                }
            }
            if (p.paused) {
                Spacer(Modifier.height(10.dp))
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (guided != null && guided.started) "Paused — the demo keeps going so you can watch it (drag to look around). When you resume there's a 3-2-1 and the rep you were on starts again."
                        else "Paused — watch the demo as long as you like (drag it to look around), then tap Resume.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp),
                    )
                }
            }
            if (step.type == StepType.GET_READY) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().toggleable(pauseBefore, role = Role.Switch, onValueChange = vm::setPauseBefore).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Always pause before a new exercise", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = pauseBefore, onCheckedChange = null)
                }
            }
            if (step.type == StepType.REST) {
                p.previousWorkIndex?.let { wi ->
                    val result = p.results[wi]
                    val prevStep = p.steps[wi]
                    if (prevStep.isRepWork && result != null && !result.skipped && result.reps != null) {
                        Spacer(Modifier.height(14.dp))
                        NumberStepper(result.reps.toDouble(), { m.setReps(wi, it.roundToInt()) }, if (prevStep.side == "each side") "each side" else "reps",
                            min = 0.0, max = 100.0, label = "Reps you did")
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("How did that set feel?", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    ConnectedChoice(listOf(Effort.EASY, Effort.GOOD, Effort.HARD), result?.effort ?: Effort.GOOD, { it.label }, { m.rate(wi, it) })
                }
            }
            if (step.type == StepType.WORK) {
                Spacer(Modifier.height(14.dp))
                ex.cues.forEach { c ->
                    Text("• $c", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            when {
                step.type == StepType.REST || step.type == StepType.GET_READY -> {
                    FilledTonalButton(onClick = { m.addTime(15) }, enabled = !p.paused) { Text("+15 s") }
                    Button(onClick = { m.complete() }) { Text(if (step.type == StepType.REST) "Skip rest" else "Start now") }
                }
                guided != null -> FilledTonalButton(onClick = { m.complete() }) {
                    Text(if (!guided.started) "Done — log ${guided.target}" else "Stop set · log ${guided.reps}")
                }
                step.timed -> FilledTonalButton(onClick = { m.complete() }) { Text("Done early") }
                else -> Unit
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = m::previous, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
            val shape by animateDpAsState(if (p.paused) 36.dp else 20.dp, label = "pauseShape")
            Button(
                onClick = m::togglePause,
                shape = RoundedCornerShape(shape),
                colors = if (p.paused) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                else ButtonDefaults.buttonColors(),
                modifier = Modifier.height(72.dp).widthIn(min = 152.dp),
            ) {
                Icon(if (p.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, Modifier.size(28.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (p.paused) "Resume" else "Pause")
            }
            FilledTonalIconButton(onClick = m::skip, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Skip") }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End workout?") },
            text = { Text("You can still save what you've done so far.") },
            confirmButton = { TextButton(onClick = { confirmEnd = false; m.finishEarly() }) { Text("End & review") } },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep going") } },
        )
    }
}

/** Big rep counter for a guided set, with the call that's being made right now. */
@Composable
private fun GuidedPanel(g: GuidedProgress, leadIn: Boolean, paused: Boolean) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("${g.reps}", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
        Text(" / ${g.target}", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
    }
    Text(
        when {
            paused -> "paused"
            leadIn || !g.started -> "get into position…"
            g.switching -> "switch sides"
            else -> (g.word ?: "").replaceFirstChar { it.uppercase() }.ifBlank { "reps" } + if (g.sides == 2) " · " + (if (g.side == 0) "left" else "right") else ""
        },
        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary,
    )
}

private fun tempoLabel(id: String, note: String = ""): String? = Tempos[id].withNote(note).label()

@Composable
private fun TimerRing(p: PlayerState, size: androidx.compose.ui.unit.Dp = 220.dp) {
    val step = p.current
    val total = step.seconds * 1000f
    val frac = if (total <= 0) 0f else (p.remainingMs / total).coerceIn(0f, 1f)
    val stroke = with(LocalDensity.current) { 10.dp.toPx() }
    val color = when (step.type) {
        StepType.WORK -> MaterialTheme.colorScheme.primary
        StepType.REST -> MaterialTheme.colorScheme.tertiary
        StepType.GET_READY -> MaterialTheme.colorScheme.secondary
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        CircularWavyProgressIndicator(
            progress = { frac }, modifier = Modifier.size(size), color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            stroke = Stroke(stroke, cap = StrokeCap.Round), trackStroke = Stroke(stroke, cap = StrokeCap.Round),
            wavelength = 48.dp,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(p.remainingMs / 1000.0).let { kotlin.math.ceil(it).toInt() }}", style = if (size < 200.dp) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge)
            Text(if (p.paused) "paused" else "seconds", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Summary(vm: WorkoutPlayerViewModel, p: PlayerState, name: String, startedAt: java.time.Instant, onDone: () -> Unit) {
    var rpe by remember { mutableFloatStateOf(6f) }
    // A workout started after midnight (before 4 am) counts for yesterday unless changed.
    var date by remember { mutableStateOf(be.lain.bloom.core.designsystem.defaultLogDate(startedAt)) }
    var notes by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val workSteps = p.steps.withIndex().filter { it.value.type == StepType.WORK && (it.value.block.name == "MAIN" || it.value.block.name == "CORE") }
    val done = workSteps.count { (i, _) -> p.results[i]?.skipped == false }
    val minutes = vm.manager.activeSeconds() / 60
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        ShapeIcon(MaterialShapes.SoftBurst, Icons.Rounded.EmojiEvents, size = 120.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            when { done == 0 -> "Workout ended"; done < workSteps.size -> "Good effort!"; else -> "Workout complete!" },
            style = MaterialTheme.typography.headlineMedium,
        )
        Text("$name · $minutes min · $done/${workSteps.size} sets", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Text("How hard was it overall? ${rpe.roundToInt()}/10", style = MaterialTheme.typography.titleMedium)
        Slider(value = rpe, onValueChange = { rpe = it }, valueRange = 1f..10f, steps = 8)
        Text(
            when (rpe.roundToInt()) { in 1..4 -> "Easy — time to level up soon"; in 5..7 -> "Just right"; else -> "Tough — great effort, recover well" },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Spacer(Modifier.height(16.dp))
        be.lain.bloom.core.designsystem.LogDateRow(date, { date = it }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { saving = true; vm.save(rpe.roundToInt(), notes, date, onDone) }, enabled = !saving, shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
        ) { Text("Save workout", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
        TextButton(onClick = { scope.launch { vm.discard(onDone) } }) { Text("Discard") }
    }
}
