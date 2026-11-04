package be.lain.bloom.feature.food

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import be.lain.bloom.core.designsystem.dataColors
import be.lain.bloom.core.util.fmt
import be.lain.bloom.domain.FoodCandidate
import be.lain.bloom.domain.FoodSource
import be.lain.bloom.domain.Meal
import be.lain.bloom.domain.Nutrients
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

fun Double?.kcalText(): String = this?.let { "${it.fmt(0)} kcal" } ?: "? kcal"

fun FoodCandidate.subtitle(): String = buildString {
    brand?.let { append(it); append(" · ") }
    append(per100.kcal.kcalText())
    append(" per 100 $unit")
    if (source == FoodSource.GENERIC) append(" · USDA")
    if (source == FoodSource.OFF && brand == null) append(" · Open Food Facts")
}

@Composable
fun FoodRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onClick: () -> Unit,
    onQuickAdd: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, style = MaterialTheme.typography.titleSmall)
        }
        if (onQuickAdd != null) {
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onQuickAdd() },
                modifier = Modifier.size(40.dp),
            ) { Icon(Icons.Rounded.Add, "Add $title") }
        }
    }
}

@Composable
fun MealChips(selected: Meal, onSelect: (Meal) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Meal.entries.forEach { m ->
            FilterChip(
                selected = m == selected,
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(m) },
                label = { Text(m.label) },
            )
        }
    }
}

/** Macro summary line: coloured dots beside text values. */
@Composable
fun MacroLine(n: Nutrients, modifier: Modifier = Modifier) {
    val c = MaterialTheme.dataColors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        MacroValue("Protein", n.protein, c.protein)
        MacroValue("Carbs", n.carbs, c.carbs)
        MacroValue("Fat", n.fat, c.fat)
    }
}

@Composable
private fun MacroValue(label: String, v: Double?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text("${v?.fmt(1) ?: "?"} g", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(3.dp))
        Text(label.lowercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Full nutrition table for an amount, plus per-100 values. */
@Composable
fun NutritionTable(forAmount: Nutrients, per100: Nutrients, amountLabel: String, unit: String) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text("Nutrient", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(amountLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
            Text("per 100 $unit", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val rows = listOf(
            Triple("Energy", forAmount.kcal to per100.kcal, "kcal"),
            Triple("Protein", forAmount.protein to per100.protein, "g"),
            Triple("Carbohydrates", forAmount.carbs to per100.carbs, "g"),
            Triple("  of which sugars", forAmount.sugar to per100.sugar, "g"),
            Triple("Fat", forAmount.fat to per100.fat, "g"),
            Triple("  of which saturated", forAmount.satFat to per100.satFat, "g"),
            Triple("Fibre", forAmount.fiber to per100.fiber, "g"),
            Triple("Salt", forAmount.salt to per100.salt, "g"),
        )
        rows.forEach { (label, values, u) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(values.first?.let { "${it.fmt(if (u == "kcal") 0 else 1)} $u" } ?: "unknown",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
                Text(values.second?.let { "${it.fmt(if (u == "kcal") 0 else 1)} $u" } ?: "unknown",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(96.dp))
            }
        }
    }
}

/** Monday–Sunday strip with week navigation. Days that have entries get a dot. */
@Composable
fun WeekStrip(
    selected: LocalDate,
    today: LocalDate,
    loggedDays: Set<LocalDate>,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val monday = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onSelect(selected.minusWeeks(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous week") }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            (0..6).forEach { i ->
                val d = monday.plusDays(i.toLong())
                val isSel = d == selected
                val bg by animateColorAsState(
                    when {
                        isSel -> MaterialTheme.colorScheme.primary
                        d == today -> MaterialTheme.colorScheme.secondaryContainer
                        else -> Color.Transparent
                    }, label = "day",
                )
                val fg = when {
                    isSel -> MaterialTheme.colorScheme.onPrimary
                    d.isAfter(today) -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Surface(
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(d) },
                    shape = if (isSel) MaterialShapes.Cookie9Sided.toShape() else MaterialTheme.shapes.large,
                    color = bg,
                    modifier = Modifier.size(width = 40.dp, height = 58.dp).semantics {
                        contentDescription = "${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${d.dayOfMonth}"
                    },
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH), style = MaterialTheme.typography.labelSmall, color = fg)
                        Text("${d.dayOfMonth}", style = MaterialTheme.typography.titleSmall, color = fg)
                        Spacer(Modifier.height(2.dp))
                        Box(
                            Modifier.size(5.dp).clip(CircleShape)
                                .background(if (d in loggedDays) (if (isSel) fg else MaterialTheme.colorScheme.primary) else Color.Transparent),
                        )
                    }
                }
            }
        }
        IconButton(onClick = { onSelect(selected.plusWeeks(1)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next week") }
    }
}
