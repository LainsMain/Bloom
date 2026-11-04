package be.lain.bloom.feature.review

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EventNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.lain.bloom.core.designsystem.BloomCard
import be.lain.bloom.core.designsystem.ColumnChart
import be.lain.bloom.core.designsystem.ColumnDatum
import be.lain.bloom.core.designsystem.ConnectedChoice
import be.lain.bloom.core.designsystem.DecimalField
import be.lain.bloom.core.designsystem.MarkdownText
import be.lain.bloom.core.designsystem.NumberStepper
import be.lain.bloom.core.designsystem.ShapeIcon
import be.lain.bloom.core.designsystem.StatTile
import be.lain.bloom.core.designsystem.dataColors
import be.lain.bloom.core.util.fmt
import be.lain.bloom.core.util.relative
import be.lain.bloom.core.util.short
import be.lain.bloom.core.util.signed
import be.lain.bloom.data.review.CheckIn
import be.lain.bloom.data.review.WeekData
import be.lain.bloom.domain.Adaptive
import be.lain.bloom.domain.DayStatus
import be.lain.bloom.feature.coach.ActionCard
import be.lain.bloom.feature.coach.ChatItem
import be.lain.bloom.feature.coach.RunIndicator
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun WeeklyReviewScreen(onClose: () -> Unit, vm: WeeklyReviewViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    BackHandler { if (!vm.back()) onClose() }
    val steps = ReviewStep.entries
    val progress by animateFloatAsState(
        (steps.indexOf(ui.step) + 1f) / steps.size, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "progress",
    )

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close review") }
            LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f).padding(end = 20.dp))
        }
        val week = ui.week
        if (ui.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularWavyProgressIndicator() }
            return@Column
        }
        if (week == null) {
            Text("Finish setting up your profile first.", modifier = Modifier.padding(24.dp))
            return@Column
        }
        if (ui.notDue && !ui.saved) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                ShapeIcon(MaterialShapes.Flower, Icons.Rounded.CheckCircle, size = 96.dp)
                Spacer(Modifier.height(16.dp))
                Text("No review due right now", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    "Reviews happen once a week, after a full week of logging. You'll get the next one on your review day.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onClose) { Text("OK") }
            }
            return@Column
        }
        AnimatedContent(
            targetState = ui.step,
            transitionSpec = {
                val dir = if (ui.forward) 1 else -1
                (slideInHorizontally { it * dir / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir / 3 } + fadeOut())
            },
            modifier = Modifier.weight(1f),
            label = "review-step",
        ) { step ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Step ${steps.indexOf(step) + 1} of ${steps.size}",
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                )
                Text(step.title, style = MaterialTheme.typography.headlineMedium)
                when (step) {
                    ReviewStep.WEEK -> WeekStep(week, ui)
                    ReviewStep.DAYS -> DaysStep(week, ui, vm::setStatus)
                    ReviewStep.CHECKIN -> CheckInStep(week, ui, vm)
                    ReviewStep.TARGET -> ui.result?.let { TargetStep(it, ui) }
                    ReviewStep.COACH -> CoachStep(ui, vm)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        BottomActions(ui, vm, onClose)
    }
}

@Composable
private fun BottomActions(ui: ReviewUi, vm: WeeklyReviewViewModel, onClose: () -> Unit) {
    val big = ButtonDefaults.MediumContainerHeight
    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (ui.step) {
            ReviewStep.WEEK, ReviewStep.DAYS, ReviewStep.CHECKIN -> Button(
                onClick = vm::next, enabled = !ui.busy, shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(big), contentPadding = ButtonDefaults.contentPaddingFor(big),
            ) { Text(if (ui.step == ReviewStep.CHECKIN) "See my numbers" else "Continue", style = ButtonDefaults.textStyleFor(big)) }

            ReviewStep.TARGET -> {
                val r = ui.result ?: return@Column
                val starts = if (ui.effective != null && ui.effective == ui.week?.reviewDate) "today" else "tomorrow"
                if (r.change == 0) {
                    Button(
                        onClick = { vm.decide(true) }, enabled = !ui.busy, shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().height(big), contentPadding = ButtonDefaults.contentPaddingFor(big),
                    ) { Text("Continue", style = ButtonDefaults.textStyleFor(big)) }
                } else {
                    Button(
                        onClick = { vm.decide(true) }, enabled = !ui.busy, shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.fillMaxWidth().height(big), contentPadding = ButtonDefaults.contentPaddingFor(big),
                    ) { Text("Use ${r.proposedKcal.fmt()} kcal from $starts", style = ButtonDefaults.textStyleFor(big)) }
                    OutlinedButton(onClick = { vm.decide(false) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Keep ${r.currentKcal.fmt()} kcal")
                    }
                }
            }

            ReviewStep.COACH -> Button(
                onClick = onClose, shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(big), contentPadding = ButtonDefaults.contentPaddingFor(big),
            ) { Text("Done", style = ButtonDefaults.textStyleFor(big)) }
        }
    }
}

// ---------- Step 1: the week at a glance ----------

@Composable
private fun ColumnScope.WeekStep(week: WeekData, ui: ReviewUi) {
    Text(
        "${week.weekStart.short()} – ${week.weekEnd.short()}. Here's how it went.",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Eaten per day", week.avgLoggedKcal?.fmt() ?: "–", Modifier.weight(1f), unit = week.avgLoggedKcal?.let { "kcal" },
            supporting = "${week.loggedDays} of 7 days logged")
        StatTile("Weight trend", week.trendChange?.signed() ?: "–", Modifier.weight(1f), unit = week.trendChange?.let { "kg" },
            supporting = week.trendEnd?.let { "now ${it.fmt()} kg" } ?: "no weigh-ins yet")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Strength", "${week.workouts.size}/${week.workoutsPlanned}", Modifier.weight(1f), supporting = "workouts")
        StatTile("Run-walk", "${week.runs.size}/${week.runsPlanned}", Modifier.weight(1f),
            supporting = if (week.runKm > 0) "${week.runKm.fmt(1)} km" else "runs")
    }
    if (week.avgSteps != null || week.weighInsThisWeek > 0) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Weigh-ins", "${week.weighInsThisWeek}", Modifier.weight(1f), supporting = "aim for 3 or more")
            if (week.avgSteps != null) StatTile("Steps", week.avgSteps.fmt(), Modifier.weight(1f), supporting = "per day")
        }
    }
    BloomCard(Modifier.fillMaxWidth()) {
        Text("Calories per day", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        val target = week.days.mapNotNull { it.targetKcal }.lastOrNull()
        ColumnChart(
            data = week.days.map { d ->
                val st = ui.statuses[d.date] ?: d.initial
                ColumnDatum(
                    label = d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH),
                    value = d.kcal,
                    detail = "${d.date.short()}\n${d.kcal.roundToInt().fmt()} kcal · ${st.label}",
                    muted = st != DayStatus.COMPLETE,
                )
            },
            reference = target?.toDouble(),
            legend = listOf("Fully logged" to MaterialTheme.colorScheme.primary, "Partly logged" to MaterialTheme.colorScheme.outlineVariant),
            summary = "Calories per day for the week; average ${week.avgLoggedKcal ?: 0} kcal on logged days" + (target?.let { ", target $it" } ?: ""),
        )
    }
    week.latestMeasurement?.takeIf { !it.date.isBefore(week.weekStart) }?.let { m ->
        BloomCard(Modifier.fillMaxWidth()) {
            Text("Measurements", style = MaterialTheme.typography.titleMedium)
            val prev = week.previousMeasurement
            listOfNotNull(
                m.waistCm?.let { "Waist ${it.fmt()} cm" + (prev?.waistCm?.let { p -> " (${(it - p).signed()})" } ?: "") },
                m.hipsCm?.let { "Hips ${it.fmt()} cm" + (prev?.hipsCm?.let { p -> " (${(it - p).signed()})" } ?: "") },
            ).forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

// ---------- Step 2: which days were logged well ----------

@Composable
private fun ColumnScope.DaysStep(week: WeekData, ui: ReviewUi, onSet: (java.time.LocalDate, DayStatus) -> Unit) {
    Text(
        "Your new target is based on days you logged completely. A day with forgotten snacks or a meal out you didn't log is \"partly\". I guessed from your diary; correct anything that's off.",
        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    week.days.forEach { d ->
        BloomCard(Modifier.fillMaxWidth(), contentPadding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        d.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + d.date.short(),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (d.entries == 0) "Nothing logged" else "${d.kcal.roundToInt().fmt()} kcal · ${d.entries} item${if (d.entries == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ConnectedChoice(
                options = listOf(DayStatus.COMPLETE, DayStatus.PARTIAL, DayStatus.SKIPPED),
                selected = ui.statuses[d.date] ?: d.initial,
                label = { when (it) { DayStatus.COMPLETE -> "Complete"; DayStatus.PARTIAL -> "Partly"; DayStatus.SKIPPED -> "Not logged" } },
                onSelect = { onSet(d.date, it) },
            )
        }
    }
}

// ---------- Step 3: check-in ----------

@Composable
private fun ColumnScope.CheckInStep(week: WeekData, ui: ReviewUi, vm: WeeklyReviewViewModel) {
    BloomCard(Modifier.fillMaxWidth()) {
        Text("Weigh-in", style = MaterialTheme.typography.titleMedium)
        if (ui.weightLogged) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("You weighed in today. Thanks!", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            Text(
                "For the most accurate numbers, weigh yourself this morning: after the toilet, before eating or drinking.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            NumberStepper(ui.weightKg, vm::setWeight, "kg", Modifier.fillMaxWidth(), step = 0.1, decimals = 1, min = 30.0, max = 300.0, label = "Weight")
            Spacer(Modifier.height(12.dp))
            FilledTonalButton(onClick = vm::logWeight, modifier = Modifier.fillMaxWidth()) { Text("Save weigh-in") }
        }
    }
    BloomCard(Modifier.fillMaxWidth()) {
        Text("Measurements (optional)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Waist at the narrowest point, hips at the widest. Shape often changes before the scale does.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DecimalField("Waist", ui.waist, vm::setWaist, Modifier.weight(1f), suffix = "cm")
            DecimalField("Hips", ui.hips, vm::setHips, Modifier.weight(1f), suffix = "cm")
        }
        week.latestMeasurement?.let { m ->
            Text(
                "Last time (${m.date.relative(week.reviewDate)}): " + listOfNotNull(m.waistCm?.let { "waist ${it.fmt()}" }, m.hipsCm?.let { "hips ${it.fmt()}" }).joinToString(", "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    BloomCard(Modifier.fillMaxWidth()) {
        Text("How did the week feel?", style = MaterialTheme.typography.titleMedium)
        Text("Optional. Your coach uses this to suggest changes.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val c = ui.checkIn
        Scale("Energy", "1 drained · 5 great", c.energy) { vm.setCheckIn(c.copy(energy = it)) }
        Scale("Hunger", "1 rarely hungry · 5 always hungry", c.hunger) { vm.setCheckIn(c.copy(hunger = it)) }
        Scale("Sleep", "1 poor · 5 great", c.sleep) { vm.setCheckIn(c.copy(sleep = it)) }
        Scale("Soreness", "1 none · 5 very sore", c.soreness) { vm.setCheckIn(c.copy(soreness = it)) }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = c.notes, onValueChange = { vm.setCheckIn(c.copy(notes = it.take(500))) },
            label = { Text("Anything else? (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Scale(label: String, hint: String, value: Int?, onSet: (Int?) -> Unit) {
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(6.dp))
    ConnectedChoice(
        options = listOf<Int?>(1, 2, 3, 4, 5), selected = value, label = { it.toString() },
        onSelect = { onSet(if (it == value) null else it) },
    )
}

// ---------- Step 4: the engine's numbers ----------

@Composable
private fun ColumnScope.TargetStep(r: Adaptive.Result, ui: ReviewUi) {
    val c = MaterialTheme.dataColors
    BloomCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            when {
                r.change > 0 -> "You can eat a little more"
                r.change < 0 -> "A small step down"
                else -> "Your target stays the same"
            },
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(r.proposedKcal.fmt(), style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(" kcal / day", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(bottom = 8.dp))
        }
        if (r.change != 0) {
            Text(
                "${if (r.change > 0) "+" else "−"}${kotlin.math.abs(r.change)} kcal from ${r.currentKcal.fmt()}",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Macro("Protein", r.targets.proteinG, c.protein)
            Macro("Carbs", r.targets.carbsG, c.carbs)
            Macro("Fat", r.targets.fatG, c.fat)
            Macro("Fibre", r.targets.fiberG, c.fiber)
        }
    }
    BloomCard(Modifier.fillMaxWidth()) {
        Text("How this was worked out", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Fact("Eaten on complete days", r.avgIntake?.let { "${it.roundToInt().fmt()} kcal (${r.completeDays} days)" } ?: "not enough complete days")
        Fact("Weight trend", r.weeklyRateKg?.let { "${it.signed(2)} kg per week" } ?: "not enough weigh-ins")
        Fact("Measured burn", r.measuredExpenditure?.let { "${it.fmt()} kcal/day" } ?: "not used this week")
        Fact("Your burn estimate", "${r.newExpenditure.fmt()} kcal/day" + if (r.newExpenditure != r.priorExpenditure) " (was ${r.priorExpenditure.fmt()})" else "")
        Spacer(Modifier.height(8.dp))
        Text(
            "Burn = what you ate − what your body stored or used up (1 kg ≈ 7,700 kcal). Each week moves the estimate part of the way, so one odd week can't throw your plan off.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    r.notes.forEach { n ->
        BloomCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh, contentPadding = 14.dp) {
            Text(n, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (ui.effective != null && r.change != 0) {
        Text(
            if (ui.effective == ui.week?.reviewDate) "If you accept, the new target starts today." else "If you accept, the new target starts tomorrow, since you've already logged food today.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}

@Composable
private fun Macro(label: String, grams: Int, color: androidx.compose.ui.graphics.Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Canvas(Modifier.size(8.dp)) { drawCircle(color) }
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text("$grams g", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

// ---------- Step 5: the coach's write-up ----------

@Composable
private fun ColumnScope.CoachStep(ui: ReviewUi, vm: WeeklyReviewViewModel) {
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            when (ui.accepted) {
                true -> if (ui.result?.change == 0) "Review saved." else "Review saved. New target: ${ui.result?.proposedKcal?.fmt()} kcal."
                else -> "Review saved. You're keeping ${ui.result?.currentKcal?.fmt()} kcal."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    if (!hasKey) {
        OfflineSummary(ui)
        return
    }
    items.forEach { item ->
        when (item) {
            is ChatItem.Assistant -> BloomCard(Modifier.fillMaxWidth()) { MarkdownText(item.m.content) }
            is ChatItem.Action -> ActionCard(item.a, { vm.undo(item.a.id) }, { vm.accept(item.a.id) }, { vm.decline(item.a.id) })
            else -> Unit
        }
    }
    if (run.busy && run.streaming.isNotBlank()) BloomCard(Modifier.fillMaxWidth()) { MarkdownText(run.streaming) }
    RunIndicator(run)
    run.error?.let { e ->
        Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = vm::retryCoach) { Text("Try again") }
    }
}

/** Without an AI key: a short, rule-based summary so the review still ends with something useful. */
@Composable
private fun OfflineSummary(ui: ReviewUi) {
    val week = ui.week ?: return
    val r = ui.result ?: return
    val wins = buildList {
        if (week.workouts.size >= week.workoutsPlanned && week.workoutsPlanned > 0) add("You did all ${week.workoutsPlanned} strength workouts.")
        else if (week.workouts.isNotEmpty()) add("${week.workouts.size} strength workout${if (week.workouts.size == 1) "" else "s"} done.")
        if (week.runs.isNotEmpty()) add("${week.runs.size} run-walk${if (week.runs.size == 1) "" else "s"}, ${week.runKm.fmt(1)} km.")
        if (r.completeDays >= 5) add("${r.completeDays} fully logged days. That's what makes the numbers reliable.")
        week.trendChange?.takeIf { it < -0.05 }?.let { add("Your trend weight went down ${(-it).fmt()} kg.") }
    }
    val focus = buildList {
        if (r.weighIns < 3) add("Weigh in at least 3 mornings a week.")
        if (r.completeDays < 5) add("Log every meal for 5 or more days, including drinks and snacks.")
        if (week.workouts.size < week.workoutsPlanned) add("Plan your strength sessions into the calendar like appointments.")
        if (week.runs.size < week.runsPlanned) add("Keep your run-walks easy. Finishing them matters more than speed.")
        if (isEmpty()) add("Keep doing exactly what you're doing.")
    }
    BloomCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(MaterialShapes.Sunny, Icons.Rounded.EventNote, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Text("Week summary", style = MaterialTheme.typography.titleMedium)
        }
        if (wins.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("What went well", style = MaterialTheme.typography.titleSmall)
            wins.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
        Spacer(Modifier.height(12.dp))
        Text("Focus for next week", style = MaterialTheme.typography.titleSmall)
        focus.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
    BloomCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh, contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("Add a DeepSeek key in Settings to get a personal write-up and training suggestions from your coach.",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}
