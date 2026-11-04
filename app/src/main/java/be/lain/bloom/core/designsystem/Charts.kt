package be.lain.bloom.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import be.lain.bloom.core.util.fmt
import be.lain.bloom.core.util.short
import be.lain.bloom.core.util.shortWithYear
import be.lain.bloom.domain.TrendPoint
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun niceStep(range: Double): Double = when {
    range <= 3 -> 0.5
    range <= 6 -> 1.0
    range <= 12 -> 2.0
    range <= 30 -> 5.0
    else -> 10.0
}

/**
 * Weigh-ins (dots), smoothed trend (line), optional projection (dashed: it's a forecast)
 * and goal (thin reference line). Drag or tap to inspect a day.
 */
@Composable
fun WeightChart(
    points: List<TrendPoint>,
    modifier: Modifier = Modifier,
    goalKg: Double? = null,
    projection: List<Pair<LocalDate, Double>> = emptyList(),
) {
    if (points.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val trendColor = scheme.primary
    val dotColor = scheme.secondary
    val goalColor = scheme.tertiary
    val grid = scheme.outlineVariant.copy(alpha = 0.6f)
    val surface = scheme.surfaceContainer
    val textColor = scheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = textColor)
    val valueStyle = MaterialTheme.typography.labelMedium.copy(color = scheme.onSurface)
    val measurer = rememberTextMeasurer()
    var selected by remember(points) { mutableStateOf<Int?>(null) }

    val start = points.first().date
    val end = (projection.lastOrNull()?.first ?: points.last().date).let { if (it.isAfter(points.last().date)) it else points.last().date }
    val days = max(1L, ChronoUnit.DAYS.between(start, end)).toDouble()
    val values = points.flatMap { listOfNotNull(it.raw, it.trend) } + projection.map { it.second } +
        listOfNotNull(goalKg?.takeIf { projection.isNotEmpty() })
    val lo0 = values.min()
    val hi0 = values.max()
    val step = niceStep(hi0 - lo0 + 1.0)
    val lo = floor((lo0 - 0.3) / step) * step
    val hi = ceil((hi0 + 0.3) / step) * step

    val last = points.last()
    val summary = "Weight trend ${points.first().trend.fmt()} to ${last.trend.fmt()} kilograms from ${start.short()} to ${last.date.short()}" +
        (goalKg?.let { ", goal ${it.fmt()} kilograms" } ?: "")

    Column(modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LegendKey("Weigh-in", dotColor)
            LegendKey("Trend", trendColor, line = true)
            if (projection.isNotEmpty()) LegendKey("Projection", trendColor.copy(alpha = 0.6f), line = true)
            if (goalKg != null) LegendKey("Goal", goalColor, line = true)
        }
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier.fillMaxWidth().height(220.dp)
                .semantics { contentDescription = summary }
                .pointerInput(points) {
                    detectTapGestures { pos -> selected = indexAt(pos.x, size.width.toFloat(), 40.dp.toPx(), 44.dp.toPx(), points, start, days) }
                }
                .pointerInput(points) {
                    // Horizontal only, so vertical swipes still scroll the page.
                    detectHorizontalDragGestures(
                        onDragStart = { pos -> selected = indexAt(pos.x, size.width.toFloat(), 40.dp.toPx(), 44.dp.toPx(), points, start, days) },
                        onDragEnd = { },
                    ) { change, _ -> selected = indexAt(change.position.x, size.width.toFloat(), 40.dp.toPx(), 44.dp.toPx(), points, start, days) }
                },
        ) {
            val left = 40.dp.toPx()
            val right = 44.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = 22.dp.toPx()
            val w = size.width - left - right
            val h = size.height - top - bottom
            fun x(d: LocalDate) = left + (ChronoUnit.DAYS.between(start, d) / days * w).toFloat()
            fun y(v: Double) = top + ((hi - v) / (hi - lo) * h).toFloat()

            // Grid + y labels
            var t = lo
            while (t <= hi + 1e-9) {
                val yy = y(t)
                drawLine(grid, Offset(left, yy), Offset(left + w, yy), strokeWidth = 1f)
                val txt = measurer.measure(t.fmt(if (step < 1) 1 else 0), labelStyle)
                drawText(txt, topLeft = Offset(left - txt.size.width - 8.dp.toPx(), yy - txt.size.height / 2f))
                t += step
            }
            // x labels: start, end
            val withYear = start.year != end.year
            listOf(start, end).distinct().forEachIndexed { i, d ->
                val txt = measurer.measure(if (withYear) d.shortWithYear() else d.short(), labelStyle)
                val xx = x(d) - if (i == 0) 0f else txt.size.width.toFloat()
                drawText(txt, topLeft = Offset(xx.coerceAtLeast(left), size.height - txt.size.height))
            }

            // Goal reference
            if (goalKg != null && goalKg in lo..hi) {
                val gy = y(goalKg)
                drawLine(goalColor, Offset(left, gy), Offset(left + w, gy), strokeWidth = 1.5.dp.toPx())
                val txt = measurer.measure("Goal", labelStyle)
                drawText(txt, topLeft = Offset(left + w + 6.dp.toPx(), gy - txt.size.height / 2f))
            }

            // Projection (dashed = forecast)
            if (projection.size >= 2) {
                val path = Path()
                projection.forEachIndexed { i, (d, v) -> if (i == 0) path.moveTo(x(d), y(v)) else path.lineTo(x(d), y(v)) }
                drawPath(
                    path, trendColor.copy(alpha = 0.6f),
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))),
                )
            }

            // Weigh-in dots with surface ring
            val r = 4.dp.toPx()
            points.forEach { p ->
                p.raw?.let {
                    drawCircle(surface, radius = r + 2.dp.toPx(), center = Offset(x(p.date), y(it)))
                    drawCircle(dotColor.copy(alpha = 0.75f), radius = r, center = Offset(x(p.date), y(it)))
                }
            }

            // Trend line
            if (points.size >= 2) {
                val path = Path()
                points.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.date), y(p.trend)) else path.lineTo(x(p.date), y(p.trend)) }
                drawPath(path, trendColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            // End dot + direct label of latest trend
            val ex = x(last.date)
            val ey = y(last.trend)
            drawCircle(surface, radius = r + 2.dp.toPx(), center = Offset(ex, ey))
            drawCircle(trendColor, radius = r, center = Offset(ex, ey))
            if (projection.isEmpty()) {
                val txt = measurer.measure(last.trend.fmt(), valueStyle)
                drawText(txt, topLeft = Offset(ex + 8.dp.toPx(), ey - txt.size.height / 2f))
            }

            // Inspection crosshair + tooltip
            selected?.let { idx ->
                val p = points[idx]
                val sx = x(p.date)
                drawLine(scheme.outline, Offset(sx, top), Offset(sx, top + h), strokeWidth = 1f)
                drawCircle(surface, radius = r + 2.dp.toPx(), center = Offset(sx, y(p.trend)))
                drawCircle(trendColor, radius = r + 1.dp.toPx(), center = Offset(sx, y(p.trend)))
                val lines = buildString {
                    append(p.date.short())
                    append("\nTrend ${p.trend.fmt()} kg")
                    p.raw?.let { append("\nWeighed ${it.fmt()} kg") }
                }
                val txt = measurer.measure(lines, valueStyle)
                val pad = 8.dp.toPx()
                val bw = txt.size.width + pad * 2
                val bh = txt.size.height + pad * 2
                val bx = (sx - bw / 2).coerceIn(left, left + w - bw + right)
                drawRoundRect(scheme.inverseSurface, topLeft = Offset(bx, top), size = Size(bw, bh), cornerRadius = CornerRadius(12.dp.toPx()))
                drawText(measurer.measure(lines, valueStyle.copy(color = scheme.inverseOnSurface)), topLeft = Offset(bx + pad, top + pad))
            }
        }
    }
}

private fun indexAt(px: Float, width: Float, left: Float, right: Float, points: List<TrendPoint>, start: LocalDate, days: Double): Int {
    val frac = ((px - left) / (width - left - right)).coerceIn(0f, 1f)
    val target = start.plusDays((frac * days).roundToInt().toLong())
    var best = 0
    var bestDist = Long.MAX_VALUE
    points.forEachIndexed { i, p ->
        val d = kotlin.math.abs(ChronoUnit.DAYS.between(p.date, target))
        if (d < bestDist) { bestDist = d; best = i }
    }
    return min(best, points.lastIndex)
}

/** One column; [muted] columns draw in the de-emphasis colour (e.g. partly logged days). */
data class ColumnDatum(val label: String, val value: Double, val detail: String, val muted: Boolean = false)

private fun niceMax(v: Double): Pair<Double, Double> {
    if (v <= 0) return 1.0 to 0.5
    val raw = v / 4
    val mag = Math.pow(10.0, floor(kotlin.math.log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    return ceil(v / step) * step to step
}

/**
 * Columns from one baseline (≤24 dp, 4 dp rounded data-end), an optional solid reference line
 * (e.g. the calorie target) and tap/drag inspection. One series: the card title names it.
 */
@Composable
fun ColumnChart(
    data: List<ColumnDatum>,
    modifier: Modifier = Modifier,
    reference: Double? = null,
    referenceLabel: String = "Target",
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    legend: List<Pair<String, androidx.compose.ui.graphics.Color>> = emptyList(),
    labelEvery: Int = 1,
    decimals: Int = 0,
    summary: String,
    height: androidx.compose.ui.unit.Dp = 180.dp,
) {
    if (data.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.outlineVariant
    val grid = scheme.outlineVariant.copy(alpha = 0.6f)
    val refColor = scheme.tertiary
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val tipStyle = MaterialTheme.typography.labelMedium.copy(color = scheme.inverseOnSurface)
    val measurer = rememberTextMeasurer()
    var selected by remember(data) { mutableStateOf<Int?>(null) }
    val (top, step) = niceMax(max(data.maxOf { it.value }, reference ?: 0.0) * 1.05)

    Column(modifier) {
        if (legend.isNotEmpty() || (reference != null && reference > 0)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                legend.forEach { (l, c) -> LegendKey(l, c) }
                if (reference != null && reference > 0) LegendKey(referenceLabel, refColor, line = true)
            }
            Spacer(Modifier.height(12.dp))
        }
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .semantics { contentDescription = summary }
                .pointerInput(data) {
                    detectTapGestures { pos ->
                        val left = 40.dp.toPx(); val w = size.width - left - 8.dp.toPx()
                        val i = ((pos.x - left) / (w / data.size)).toInt()
                        selected = if (i in data.indices && selected != i) i else null
                    }
                }
                .pointerInput(data) {
                    detectHorizontalDragGestures(onDragEnd = { }) { change, _ ->
                        val left = 40.dp.toPx(); val w = size.width - left - 8.dp.toPx()
                        selected = ((change.position.x - left) / (w / data.size)).toInt().coerceIn(0, data.lastIndex)
                    }
                },
        ) {
            val left = 40.dp.toPx()
            val right = 8.dp.toPx()
            val topPad = 8.dp.toPx()
            val bottom = 22.dp.toPx()
            val w = size.width - left - right
            val h = size.height - topPad - bottom
            fun y(v: Double) = topPad + ((top - v) / top * h).toFloat()

            var t = 0.0
            while (t <= top + 1e-9) {
                val yy = y(t)
                drawLine(grid, Offset(left, yy), Offset(left + w, yy), strokeWidth = 1f)
                val txt = measurer.measure(t.fmt(if (step < 1) 1 else 0), labelStyle)
                drawText(txt, topLeft = Offset(left - txt.size.width - 8.dp.toPx(), yy - txt.size.height / 2f))
                t += step
            }
            val slot = w / data.size
            val bar = min(24.dp.toPx(), slot - 2.dp.toPx()).coerceAtLeast(2f)
            val radius = min(4.dp.toPx(), bar / 2)
            data.forEachIndexed { i, d ->
                val cx = left + slot * i + slot / 2
                if (d.value > 0) {
                    val yt = y(d.value)
                    val base = y(0.0)
                    val path = Path().apply {
                        val x0 = cx - bar / 2; val x1 = cx + bar / 2
                        val r = min(radius, (base - yt) / 2)
                        moveTo(x0, base); lineTo(x0, yt + r)
                        quadraticTo(x0, yt, x0 + r, yt); lineTo(x1 - r, yt)
                        quadraticTo(x1, yt, x1, yt + r); lineTo(x1, base); close()
                    }
                    val c = if (d.muted) muted else color
                    drawPath(path, if (selected != null && selected != i) c.copy(alpha = 0.55f) else c)
                }
                // Anchor labels on the newest column so the latest one is always named.
                if ((data.lastIndex - i) % labelEvery == 0) {
                    val txt = measurer.measure(d.label, labelStyle)
                    if (txt.size.width < slot * labelEvery + 4) {
                        drawText(txt, topLeft = Offset(cx - txt.size.width / 2f, size.height - txt.size.height))
                    }
                }
            }
            if (reference != null && reference > 0) {
                val ry = y(reference)
                drawLine(refColor, Offset(left, ry), Offset(left + w, ry), strokeWidth = 1.5.dp.toPx())
            }
            selected?.let { i ->
                val d = data[i]
                val txt = measurer.measure(d.detail, tipStyle)
                val pad = 8.dp.toPx()
                val bw = txt.size.width + pad * 2
                val bh = txt.size.height + pad * 2
                val cx = left + slot * i + slot / 2
                val bx = (cx - bw / 2).coerceIn(0f, size.width - bw)
                drawRoundRect(scheme.inverseSurface, topLeft = Offset(bx, topPad), size = Size(bw, bh), cornerRadius = CornerRadius(12.dp.toPx()))
                drawText(txt, topLeft = Offset(bx + pad, topPad + pad))
            }
        }
    }
}

data class LineSeries(val name: String, val color: androidx.compose.ui.graphics.Color, val points: List<Pair<LocalDate, Double>>)

/** A few dated series on one shared axis (same unit), with a legend, end labels and tap inspection. */
@Composable
fun DateLineChart(
    series: List<LineSeries>,
    unit: String,
    summary: String,
    modifier: Modifier = Modifier,
    decimals: Int = 1,
    height: androidx.compose.ui.unit.Dp = 180.dp,
) {
    val all = series.flatMap { it.points }
    if (all.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val surface = scheme.surfaceContainer
    val grid = scheme.outlineVariant.copy(alpha = 0.6f)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    val valueStyle = MaterialTheme.typography.labelMedium.copy(color = scheme.onSurface)
    val tipStyle = MaterialTheme.typography.labelMedium.copy(color = scheme.inverseOnSurface)
    val measurer = rememberTextMeasurer()
    val dates = all.map { it.first }.distinct().sorted()
    var selected by remember(series) { mutableStateOf<LocalDate?>(null) }
    val start = dates.first()
    val end = dates.last()
    val days = max(1L, ChronoUnit.DAYS.between(start, end)).toDouble()
    val lo0 = all.minOf { it.second }
    val hi0 = all.maxOf { it.second }
    val step = niceStep(hi0 - lo0 + 1.0)
    val lo = floor((lo0 - 0.5) / step) * step
    val hi = ceil((hi0 + 0.5) / step) * step

    Column(modifier) {
        if (series.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { series.forEach { LegendKey(it.name, it.color, line = true) } }
            Spacer(Modifier.height(12.dp))
        }
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .semantics { contentDescription = summary }
                .pointerInput(series) {
                    detectTapGestures { pos ->
                        val left = 40.dp.toPx(); val w = size.width - left - 44.dp.toPx()
                        val target = start.plusDays((((pos.x - left) / w).coerceIn(0f, 1f) * days).roundToInt().toLong())
                        selected = dates.minBy { kotlin.math.abs(ChronoUnit.DAYS.between(it, target)) }
                    }
                },
        ) {
            val left = 40.dp.toPx()
            val right = 44.dp.toPx()
            val topPad = 8.dp.toPx()
            val bottom = 22.dp.toPx()
            val w = size.width - left - right
            val h = size.height - topPad - bottom
            fun x(d: LocalDate) = left + if (dates.size == 1) w / 2 else (ChronoUnit.DAYS.between(start, d) / days * w).toFloat()
            fun y(v: Double) = topPad + ((hi - v) / (hi - lo) * h).toFloat()
            var t = lo
            while (t <= hi + 1e-9) {
                val yy = y(t)
                drawLine(grid, Offset(left, yy), Offset(left + w, yy), strokeWidth = 1f)
                val txt = measurer.measure(t.fmt(if (step < 1) 1 else 0), labelStyle)
                drawText(txt, topLeft = Offset(left - txt.size.width - 8.dp.toPx(), yy - txt.size.height / 2f))
                t += step
            }
            val withYear = start.year != end.year
            listOf(start, end).distinct().forEachIndexed { i, d ->
                val txt = measurer.measure(if (withYear) d.shortWithYear() else d.short(), labelStyle)
                val xx = x(d) - if (i == 0) 0f else txt.size.width.toFloat()
                drawText(txt, topLeft = Offset(xx.coerceAtLeast(left), size.height - txt.size.height))
            }
            val r = 4.dp.toPx()
            series.forEach { s ->
                val pts = s.points.sortedBy { it.first }
                if (pts.size >= 2) {
                    val path = Path()
                    pts.forEachIndexed { i, (d, v) -> if (i == 0) path.moveTo(x(d), y(v)) else path.lineTo(x(d), y(v)) }
                    drawPath(path, s.color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                pts.forEach { (d, v) ->
                    drawCircle(surface, radius = r + 2.dp.toPx(), center = Offset(x(d), y(v)))
                    drawCircle(s.color, radius = r, center = Offset(x(d), y(v)))
                }
                pts.lastOrNull()?.let { (d, v) ->
                    val txt = measurer.measure(v.fmt(decimals), valueStyle)
                    drawText(txt, topLeft = Offset(x(d) + 8.dp.toPx(), y(v) - txt.size.height / 2f))
                }
            }
            selected?.let { d ->
                val sx = x(d)
                drawLine(scheme.outline, Offset(sx, topPad), Offset(sx, topPad + h), strokeWidth = 1f)
                val lines = buildString {
                    append(d.short())
                    series.forEach { s -> s.points.firstOrNull { it.first == d }?.let { append("\n${s.name} ${it.second.fmt(decimals)} $unit") } }
                }
                val txt = measurer.measure(lines, tipStyle)
                val pad = 8.dp.toPx()
                val bw = txt.size.width + pad * 2
                val bh = txt.size.height + pad * 2
                val bx = (sx - bw / 2).coerceIn(0f, size.width - bw)
                drawRoundRect(scheme.inverseSurface, topLeft = Offset(bx, topPad), size = Size(bw, bh), cornerRadius = CornerRadius(12.dp.toPx()))
                drawText(txt, topLeft = Offset(bx + pad, topPad + pad))
            }
        }
    }
}
