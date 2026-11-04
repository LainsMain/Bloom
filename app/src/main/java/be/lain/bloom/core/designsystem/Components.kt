package be.lain.bloom.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import be.lain.bloom.core.util.fmt
import java.time.DayOfWeek
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

@Composable
fun BloomCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentPadding: Dp = 20.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.extraLarge
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = color) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** A Material shape (cookie, flower, sunny...) filled with colour, with an icon on top. */
@Composable
fun ShapeIcon(
    polygon: RoundedPolygon,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    contentDescription: String? = null,
) {
    Box(
        modifier.size(size).clip(polygon.toShape()).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

/** The day's calorie budget as a wavy ring with the remaining amount as the hero number. */
@Composable
fun CalorieRing(eaten: Int, target: Int, modifier: Modifier = Modifier, size: Dp = 232.dp) {
    val over = target in 1..<eaten
    val fraction = if (target <= 0) 0f else (eaten.toFloat() / target).coerceIn(0f, 1f)
    val animated by animateFloatAsState(fraction, MaterialTheme.motionScheme.slowSpatialSpec(), label = "ring")
    val color by animateColorAsState(
        if (over) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "ringColor",
    )
    val strokeWidth = with(LocalDensity.current) { 16.dp.toPx() }
    val description = if (over) "${eaten - target} kilocalories over target" else "${target - eaten} kilocalories left of $target"
    Box(modifier.size(size).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        CircularWavyProgressIndicator(
            progress = { animated },
            modifier = Modifier.size(size),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            stroke = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            trackStroke = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            wavelength = 36.dp,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                (if (over) eaten - target else target - eaten).fmt(),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                if (over) "kcal over" else "kcal left",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${eaten.fmt()} of ${target.fmt()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A thin meter: fill in the series colour, track a light step of the same colour. */
@Composable
fun MeterBar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), MaterialTheme.motionScheme.defaultSpatialSpec(), label = "meter")
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(color.copy(alpha = 0.22f), cornerRadius = r)
        if (animated > 0f) {
            drawRoundRect(color, size = Size(size.width * animated, size.height), cornerRadius = r)
        }
    }
}

@Composable
fun MacroMeter(label: String, value: Double?, target: Int, color: Color, modifier: Modifier = Modifier, unit: String = "g") {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                "${value?.let { it.fmt(0) } ?: "–"} / $target $unit",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        MeterBar(fraction = if (target > 0) ((value ?: 0.0) / target).toFloat() else 0f, color = color)
    }
}

/** Large selectable card used for one-of-many choices. Corners morph when selected. */
@Composable
fun ChoiceCard(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
) {
    val haptics = LocalHapticFeedback.current
    val corner by animateDpAsState(if (selected) 32.dp else 20.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "corner")
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "container",
    )
    Surface(
        onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onClick() },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(corner),
        color = container,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    icon, null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (description != null) {
                    Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selected) {
                Icon(Icons.Rounded.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Connected button group for a small set of mutually exclusive options. */
@Composable
fun <T> ConnectedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(option) },
                modifier = Modifier.weight(1f).semantics { contentDescription = label(option) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                Text(label(option), maxLines = 1)
            }
        }
    }
}

/**
 * Big editable number with -/+ buttons. Text input is parsed leniently (comma or dot).
 */
@Composable
fun NumberStepper(
    value: Double,
    onValueChange: (Double) -> Unit,
    unit: String,
    modifier: Modifier = Modifier,
    step: Double = 1.0,
    decimals: Int = 0,
    min: Double = 0.0,
    max: Double = 1000.0,
    label: String? = null,
    trimZeros: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    var text by remember(value) {
        mutableStateOf(if (trimZeros) formatPlain(value, decimals) else value.fmt(decimals, grouping = false))
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onValueChange((value - step).coerceIn(min, max)) },
                modifier = Modifier.size(56.dp),
            ) { Icon(Icons.Rounded.Remove, "Decrease $label") }
            Spacer(Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                BasicTextField(
                    value = text,
                    onValueChange = { new ->
                        text = new
                        new.replace(',', '.').toDoubleOrNull()?.let { if (it in min..max) onValueChange(it) }
                    },
                    textStyle = MaterialTheme.typography.displayMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.widthIn(min = 72.dp, max = 180.dp).width(textFieldWidth(text)),
                )
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalIconButton(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onValueChange((value + step).coerceIn(min, max)) },
                modifier = Modifier.size(56.dp),
            ) { Icon(Icons.Rounded.Add, "Increase $label") }
        }
    }
}

/** Rough width for the big number field so the unit sits right after the digits. */
private fun textFieldWidth(text: String): Dp = (text.length.coerceAtLeast(2) * 30 + 8).dp

private val dayOrder = listOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
)

/** Seven round day toggles (Mon–Sun). */
@Composable
fun DayPicker(
    selected: Set<DayOfWeek>,
    onToggle: (DayOfWeek) -> Unit,
    modifier: Modifier = Modifier,
    disabled: Set<DayOfWeek> = emptySet(),
    selectedColor: Color = MaterialTheme.colorScheme.primary,
    onSelectedColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val haptics = LocalHapticFeedback.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        dayOrder.forEach { day ->
            val on = day in selected
            val off = day in disabled && !on
            val bg by animateColorAsState(
                when {
                    on -> selectedColor
                    off -> MaterialTheme.colorScheme.surfaceContainerLow
                    else -> MaterialTheme.colorScheme.surfaceContainerHighest
                },
                label = "day",
            )
            val name = day.getDisplayName(JTextStyle.FULL, Locale.ENGLISH)
            Surface(
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onToggle(day) },
                enabled = !off,
                shape = if (on) MaterialShapesCookie else CircleShape,
                color = bg,
                modifier = Modifier.size(42.dp).semantics { contentDescription = "$name ${if (on) "selected" else "not selected"}" },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        day.getDisplayName(JTextStyle.NARROW, Locale.ENGLISH),
                        style = MaterialTheme.typography.titleSmall,
                        color = when {
                            on -> onSelectedColor
                            off -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

private val MaterialShapesCookie: androidx.compose.ui.graphics.Shape
    @Composable get() = androidx.compose.material3.MaterialShapes.Cookie6Sided.toShape()

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, supporting: String? = null) {
    BloomCard(modifier, contentPadding = 16.dp) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        if (supporting != null) {
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun EmptyState(
    polygon: RoundedPolygon,
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        ShapeIcon(polygon, icon, size = 112.dp)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

/** Tiny legend key: a coloured mark beside a text label. */
@Composable
fun LegendKey(label: String, color: Color, modifier: Modifier = Modifier, line: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(if (line) 16.dp else 10.dp, 10.dp)) {
            if (line) drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx())
            else drawCircle(color)
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Numeric text field that keeps the user's raw text (so "1." isn't reformatted) and reports parsed values. */
@Composable
fun DecimalField(
    label: String,
    value: Double?,
    onValue: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    decimals: Int = 1,
) {
    var text by remember { mutableStateOf(value?.let { formatPlain(it, decimals) }.orEmpty()) }
    androidx.compose.material3.OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new.filter { it.isDigit() || it == '.' || it == ',' }.take(9)
            onValue(text.replace(',', '.').toDoubleOrNull())
        },
        label = { Text(label, maxLines = 1) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

private fun formatPlain(v: Double, decimals: Int): String =
    java.math.BigDecimal(v).setScale(decimals, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
