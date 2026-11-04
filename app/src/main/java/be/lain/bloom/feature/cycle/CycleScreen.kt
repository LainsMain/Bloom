package be.lain.bloom.feature.cycle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.designsystem.BloomCard
import be.lain.bloom.core.designsystem.ConnectedChoice
import be.lain.bloom.core.designsystem.LegendKey
import be.lain.bloom.core.designsystem.dataColors
import be.lain.bloom.core.util.long
import be.lain.bloom.core.util.monthYear
import be.lain.bloom.core.util.short
import be.lain.bloom.data.cycle.CycleRepository
import be.lain.bloom.data.cycle.CycleState
import be.lain.bloom.data.cycle.Symptom
import be.lain.bloom.domain.Cycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class CycleViewModel @Inject constructor(private val repo: CycleRepository, clock: Clock) : ViewModel() {
    val today: LocalDate = LocalDate.now(clock)
    val state = repo.state(today).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun setPeriod(d: LocalDate, on: Boolean, flow: Int? = null) = viewModelScope.launch { repo.setPeriod(d, on, flow) }
    fun setSymptoms(d: LocalDate, s: Set<Symptom>) = viewModelScope.launch { repo.setSymptoms(d, s) }

    /** Marks [days] period days from [start] (never into the future). */
    fun fillPeriod(start: LocalDate, days: Int) = viewModelScope.launch {
        for (i in 0 until days) {
            val d = start.plusDays(i.toLong())
            if (d.isAfter(today)) break
            repo.setPeriod(d, true)
        }
    }
}

@Composable
fun CycleScreen(onBack: () -> Unit, vm: CycleViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var month by rememberSaveable { mutableStateOf(YearMonth.from(vm.today).toString()) }
    var selected by rememberSaveable { mutableStateOf(vm.today.toString()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cycle") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val s = state ?: return@Scaffold
        val sel = LocalDate.parse(selected)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Summary(s, vm.today) }
            item {
                Calendar(YearMonth.parse(month), s, vm.today, sel,
                    onMonth = { month = it.toString() }, onSelect = { selected = it.toString() })
            }
            item { DayEditor(sel, s, vm.today, vm) }
            item { Stats(s) }
            item {
                Text(
                    "Predictions come from your own logged cycles and are estimates, not medical advice. They're used to read your weight trend more fairly.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Summary(s: CycleState, today: LocalDate) {
    val c = s.today
    BloomCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiaryContainer) {
        val on = MaterialTheme.colorScheme.onTertiaryContainer
        if (c == null) {
            Text("Log your next period", style = MaterialTheme.typography.titleLarge, color = on)
            Text("Tap the first day of your last period in the calendar below. Logging a few cycles makes predictions personal.",
                style = MaterialTheme.typography.bodyMedium, color = on)
            return@BloomCard
        }
        Text("Cycle day ${c.day}" + (c.phase?.let { " · ${it.label}" } ?: ""), style = MaterialTheme.typography.headlineSmall, color = on)
        Text(
            when {
                c.daysUntilNext < 0 -> "Your period is ${-c.daysUntilNext} day${if (c.daysUntilNext == -1) "" else "s"} later than usual."
                c.daysUntilNext == 0 -> "Your period is expected today."
                c.daysUntilNext == 1 -> "Your period is expected tomorrow."
                else -> "Next period around ${c.nextStart.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${c.nextStart.short()} (in ${c.daysUntilNext} days)."
            },
            style = MaterialTheme.typography.bodyLarge, color = on,
        )
        c.phase?.let {
            Spacer(Modifier.height(6.dp))
            Text(it.note, style = MaterialTheme.typography.bodyMedium, color = on)
        }
    }
}

@Composable
private fun Calendar(
    month: YearMonth,
    s: CycleState,
    today: LocalDate,
    selected: LocalDate,
    onMonth: (YearMonth) -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    val period = MaterialTheme.dataColors.protein
    val predicted = androidx.compose.runtime.remember(s, today) { Cycle.predictedPeriodDays(today, s.periodDays, s.stats, cycles = 3) }
    val haptics = LocalHapticFeedback.current
    BloomCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonth(month.minusMonths(1)) }) { Icon(Icons.Rounded.ChevronLeft, "Previous month") }
            Text(month.atDay(1).monthYear(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = { onMonth(month.plusMonths(1)) }, enabled = month < YearMonth.from(today).plusMonths(2)) {
                Icon(Icons.Rounded.ChevronRight, "Next month")
            }
        }
        Row(Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach {
                Text(it.getDisplayName(TextStyle.NARROW, Locale.ENGLISH), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Spacer(Modifier.height(4.dp))
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1
        val cells = lead + month.lengthOfMonth()
        val rows = (cells + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val idx = r * 7 + c - lead
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (idx in 0 until month.lengthOfMonth()) {
                            val d = first.plusDays(idx.toLong())
                            val isPeriod = d in s.periodDays
                            val isPredicted = d in predicted
                            val future = d.isAfter(today)
                            Surface(
                                onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(d) },
                                shape = CircleShape,
                                color = when {
                                    isPeriod -> period
                                    d == selected -> MaterialTheme.colorScheme.secondaryContainer
                                    else -> androidx.compose.ui.graphics.Color.Transparent
                                },
                                border = when {
                                    isPredicted -> BorderStroke(2.dp, period.copy(alpha = 0.7f))
                                    d == today -> BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface)
                                    d == selected && isPeriod -> BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface)
                                    else -> null
                                },
                                modifier = Modifier.fillMaxSize().semantics {
                                    contentDescription = d.long() + if (isPeriod) ", period" else if (isPredicted) ", predicted period" else ""
                                },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "${d.dayOfMonth}",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal),
                                        color = when {
                                            isPeriod -> androidx.compose.ui.graphics.Color.White
                                            future -> MaterialTheme.colorScheme.onSurfaceVariant
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            LegendKey("Period", period)
            LegendKey("Predicted", period.copy(alpha = 0.7f), line = true)
            LegendKey("Today", MaterialTheme.colorScheme.onSurface, line = true)
        }
    }
}

@Composable
private fun DayEditor(date: LocalDate, s: CycleState, today: LocalDate, vm: CycleViewModel) {
    val entry = s.days.firstOrNull { it.date == date }
    val isPeriod = entry?.period == true
    val future = date.isAfter(today)
    BloomCard(Modifier.fillMaxWidth()) {
        Text(if (date == today) "Today" else date.long(), style = MaterialTheme.typography.titleMedium)
        if (future) {
            Text("You can log this day when it comes.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@BloomCard
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Period day", style = MaterialTheme.typography.titleSmall)
                Text("Pick a day in the calendar, then switch it on.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = isPeriod, onCheckedChange = { vm.setPeriod(date, it) })
        }
        // First day of a period with nothing after it yet: offer to fill the usual length in one tap.
        val isStart = isPeriod && date in s.stats.starts && Cycle.periodLength(date, s.periodDays) == 1
        val fill = minOf(s.stats.avgPeriod, (java.time.temporal.ChronoUnit.DAYS.between(date, today) + 1).toInt())
        if (isStart && fill > 1) {
            androidx.compose.material3.FilledTonalButton(onClick = { vm.fillPeriod(date, fill) }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Mark $fill days from here")
            }
        }
        if (isPeriod) {
            Spacer(Modifier.height(8.dp))
            Text("Flow", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            ConnectedChoice(listOf<Int?>(1, 2, 3), entry?.flow, { when (it) { 1 -> "Light"; 2 -> "Medium"; else -> "Heavy" } }, { vm.setPeriod(date, true, it) })
        }
        Spacer(Modifier.height(12.dp))
        Text("Symptoms", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        val current = Symptom.parse(entry?.symptoms.orEmpty())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Symptom.entries.forEach { sym ->
                FilterChip(
                    selected = sym in current,
                    onClick = { vm.setSymptoms(date, if (sym in current) current - sym else current + sym) },
                    label = { Text(sym.label) },
                )
            }
        }
    }
}

@Composable
private fun Stats(s: CycleState) {
    if (s.stats.starts.isEmpty()) return
    BloomCard(Modifier.fillMaxWidth()) {
        Text("Your cycles", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("${s.stats.avgCycle} days", style = MaterialTheme.typography.headlineSmall)
                Text(if (s.stats.lengthsKnown) "average cycle" else "assumed cycle (log 2 periods)", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text("${s.stats.avgPeriod} days", style = MaterialTheme.typography.headlineSmall)
                Text("average period", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (s.stats.cycleLengths.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Recent cycles: " + s.stats.cycleLengths.joinToString(" · ") { "$it d" }, style = MaterialTheme.typography.bodyMedium)
        }
        Text("Periods started: " + s.stats.starts.takeLast(4).joinToString(", ") { it.short() },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
