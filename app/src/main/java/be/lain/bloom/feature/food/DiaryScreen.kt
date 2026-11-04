package be.lain.bloom.feature.food

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.AppMessages
import be.lain.bloom.core.designsystem.BloomCard
import be.lain.bloom.core.designsystem.ConnectedChoice
import be.lain.bloom.core.designsystem.MacroMeter
import be.lain.bloom.core.designsystem.MeterBar
import be.lain.bloom.core.designsystem.dataColors
import be.lain.bloom.core.util.fmt
import be.lain.bloom.core.util.long
import be.lain.bloom.data.ProfileRepository
import be.lain.bloom.data.activeOn
import be.lain.bloom.data.db.DiaryEntryEntity
import be.lain.bloom.data.food.FoodRepository
import be.lain.bloom.domain.DayStatus
import be.lain.bloom.domain.Meal
import be.lain.bloom.domain.Nutrients
import be.lain.bloom.domain.Targets
import be.lain.bloom.ui.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class DiaryState(
    val loaded: Boolean = false,
    val date: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val entries: List<DiaryEntryEntity> = emptyList(),
    val targets: Targets? = null,
    val loggedDays: Set<LocalDate> = emptySet(),
    val status: DayStatus? = null,
) {
    val totals: Nutrients get() = Nutrients.sum(entries.map { it.nutrients })
    fun meal(m: Meal) = entries.filter { it.meal == m }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DiaryViewModel @Inject constructor(
    private val repo: FoodRepository,
    profiles: ProfileRepository,
    private val messages: AppMessages,
) : ViewModel() {
    private val date = MutableStateFlow(repo.today())

    val state = date.flatMapLatest { d ->
        val monday = d.minusDays((d.dayOfWeek.value - 1).toLong())
        combine(
            repo.observeDay(d), profiles.plans, repo.observeLoggedDays(monday, monday.plusDays(6)), repo.observeDayStatus(d),
        ) { entries, plans, logged, status ->
            DiaryState(true, d, repo.today(), entries, plans.activeOn(d)?.targets, logged.toSet(), status?.status)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryState())

    fun select(d: LocalDate) { date.value = d }

    fun delete(e: DiaryEntryEntity) = viewModelScope.launch {
        repo.deleteEntry(e.id)
        messages.show("Removed ${e.name}", "Undo") { repo.restoreEntry(e) }
    }

    fun copyFromYesterday(m: Meal) = viewModelScope.launch {
        val d = date.value
        val n = repo.copyMeal(d.minusDays(1), m, d, m)
        messages.show(if (n == 0) "Nothing logged for ${m.label.lowercase()} yesterday" else "Copied $n item${if (n == 1) "" else "s"} from yesterday")
    }

    fun saveTemplate(name: String, m: Meal) = viewModelScope.launch {
        val items = state.value.meal(m)
        if (items.isEmpty()) return@launch
        repo.saveTemplate(name, items)
        messages.show("Saved “$name” to your meals")
    }

    fun setStatus(s: DayStatus?) = viewModelScope.launch { repo.setDayStatus(date.value, s) }
}

@Composable
fun DiaryScreen(navigate: (Route) -> Unit, vm: DiaryViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var saveMeal by remember { mutableStateOf<Meal?>(null) }
    if (!s.loaded) return
    val dateArg = s.date.toEpochDay()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (s.date == s.today) "Today" else s.date.long(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Food", style = MaterialTheme.typography.displaySmall)
                }
                IconButton(onClick = { navigate(Route.Recipes) }) { Icon(Icons.Rounded.MenuBook, "Recipes") }
                IconButton(onClick = { navigate(Route.Scan(dateArg, Meal.forTime(java.time.LocalTime.now()))) }) {
                    Icon(Icons.Rounded.QrCodeScanner, "Scan barcode")
                }
            }
        }
        item { WeekStrip(s.date, s.today, s.loggedDays, vm::select) }
        item { DaySummary(s) }
        Meal.entries.forEach { meal ->
            val items = s.meal(meal)
            item(key = "h-$meal") {
                MealHeader(
                    meal = meal, kcal = items.sumOf { it.nutrients.kcal ?: 0.0 }, hasItems = items.isNotEmpty(),
                    onAdd = { navigate(Route.FoodSearch(dateArg, meal)) },
                    onCopy = { vm.copyFromYesterday(meal) },
                    onSaveTemplate = { saveMeal = meal },
                )
            }
            items(items, key = { "e-${it.id}" }) { e -> EntryRow(e, onClick = { navigate(Route.EditEntry(e.id)) }, onDelete = { vm.delete(e) }) }
        }
        item {
            BloomCard(Modifier.fillMaxWidth()) {
                Text("How complete is this day's log?", style = MaterialTheme.typography.titleMedium)
                Text("Your weekly review only trusts days you mark complete.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                ConnectedChoice(
                    options = listOf<DayStatus?>(DayStatus.COMPLETE, DayStatus.PARTIAL, DayStatus.SKIPPED),
                    selected = s.status,
                    label = { when (it) { DayStatus.COMPLETE -> "Complete"; DayStatus.PARTIAL -> "Partly"; else -> "Not logged" } },
                    onSelect = { vm.setStatus(if (it == s.status) null else it) },
                )
            }
        }
    }

    saveMeal?.let { m ->
        var name by remember { mutableStateOf("My ${m.label.lowercase()}") }
        AlertDialog(
            onDismissRequest = { saveMeal = null },
            title = { Text("Save as a meal") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = { vm.saveTemplate(name, m); saveMeal = null }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { saveMeal = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DaySummary(s: DiaryState) {
    val t = s.targets ?: return
    val n = s.totals
    val eaten = n.kcal ?: 0.0
    val c = MaterialTheme.dataColors
    BloomCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(eaten.fmt(0), style = MaterialTheme.typography.displaySmall)
            Text(" / ${t.kcal.fmt()} kcal", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp).weight(1f))
            val left = t.kcal - eaten
            Text(if (left >= 0) "${left.fmt(0)} left" else "${(-left).fmt(0)} over", style = MaterialTheme.typography.titleMedium,
                color = if (left >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
        }
        Spacer(Modifier.height(8.dp))
        MeterBar((eaten / t.kcal).toFloat(), MaterialTheme.colorScheme.primary, height = 10.dp)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            MacroMeter("Protein", n.protein ?: 0.0, t.proteinG, c.protein, Modifier.weight(1f))
            MacroMeter("Carbs", n.carbs ?: 0.0, t.carbsG, c.carbs, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            MacroMeter("Fat", n.fat ?: 0.0, t.fatG, c.fat, Modifier.weight(1f))
            MacroMeter("Fibre", n.fiber ?: 0.0, t.fiberG, c.fiber, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MealHeader(meal: Meal, kcal: Double, hasItems: Boolean, onAdd: () -> Unit, onCopy: () -> Unit, onSaveTemplate: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(meal.label, style = MaterialTheme.typography.titleLarge)
            if (hasItems) Text("${kcal.fmt(0)} kcal", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 14.dp)) {
            Icon(Icons.Rounded.Add, null)
            Text("Add")
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "${meal.label} options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Copy from yesterday") }, onClick = { menu = false; onCopy() })
                if (hasItems) DropdownMenuItem(text = { Text("Save as a meal") }, onClick = { menu = false; onSaveTemplate() })
            }
        }
    }
}

@Composable
private fun EntryRow(e: DiaryEntryEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    val dismiss = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete() },
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Rounded.DeleteOutline, "Delete", tint = MaterialTheme.colorScheme.onErrorContainer) }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    amountText(e) + (e.brand?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            Text(e.nutrients.kcal?.fmt(0) ?: "?", style = MaterialTheme.typography.titleMedium)
            Text(" kcal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun amountText(e: DiaryEntryEntity): String = when (e.unit) {
    "kcal" -> "Quick add"
    "g", "ml" -> "${e.amount.fmt(0)} ${e.unit}"
    else -> "${e.amount.toBigDecimal().stripTrailingZeros().toPlainString()} × ${e.unit} (${e.grams.fmt(0)} g)"
}
