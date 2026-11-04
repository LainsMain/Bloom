package be.lain.bloom.feature.train

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.AppMessages
import be.lain.bloom.core.designsystem.BloomCard
import be.lain.bloom.core.designsystem.ConnectedChoice
import be.lain.bloom.core.designsystem.SectionHeader
import be.lain.bloom.core.designsystem.ShapeIcon
import be.lain.bloom.core.util.relative
import be.lain.bloom.data.ProfileRepository
import be.lain.bloom.data.db.WorkoutSessionEntity
import be.lain.bloom.data.training.ProgramStatus
import be.lain.bloom.data.training.TrainingRepository
import be.lain.bloom.domain.Profile
import be.lain.bloom.domain.training.Exercises
import be.lain.bloom.domain.training.Program
import be.lain.bloom.service.WorkoutManager
import be.lain.bloom.ui.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

enum class TrainMode(val label: String) { STRENGTH("Strength"), RUNNING("Running") }

@HiltViewModel
class TrainViewModel @Inject constructor(
    private val repo: TrainingRepository,
    profiles: ProfileRepository,
    private val manager: WorkoutManager,
    private val messages: AppMessages,
) : ViewModel() {
    val status = repo.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val workouts = repo.workouts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val profile = profiles.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val active = manager.active

    init { viewModelScope.launch { repo.ensureStarted() } }

    fun setPhase(i: Int) = viewModelScope.launch {
        repo.setPhase(i)
        messages.show("Now on phase ${i + 1}: ${Program.phase(i).name}")
    }

    fun delete(w: WorkoutSessionEntity) = viewModelScope.launch {
        repo.deleteWorkout(w.id)
        messages.show("Workout deleted")
    }
}

@Composable
fun TrainScreen(navigate: (Route) -> Unit, running: @Composable () -> Unit, vm: TrainViewModel = hiltViewModel()) {
    var mode by rememberSaveable { mutableStateOf(TrainMode.STRENGTH) }
    val status by vm.status.collectAsStateWithLifecycle()
    val workouts by vm.workouts.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    var picked by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(profile) {
        // On a run day, open on Running (once).
        if (!picked && profile != null) {
            picked = true
            if (LocalDate.now().dayOfWeek in profile!!.runDays) mode = TrainMode.RUNNING
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Train", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            ConnectedChoice(TrainMode.entries, mode, { it.label }, { mode = it })
        }
        when (mode) {
            TrainMode.RUNNING -> running()
            TrainMode.STRENGTH -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (active != null) {
                    item {
                        BloomCard(Modifier.fillMaxWidth(), onClick = { navigate(Route.WorkoutPlayer) }, color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Text("Workout in progress", style = MaterialTheme.typography.titleMedium)
                            Text("Tap to return to ${active!!.session.name}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                status?.let { st -> strengthContent(st, workouts, profile, navigate, vm) }
            }
        }
    }
}

private fun LazyListScope.strengthContent(
    st: ProgramStatus,
    workouts: List<WorkoutSessionEntity>,
    profile: Profile?,
    navigate: (Route) -> Unit,
    vm: TrainViewModel,
) {
    item { ProgramCard(st, vm::setPhase) }
    if (st.readyToAdvance) {
        item {
            val next = Program.phase(st.phase.index + 1)
            BloomCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text("Ready for ${next.name}?", style = MaterialTheme.typography.titleLarge)
                Text("You've put in ${st.workoutsInPhase} workouts over ${st.week - 1} weeks. ${next.summary}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.setPhase(next.index) }) { Text("Start phase ${next.index + 1}") }
            }
        }
    }
    item {
        val s = st.next
        BloomCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(MaterialShapes.Sunny, Icons.Rounded.FitnessCenter, size = 52.dp,
                    container = MaterialTheme.colorScheme.secondary, content = MaterialTheme.colorScheme.onSecondary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Next workout", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(s.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("${s.focus} · ≈ ${s.estimateMinutes()} min", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                s.blocks.filter { it.kind.name == "MAIN" || it.kind.name == "CORE" }.flatMap { it.items }.joinToString(" · ") { Exercises[it.exerciseId].name },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { navigate(Route.WorkoutPreview(st.phase.index, s.id)) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
            ) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("Start workout", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
            }
            Spacer(Modifier.height(4.dp))
            val other = st.phase.sessions.firstOrNull { it.id != s.id }
            if (other != null) {
                TextButton(onClick = { navigate(Route.WorkoutPreview(st.phase.index, other.id)) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Do ${other.name} instead")
                }
            }
        }
    }
    item { WeekCard(profile, workouts) }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { navigate(Route.ExerciseLibrary) }) {
                Icon(Icons.Rounded.MenuBook, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Exercise library")
            }
        }
    }
    if (workouts.isNotEmpty()) {
        item { SectionHeader("History") }
        items(workouts.take(20), key = { it.id }) { w -> HistoryRow(w, onDelete = { vm.delete(w) }) }
    }
}

@Composable
private fun ProgramCard(st: ProgramStatus, onSetPhase: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    BloomCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(Program.NAME, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Phase ${st.phase.index + 1} · ${st.phase.name}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    "Week ${st.week.coerceAtMost(st.phase.weeks)} of ${st.phase.weeks}${if (st.week > st.phase.weeks) "+" else ""} · ${st.workoutsInPhase} workout${if (st.workoutsInPhase == 1) "" else "s"} done",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Program options") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Change phase") }, onClick = { menu = false; picker = true })
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        LinearWavyProgressIndicator(progress = { (st.week - 1).coerceAtLeast(0).toFloat() / st.phase.weeks }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text(st.phase.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
    if (picker) {
        AlertDialog(
            onDismissRequest = { picker = false },
            title = { Text("Choose phase") },
            text = {
                Column {
                    Program.phases.forEach { ph ->
                        TextButton(onClick = { picker = false; onSetPhase(ph.index) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${ph.index + 1}. ${ph.name} (${ph.weeks} weeks)", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun WeekCard(profile: Profile?, workouts: List<WorkoutSessionEntity>) {
    val today = LocalDate.now()
    val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
    val doneDays = workouts.map { it.date }.toSet()
    BloomCard(Modifier.fillMaxWidth()) {
        Text("This week", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DayOfWeek.entries.forEach { d ->
                val date = monday.plusDays((d.value - 1).toLong())
                val strength = profile?.strengthDays?.contains(d) == true
                val run = profile?.runDays?.contains(d) == true
                val done = date in doneDays
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.getDisplayName(TextStyle.NARROW, Locale.ENGLISH), style = MaterialTheme.typography.labelMedium,
                        color = if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = if (done) MaterialShapes.Cookie6Sided.toShape() else MaterialShapes.Circle.toShape(),
                        color = when {
                            done -> MaterialTheme.colorScheme.primary
                            strength -> MaterialTheme.colorScheme.secondaryContainer
                            run -> MaterialTheme.colorScheme.tertiaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            when {
                                done -> Icon(Icons.Rounded.CheckCircle, "Done", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                                strength -> Icon(Icons.Rounded.FitnessCenter, "Strength", modifier = Modifier.size(16.dp))
                                run -> Icon(Icons.AutoMirrored.Rounded.DirectionsRun, "Run", modifier = Modifier.size(16.dp))
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(w: WorkoutSessionEntity, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(w.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "${w.date.relative(LocalDate.now())} · ${w.activeSeconds / 60} min · ${w.setsDone}/${w.setsPlanned} sets · ${w.kcal} kcal" +
                    (w.rpe?.let { " · effort $it/10" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { confirm = true }) { Icon(Icons.Rounded.DeleteOutline, "Delete workout") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete this workout?") },
            confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Delete") } },
            dismissButton = { OutlinedButton(onClick = { confirm = false }) { Text("Keep") } },
        )
    }
}
