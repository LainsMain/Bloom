package be.lain.bloom.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.Chair
import androidx.compose.material.icons.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Construction
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import be.lain.bloom.core.designsystem.BloomCard
import be.lain.bloom.core.designsystem.ChoiceCard
import be.lain.bloom.core.designsystem.ConnectedChoice
import be.lain.bloom.core.designsystem.DayPicker
import be.lain.bloom.core.designsystem.NumberStepper
import be.lain.bloom.core.designsystem.dataColors
import be.lain.bloom.core.util.fmt
import be.lain.bloom.core.util.shortWithYear
import be.lain.bloom.domain.Energy
import be.lain.bloom.domain.EnergyFormula
import be.lain.bloom.domain.Goal
import be.lain.bloom.domain.Lifestyle
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun SectionLead(title: String, body: String? = null) {
    Text(title, style = MaterialTheme.typography.headlineMedium)
    if (body != null) {
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
fun NameSection(draft: ProfileDraft, onChange: (ProfileDraft) -> Unit) {
    OutlinedTextField(
        value = draft.name,
        onValueChange = { onChange(draft.copy(name = it.take(40))) },
        label = { Text("Your name") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun GoalSection(draft: ProfileDraft, onChange: (ProfileDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Goal.entries.forEach { goal ->
            ChoiceCard(
                selected = draft.goal == goal,
                onClick = {
                    val pace = when (goal) {
                        Goal.LOSE -> if (draft.paceKgPerWeek in 0.25..1.0) draft.paceKgPerWeek else 0.5
                        Goal.GAIN -> if (draft.paceKgPerWeek in 0.1..0.5) draft.paceKgPerWeek else 0.25
                        else -> draft.paceKgPerWeek
                    }
                    onChange(draft.copy(goal = goal, paceKgPerWeek = pace))
                },
                title = goal.label,
                description = goal.description,
                icon = when (goal) {
                    Goal.LOSE -> Icons.Rounded.TrendingDown
                    Goal.MAINTAIN -> Icons.Rounded.Balance
                    Goal.GAIN -> Icons.Rounded.TrendingUp
                    Goal.RECOMP -> Icons.Rounded.Spa
                },
            )
        }
    }
}

@Composable
fun BodySection(draft: ProfileDraft, onChange: (ProfileDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Column {
            Text("Energy estimate based on", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            ConnectedChoice(
                options = EnergyFormula.entries,
                selected = draft.formula,
                label = { it.label },
                onSelect = { onChange(draft.copy(formula = it)) },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when (draft.formula) {
                    EnergyFormula.FEMALE -> "Uses the female resting-energy formula."
                    EnergyFormula.MALE -> "Uses the male resting-energy formula."
                    EnergyFormula.BLEND -> "Averages both formulas — a good start if you're on hormone therapy. Your weekly reviews fine-tune it from real data either way."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NumberStepper(
            value = draft.birthYear.toDouble(),
            onValueChange = { onChange(draft.copy(birthYear = it.roundToInt())) },
            unit = "",
            label = "Birth year",
            min = 1930.0, max = LocalDate.now().year - 14.0,
            modifier = Modifier.fillMaxWidth(),
        )
        NumberStepper(
            value = draft.heightCm,
            onValueChange = { onChange(draft.copy(heightCm = it)) },
            unit = "cm",
            label = "Height",
            min = 120.0, max = 230.0,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun WeightSection(draft: ProfileDraft, onChange: (ProfileDraft) -> Unit, showCurrent: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        if (showCurrent) {
            NumberStepper(
                value = draft.weightKg,
                onValueChange = { onChange(draft.copy(weightKg = it)) },
                unit = "kg", label = "Current weight", step = 0.1, decimals = 1, min = 35.0, max = 250.0,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (draft.hasPace) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                NumberStepper(
                    value = draft.goalWeightKg,
                    onValueChange = { onChange(draft.copy(goalWeightKg = it)) },
                    unit = "kg", label = "Goal weight", step = 0.5, decimals = 1, min = 35.0, max = 250.0,
                    modifier = Modifier.fillMaxWidth(),
                )
                draft.goalNote()?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun LifestyleSection(draft: ProfileDraft, onChange: (ProfileDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Lifestyle.entries.forEach { l ->
            ChoiceCard(
                selected = draft.lifestyle == l,
                onClick = { onChange(draft.copy(lifestyle = l)) },
                title = l.label,
                description = l.description,
                icon = when (l) {
                    Lifestyle.DESK -> Icons.Rounded.Chair
                    Lifestyle.LIGHT -> Icons.Rounded.DirectionsWalk
                    Lifestyle.ON_FEET -> Icons.Rounded.Accessibility
                    Lifestyle.PHYSICAL -> Icons.Rounded.Construction
                },
            )
        }
    }
}

@Composable
fun PaceSection(draft: ProfileDraft, onChange: (ProfileDraft) -> Unit, today: LocalDate = LocalDate.now()) {
    val options = if (draft.goal == Goal.GAIN) ProfileDraft.gainPaces else ProfileDraft.losePaces
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        options.forEach { (pace, name) ->
            val profile = draft.copy(paceKgPerWeek = pace).toProfile(today)
            val r = Energy.targets(profile, draft.weightKg, today)
            val weeks = Energy.projection(profile, draft.weightKg).size - 1
            val recommended = (draft.goal == Goal.LOSE && pace == 0.5) || (draft.goal == Goal.GAIN && pace == 0.25)
            val desc = buildString {
                append("${r.targets.kcal.fmt()} kcal a day · goal around ${today.plusWeeks(weeks.toLong()).shortWithYear()}")
                if (r.limitedByFloor) append("\nHeld at a safe minimum, so you'd lose about ${(-r.impliedKgPerWeek).fmt(2)} kg/week")
            }
            ChoiceCard(
                selected = draft.paceKgPerWeek == pace,
                onClick = { onChange(draft.copy(paceKgPerWeek = pace)) },
                title = "$name · ${pace.toBigDecimal().stripTrailingZeros().toPlainString()} kg/week" + if (recommended) "  ★" else "",
                description = desc,
            )
        }
        Text(
            "★ Recommended. A steady pace keeps more muscle — the thing that gives you shape — and is easier to stick with.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun TrainingSection(draft: ProfileDraft, onChange: (ProfileDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Strength days", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${draft.strengthDays.size} / week", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("About 30 minutes at home, no equipment. Three days with a rest day between works best.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            DayPicker(
                selected = draft.strengthDays,
                disabled = draft.runDays,
                onToggle = { d -> onChange(draft.copy(strengthDays = draft.strengthDays.toggle(d))) },
            )
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Run / walk days", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${draft.runDays.size} / week", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Gentle run-walk intervals that build up week by week.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            DayPicker(
                selected = draft.runDays,
                disabled = draft.strengthDays,
                selectedColor = MaterialTheme.colorScheme.tertiary,
                onSelectedColor = MaterialTheme.colorScheme.onTertiary,
                onToggle = { d -> onChange(draft.copy(runDays = draft.runDays.toggle(d))) },
            )
        }
    }
}

private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

@Composable
fun TargetsSummary(draft: ProfileDraft, today: LocalDate = LocalDate.now()) {
    val profile = draft.toProfile(today)
    val r = Energy.targets(profile, draft.weightKg, today)
    val weeks = Energy.projection(profile, draft.weightKg).size - 1
    val colors = MaterialTheme.dataColors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BloomCard(color = MaterialTheme.colorScheme.primaryContainer) {
            Text("Daily calories", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(r.targets.kcal.fmt(), style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(" kcal", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = 10.dp))
            }
            Text(
                "Estimated burn ${r.expenditure.fmt()} kcal/day" + if (draft.hasPace && weeks > 0) " · goal around ${today.plusWeeks(weeks.toLong()).shortWithYear()}" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MacroTile("Protein", r.targets.proteinG, colors.protein, Modifier.weight(1f))
            MacroTile("Carbs", r.targets.carbsG, colors.carbs, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MacroTile("Fat", r.targets.fatG, colors.fat, Modifier.weight(1f))
            MacroTile("Fibre", r.targets.fiberG, colors.fiber, Modifier.weight(1f))
        }
        Text(
            "These are starting points. Every week your review compares what you ate with how your weight actually moved, and adjusts.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MacroTile(label: String, grams: Int, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    BloomCard(modifier, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color),
            )
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("$grams g", style = MaterialTheme.typography.headlineSmall)
    }
}
