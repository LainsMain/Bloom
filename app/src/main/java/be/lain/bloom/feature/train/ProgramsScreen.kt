package be.lain.bloom.feature.train

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import be.lain.bloom.core.designsystem.ShapeIcon
import be.lain.bloom.data.ProfileRepository
import be.lain.bloom.data.SettingsRepository
import be.lain.bloom.data.training.TrainingRepository
import be.lain.bloom.domain.training.Programs
import be.lain.bloom.domain.training.TrainingProgram
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ProgramsViewModel @Inject constructor(
    private val repo: TrainingRepository,
    private val settings: SettingsRepository,
    profiles: ProfileRepository,
    private val messages: AppMessages,
) : ViewModel() {
    val status = repo.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val profile = profiles.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Saved phase per program the user has tried before. */
    val saved = MutableStateFlow<Map<String, Int>>(emptyMap())

    init {
        viewModelScope.launch {
            saved.value = Programs.all.mapNotNull { p -> settings.programProgress(p.id).let { (ph, start) -> if (start != null) p.id to ph else null } }.toMap()
        }
    }

    fun switchTo(p: TrainingProgram, onDone: () -> Unit) = viewModelScope.launch {
        repo.setProgram(p.id)
        messages.show("Now training with ${p.name}")
        onDone()
    }
}

private val shapes = listOf(MaterialShapes.Sunny, MaterialShapes.Flower, MaterialShapes.Cookie7Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Puffy, MaterialShapes.Cookie9Sided)

@Composable
fun ProgramsScreen(onBack: () -> Unit, vm: ProgramsViewModel = hiltViewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    val current = status?.program?.id

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Programs") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "All at home, all with demo videos. Each program keeps its own progress, so you can switch and come back.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(Programs.all, key = { it.id }) { p ->
                val isCurrent = p.id == current
                val open = expanded == p.id
                BloomCard(
                    Modifier.fillMaxWidth(),
                    color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    onClick = { expanded = if (open) null else p.id },
                ) {
                    val on = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ShapeIcon(shapes[Programs.all.indexOf(p) % shapes.size], Icons.Rounded.FitnessCenter, size = 48.dp,
                            container = MaterialTheme.colorScheme.secondary, content = MaterialTheme.colorScheme.onSecondary)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleLarge, color = on)
                            Text(p.tagline, style = MaterialTheme.typography.bodyMedium, color = on)
                        }
                        Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, if (open) "Less" else "More", tint = on)
                    }
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(p.level, "${p.sessionsPerWeek}× a week", p.minutes(), "${p.phases.size} phases").forEach { Pill(it) }
                    }
                    if (isCurrent) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp))
                            Text("Your program · phase ${status!!.phase.index + 1}, ${status!!.phase.name}", style = MaterialTheme.typography.labelLarge, color = on)
                        }
                    } else saved[p.id]?.let { ph ->
                        Spacer(Modifier.height(8.dp))
                        Text("You were on phase ${ph + 1}, ${p.phase(ph).name}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AnimatedVisibility(open) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            Text(p.description, style = MaterialTheme.typography.bodyMedium, color = on)
                            Spacer(Modifier.height(8.dp))
                            Text("Focus: ${p.focus.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = on)
                            Text("You'll need: ${p.equipment}", style = MaterialTheme.typography.bodySmall, color = on)
                            Spacer(Modifier.height(12.dp))
                            p.phases.forEach { ph ->
                                Text("${ph.index + 1}. ${ph.name} · ${ph.weeks} weeks", style = MaterialTheme.typography.titleSmall, color = on)
                                Text(ph.summary, style = MaterialTheme.typography.bodySmall, color = on)
                                Text(ph.sessions.joinToString(" · ") { "${it.name} (≈ ${it.estimateMinutes()} min)" }, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                            }
                            if (!isCurrent) {
                                Button(onClick = { confirm = p.id }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (saved[p.id] != null) "Continue ${p.name}" else "Start ${p.name}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { id ->
        val p = Programs.byId(id)
        val days = profile?.strengthDays?.sorted().orEmpty()
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Switch to ${p.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        (saved[p.id]?.let { "You'll pick up at phase ${it + 1}, ${p.phase(it).name}." } ?: "You'll start at phase 1, ${p.phases.first().name}.") +
                            (status?.let { " Your progress in ${it.program.name} is kept for when you come back." } ?: ""),
                    )
                    if (days.isNotEmpty() && days.size != p.sessionsPerWeek) {
                        Text(
                            "This program is planned for ${p.sessionsPerWeek} sessions a week; you train strength on ${days.size} days (" +
                                days.joinToString { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) } + "). You can change your days in your profile.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { confirm = null; vm.switchTo(p, onDone = onBack) }) { Text("Switch") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Pill(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
