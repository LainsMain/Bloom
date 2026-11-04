package be.lain.bloom.feature.today

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.lain.bloom.core.designsystem.BloomCard
import be.lain.bloom.core.designsystem.CalorieRing
import be.lain.bloom.core.designsystem.MacroMeter
import be.lain.bloom.core.designsystem.MeterBar
import be.lain.bloom.core.designsystem.ShapeIcon
import be.lain.bloom.core.designsystem.dataColors
import be.lain.bloom.core.util.fmt
import be.lain.bloom.core.util.long
import be.lain.bloom.core.util.signed
import be.lain.bloom.feature.body.LogWeightSheet
import be.lain.bloom.ui.Route
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun TodayScreen(navigate: (Route) -> Unit, openTab: (Route) -> Unit, vm: TodayViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var fabOpen by rememberSaveable { mutableStateOf(false) }
    var weightSheet by rememberSaveable { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    LifecycleResumeEffect(Unit) {
        vm.refreshDate()
        vm.loadProteinIdeas()
        onPauseOrDispose { }
    }
    BackHandler(enabled = fabOpen) { fabOpen = false }
    if (!state.loaded) return

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Header(
                    greeting = state.greeting,
                    name = state.profile?.name.orEmpty(),
                    date = state.date.long(),
                    onAvatar = { navigate(Route.Settings) },
                )
            }
            item {
                val due by vm.reviewDue.collectAsStateWithLifecycle()
                if (due) ReviewBanner(onStart = { navigate(Route.WeeklyReview) })
            }
            item {
                val brk by vm.dietBreak.collectAsStateWithLifecycle()
                brk?.takeIf { !it.today.isAfter(it.end) }?.let { DietBreakCard(it, state.targets?.kcal, onEnd = vm::endDietBreak) }
            }
            item { EnergyCard(state, onClick = { openTab(Route.Food) }) }
            item {
                val ideas by vm.proteinIdeas.collectAsStateWithLifecycle()
                val t = state.targets
                val left = (t?.proteinG ?: 0) - (state.protein ?: 0.0)
                // From late morning, once there's a real gap; never nag first thing.
                if (t != null && left >= 15 && java.time.LocalTime.now().hour >= 11) {
                    ProteinCard(state.protein ?: 0.0, t.proteinG, ideas, onAdd = vm::logProtein, onMore = {
                        navigate(Route.FoodSearch(state.date.toEpochDay(), vm.defaultMeal()))
                    })
                }
            }
            item {
                val program by vm.program.collectAsStateWithLifecycle()
                val runPlan by vm.runPlan.collectAsStateWithLifecycle()
                val done by vm.doneToday.collectAsStateWithLifecycle()
                TrainingCard(state, program, runPlan, done, onStartStrength = { p -> navigate(Route.WorkoutPreview(p.phase.index, p.next.id, p.program.id)) },
                    onStartRun = { r -> navigate(Route.RunActive(r.key, r.plan.name)) },
                    onOpenRun = { id -> navigate(Route.RunDetail(id)) },
                    onOpenTrain = { openTab(Route.Train) })
            }
            item {
                val hc by vm.hcStatus.collectAsStateWithLifecycle()
                val steps by vm.steps.collectAsStateWithLifecycle()
                val sessions by vm.otherSessions.collectAsStateWithLifecycle()
                if (hc == be.lain.bloom.data.health.HcStatus.CONNECTED) StepsCard(steps, sessions, state.settings.stepGoal)
            }
            item {
                val cs by vm.cycleState.collectAsStateWithLifecycle()
                cs?.takeIf { it.enabled }?.let { c ->
                    CycleCard(c, state.date, onOpen = { navigate(Route.Cycle) }, onPeriod = vm::setPeriodToday)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WeightCard(state, Modifier.weight(1f), onClick = { weightSheet = true }, onOpenProgress = { openTab(Route.Progress) })
                    WaterCard(state, Modifier.weight(1f), onAdd = { vm.addWater(state.settings.waterServingMl) }, onUndo = vm::undoWater)
                }
            }
        }

        FloatingActionButtonMenu(
            expanded = fabOpen,
            modifier = Modifier.align(Alignment.BottomEnd),
            button = {
                ToggleFloatingActionButton(
                    checked = fabOpen,
                    onCheckedChange = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        fabOpen = it
                    },
                    modifier = Modifier.semantics { contentDescription = if (fabOpen) "Close quick actions" else "Quick actions" },
                ) {
                    Icon(if (fabOpen) Icons.Rounded.Close else Icons.Rounded.Add, contentDescription = null)
                }
            },
        ) {
            FloatingActionButtonMenuItem(
                onClick = { fabOpen = false; navigate(Route.FoodSearch(state.date.toEpochDay(), vm.defaultMeal())) },
                icon = { Icon(Icons.Rounded.Restaurant, null) },
                text = { Text("Log food") },
            )
            FloatingActionButtonMenuItem(
                onClick = { fabOpen = false; navigate(Route.QuickLog(state.date.toEpochDay(), vm.defaultMeal(), camera = true)) },
                icon = { Icon(Icons.Rounded.PhotoCamera, null) },
                text = { Text("Snap a meal") },
            )
            FloatingActionButtonMenuItem(
                onClick = { fabOpen = false; navigate(Route.Scan(state.date.toEpochDay(), vm.defaultMeal())) },
                icon = { Icon(Icons.Rounded.QrCodeScanner, null) },
                text = { Text("Scan barcode") },
            )
            FloatingActionButtonMenuItem(
                onClick = { fabOpen = false; weightSheet = true },
                icon = { Icon(Icons.Rounded.MonitorWeight, null) },
                text = { Text("Log weight") },
            )
            FloatingActionButtonMenuItem(
                onClick = { fabOpen = false; vm.addWater(state.settings.waterServingMl) },
                icon = { Icon(Icons.Rounded.WaterDrop, null) },
                text = { Text("Add ${state.settings.waterServingMl} ml water") },
            )
        }
    }

    if (weightSheet) {
        LogWeightSheet(
            initialKg = state.latestWeight?.kg ?: state.profile?.startWeightKg ?: 70.0,
            onDismiss = { weightSheet = false },
            onSave = vm::logWeight,
        )
    }
}

@Composable
private fun Header(greeting: String, name: String, date: String, onAvatar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(date, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                if (name.isBlank()) greeting else "$greeting,\n$name",
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Surface(
            onClick = onAvatar,
            shape = MaterialShapes.Cookie9Sided.toShape(),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.size(52.dp).semantics { contentDescription = "Profile and settings" },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ProteinCard(eaten: Double, target: Int, ideas: List<be.lain.bloom.data.food.ProteinIdea>, onAdd: (be.lain.bloom.data.food.ProteinIdea) -> Unit, onMore: () -> Unit) {
    val left = (target - eaten).coerceAtLeast(0.0)
    val color = MaterialTheme.dataColors.protein
    BloomCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(MaterialShapes.Circle.toShape()).background(color))
            Spacer(Modifier.width(8.dp))
            Text("Protein boost", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("${eaten.toInt()} / $target g", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(4.dp))
        Text("About ${left.toInt()} g to go today", style = MaterialTheme.typography.titleMedium)
        Text("Protein keeps you full and protects muscle while you lose fat.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        ideas.forEach { idea ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(idea.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("${idea.portionLabel} · ${idea.protein.toInt()} g protein · ${idea.kcal.toInt()} kcal",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FilledTonalButton(onClick = { onAdd(idea) }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Text("Add")
                }
            }
        }
        androidx.compose.material3.TextButton(onClick = onMore) { Text("Find something else") }
    }
}

@Composable
private fun DietBreakCard(b: be.lain.bloom.data.review.DietBreakState, kcal: Int?, onEnd: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    BloomCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiaryContainer, contentPadding = 16.dp) {
        val on = MaterialTheme.colorScheme.onTertiaryContainer
        if (b.upcoming) {
            Text("Diet break starts tomorrow", style = MaterialTheme.typography.titleMedium, color = on)
            Text(
                "Today stays on your normal target. From tomorrow you eat at maintenance for ${b.length / 7} weeks. " +
                    "A little weight back on the scale is water and stored carbs, not fat.",
                style = MaterialTheme.typography.bodyMedium, color = on,
            )
        } else {
            Text("Diet break · day ${b.day} of ${b.length}", style = MaterialTheme.typography.titleMedium, color = on)
            Text(
                (kcal?.let { "Eat around ${it.fmt()} kcal a day, your maintenance. " } ?: "") +
                    "A little weight back on the scale is water and stored carbs, not fat. " +
                    if (b.daysLeft <= 1) "Back to your plan tomorrow." else "Back to your plan in ${b.daysLeft} days.",
                style = MaterialTheme.typography.bodyMedium, color = on,
            )
        }
        androidx.compose.material3.TextButton(onClick = { confirm = true }) { Text(if (b.upcoming) "Cancel break" else "End break early") }
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (b.upcoming) "Cancel your diet break?" else "End your diet break?") },
            text = { Text(if (b.upcoming) "You'll stay on your fat-loss target." else "Your fat-loss target comes back from today.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirm = false; onEnd() }) { Text("End break") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Keep going") } },
        )
    }
}

@Composable
private fun ReviewBanner(onStart: () -> Unit) {
    BloomCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiaryContainer, onClick = onStart) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(
                MaterialShapes.Flower, Icons.Rounded.EventAvailable, size = 52.dp,
                container = MaterialTheme.colorScheme.tertiary, content = MaterialTheme.colorScheme.onTertiary,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Your weekly review is ready", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text("About 3 minutes. Your plan adapts to how your week really went.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.Button(
            onClick = onStart, modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary,
            ),
        ) { Text("Start review") }
    }
}

@Composable
private fun CycleCard(c: be.lain.bloom.data.cycle.CycleState, date: java.time.LocalDate, onOpen: () -> Unit, onPeriod: (Boolean) -> Unit) {
    val pink = MaterialTheme.dataColors.protein
    val today = c.today
    val periodToday = date in c.periodDays
    BloomCard(Modifier.fillMaxWidth(), onClick = onOpen, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).clip(MaterialShapes.Flower.toShape()).background(pink))
            Spacer(Modifier.width(8.dp))
            Text("Cycle", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            today?.let { Text("Day ${it.day}", style = MaterialTheme.typography.labelLarge) }
        }
        Spacer(Modifier.height(6.dp))
        if (today == null) {
            Text("Log your period so your coach can tell water weight from fat loss.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                when {
                    periodToday -> "Period · day ${be.lain.bloom.domain.Cycle.periodLength(c.stats.starts.last(), c.periodDays).coerceAtMost(today.day)}"
                    today.daysUntilNext < 0 -> "Period ${-today.daysUntilNext} day${if (today.daysUntilNext == -1) "" else "s"} late"
                    today.daysUntilNext == 0 -> "Period expected today"
                    today.daysUntilNext == 1 -> (today.phase?.label ?: "Cycle") + " · period expected tomorrow"
                    else -> (today.phase?.label ?: "Cycle") + " · period in ${today.daysUntilNext} days"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (today.waterRetention) {
                Text("Water weight is common now, so a higher reading on the scale is expected.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (periodToday) {
            androidx.compose.material3.OutlinedButton(onClick = { onPeriod(false) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Text("Not a period day")
            }
        } else {
            FilledTonalButton(onClick = { onPeriod(true) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Text(if (today != null && today.day <= 3 && c.stats.starts.isNotEmpty()) "Period today" else "Period started today")
            }
        }
    }
}

@Composable
private fun EnergyCard(state: TodayState, onClick: () -> Unit) {
    val t = state.targets ?: return
    val c = MaterialTheme.dataColors
    BloomCard(Modifier.fillMaxWidth(), onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            CalorieRing(eaten = state.eatenKcal, target = t.kcal)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            MacroMeter("Protein", state.protein, t.proteinG, c.protein, Modifier.weight(1f))
            MacroMeter("Carbs", state.carbs, t.carbsG, c.carbs, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            MacroMeter("Fat", state.fat, t.fatG, c.fat, Modifier.weight(1f))
            MacroMeter("Fibre", state.fiber, t.fiberG, c.fiber, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TrainingCard(
    state: TodayState,
    program: be.lain.bloom.data.training.ProgramStatus?,
    runPlan: be.lain.bloom.data.training.ActivePlan?,
    done: DoneToday,
    onStartStrength: (be.lain.bloom.data.training.ProgramStatus) -> Unit,
    onStartRun: (be.lain.bloom.data.training.ActivePlan) -> Unit,
    onOpenRun: (Long) -> Unit,
    onOpenTrain: () -> Unit,
) {
    fun runLine(r: be.lain.bloom.data.db.RunSessionEntity) =
        if (r.distanceUnreliable) "${r.title} · ${be.lain.bloom.domain.running.Geo.formatClock(r.movingSec)}"
        else "${r.title} · ${"%.2f".format(r.distanceM / 1000)} km in ${be.lain.bloom.domain.running.Geo.formatClock(r.movingSec)}"
    fun workoutLine(w: be.lain.bloom.data.db.WorkoutSessionEntity) = "${w.name} · ${w.activeSeconds / 60} min · ${w.setsDone}/${w.setsPlanned} sets"
    val runDone = done.run.takeIf { state.training == TrainingKind.RUN }
    val workoutDone = done.workout.takeIf { state.training == TrainingKind.STRENGTH }
    val isDone = runDone != null || workoutDone != null
    // A session that wasn't today's plan, e.g. a run on a rest day.
    val extra = when (state.training) {
        TrainingKind.RUN -> done.workout?.let { "Also done today: ${workoutLine(it)}" }
        TrainingKind.STRENGTH -> done.run?.let { "Also done today: ${runLine(it)}" }
        TrainingKind.REST -> listOfNotNull(done.run?.let(::runLine), done.workout?.let(::workoutLine))
            .takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "Done today: ")
    }
    val (title, body, icon, shape) = when {
        runDone != null -> Quad("Run done ✓", runLine(runDone) + ". Lovely work!", Icons.Rounded.CheckCircle, MaterialShapes.Clover4Leaf)
        workoutDone != null -> Quad("Workout done ✓", workoutLine(workoutDone) + ". Lovely work!", Icons.Rounded.CheckCircle, MaterialShapes.Sunny)
        else -> when (state.training) {
        TrainingKind.STRENGTH -> Quad("Strength day", program?.let { "${it.next.name} · ${it.next.focus} · ≈ ${it.next.estimateMinutes()} min" }
            ?: "Full-body bodyweight session · about 30 min", Icons.Rounded.FitnessCenter, MaterialShapes.Sunny)
        TrainingKind.RUN -> Quad(
            "Run day",
            runPlan?.let { "${it.plan.name} · ≈ ${it.plan.totalSeconds / 60} min" } ?: "Easy intervals — conversation pace",
            Icons.AutoMirrored.Rounded.DirectionsRun, MaterialShapes.Clover4Leaf,
        )
        TrainingKind.REST -> Quad("Rest day", "Recovery is when your body adapts. A walk is a lovely bonus.", Icons.Rounded.SelfImprovement, MaterialShapes.Puffy)
        }
    }
    BloomCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer, onClick = onOpenTrain) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(
                shape, icon, size = 56.dp,
                container = MaterialTheme.colorScheme.secondary,
                content = MaterialTheme.colorScheme.onSecondary,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Today", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        extra?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        if (runDone != null) {
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.OutlinedButton(onClick = { onOpenRun(runDone.id) }, modifier = Modifier.fillMaxWidth()) { Text("View run") }
        } else if (state.training == TrainingKind.RUN) {
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Button(onClick = { runPlan?.let(onStartRun) ?: onOpenTrain() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Rounded.DirectionsRun, null); Spacer(Modifier.width(8.dp)); Text("Start my run")
            }
        }
        if (state.training == TrainingKind.STRENGTH && program != null && workoutDone == null) {
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.Button(onClick = { onStartStrength(program) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.FitnessCenter, null); Spacer(Modifier.width(8.dp)); Text("Start workout")
            }
        }
        state.nextTraining?.takeIf { state.training == TrainingKind.REST || isDone }?.let { (day, kind) ->
            Spacer(Modifier.height(12.dp))
            Text(
                "Next: ${if (kind == TrainingKind.STRENGTH) "strength" else "run-walk"} on ${day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** 0 → "0", 250 → "0.25", 1500 → "1.5" */
private fun litres(ml: Int): String = (ml / 1000.0).toBigDecimal().stripTrailingZeros().toPlainString()

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
private fun WeightCard(state: TodayState, modifier: Modifier, onClick: () -> Unit, onOpenProgress: () -> Unit) {
    BloomCard(modifier, onClick = if (state.weighedToday) onOpenProgress else onClick, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.MonitorWeight, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Weight", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(state.trendKg?.fmt() ?: "–", style = MaterialTheme.typography.headlineMedium)
            Text(" kg", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp))
        }
        Text(
            state.weeklyRate?.let { "${it.signed(2)} kg/week" } ?: "Trend",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        if (state.weighedToday) {
            Text("Weighed in today ✓", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            FilledTonalButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Weigh in") }
        }
    }
}

@Composable
private fun WaterCard(state: TodayState, modifier: Modifier, onAdd: () -> Unit, onUndo: () -> Unit) {
    val goal = state.settings.waterGoalMl
    val water = MaterialTheme.dataColors.water
    BloomCard(modifier, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp).clip(MaterialShapes.Circle.toShape()).background(water.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.WaterDrop, null, tint = water, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text("Water", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f))
            if (state.waterMl > 0) {
                IconButton(onClick = onUndo, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, "Undo last water", modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(litres(state.waterMl), style = MaterialTheme.typography.headlineMedium)
            Text(" / ${(goal / 1000.0).fmt(1)} L", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        MeterBar(state.waterMl.toFloat() / goal, water)
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 14.dp)) {
            Text("+${state.settings.waterServingMl} ml")
        }
    }
}

@Composable
private fun StepsCard(day: be.lain.bloom.data.health.HcDailyEntity?, sessions: List<be.lain.bloom.data.health.HcSessionEntity>, goal: Int) {
    val steps = day?.steps ?: 0L
    BloomCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Steps", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("goal ${goal.fmt()}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Text(steps.toInt().fmt(), style = MaterialTheme.typography.headlineMedium)
        if ((day?.stepsDuringRuns ?: 0) > 0) {
            Text("incl. ${day!!.stepsDuringRuns.toInt().fmt()} during your run", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        MeterBar(steps.toFloat() / goal, MaterialTheme.colorScheme.primary)
        sessions.forEach { s ->
            Spacer(Modifier.height(8.dp))
            Text(
                "${s.title} · ${java.time.Duration.between(s.start, s.end).toMinutes()} min" +
                    (s.distanceM?.takeIf { it > 0 }?.let { " · ${(it / 1000).fmt(1)} km" } ?: "") +
                    (s.kcal?.takeIf { it > 0 }?.let { " · ${it.fmt(0)} kcal" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
