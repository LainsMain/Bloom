package be.lain.bloom.feature.progress

import androidx.compose.foundation.Image
import androidx.compose.material3.toShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import be.lain.bloom.core.designsystem.BloomCard
import be.lain.bloom.core.designsystem.ColumnChart
import be.lain.bloom.core.designsystem.ColumnDatum
import be.lain.bloom.core.designsystem.DateLineChart
import be.lain.bloom.core.designsystem.LineSeries
import be.lain.bloom.core.designsystem.dataColors
import be.lain.bloom.core.util.fmt
import be.lain.bloom.core.util.short
import be.lain.bloom.core.util.shortWithYear
import be.lain.bloom.core.util.signed
import be.lain.bloom.data.db.MeasurementEntity
import be.lain.bloom.data.db.ProgressPhotoEntity
import be.lain.bloom.data.db.WeeklyReviewEntity
import be.lain.bloom.domain.DayStatus
import be.lain.bloom.feature.coach.rememberPhotoPickers
import be.lain.bloom.feature.coach.rememberThumbnail
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun IntakeCard(days: List<IntakeDay>) {
    val logged = days.filter { it.kcal > 0 }
    if (logged.isEmpty()) return
    val complete = days.filter { it.status == DayStatus.COMPLETE && it.kcal > 0 }
    val avg = complete.takeIf { it.isNotEmpty() }?.map { it.kcal }?.average()?.roundToInt()
    val target = days.lastOrNull()?.target
    BloomCard(Modifier.fillMaxWidth()) {
        Text("Calories, last 14 days", style = MaterialTheme.typography.titleLarge)
        Text(
            avg?.let { "Average ${it.fmt()} kcal on fully logged days" + (target?.let { t -> " · target ${t.fmt()}" } ?: "") }
                ?: "Mark days complete in your diary to see an average",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        ColumnChart(
            data = days.map { d ->
                ColumnDatum(
                    label = d.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH),
                    value = d.kcal,
                    detail = "${d.date.short()}\n${d.kcal.roundToInt().fmt()} kcal" + (d.target?.let { " of ${it.fmt()}" } ?: "") + "\n${d.status.label}",
                    muted = d.status != DayStatus.COMPLETE,
                )
            },
            reference = target?.toDouble(),
            legend = listOf("Fully logged" to MaterialTheme.colorScheme.primary, "Partly logged" to MaterialTheme.colorScheme.outlineVariant),
            summary = "Calories per day for the last 14 days" + (avg?.let { ", average $it on complete days" } ?: ""),
        )
    }
}

/** Waist and hips over time (same unit, one axis), plus the waist-to-hip ratio. */
@Composable
fun MeasurementTrendCard(measurements: List<MeasurementEntity>) {
    val sorted = measurements.sortedBy { it.date }
    val waist = sorted.mapNotNull { m -> m.waistCm?.let { m.date to it } }
    val hips = sorted.mapNotNull { m -> m.hipsCm?.let { m.date to it } }
    if (waist.size < 2 && hips.size < 2) return
    val c = MaterialTheme.dataColors
    BloomCard(Modifier.fillMaxWidth()) {
        Text("Waist and hips", style = MaterialTheme.typography.titleLarge)
        val first = sorted.firstOrNull { it.waistCm != null && it.hipsCm != null }
        val last = sorted.lastOrNull { it.waistCm != null && it.hipsCm != null }
        if (first != null && last != null && first != last) {
            val r0 = first.waistCm!! / first.hipsCm!!
            val r1 = last.waistCm!! / last.hipsCm!!
            Text(
                "Waist-to-hip ratio ${r1.fmt(2)} (${(r1 - r0).signed(2)} since ${first.date.short()})",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        DateLineChart(
            series = listOfNotNull(
                waist.takeIf { it.isNotEmpty() }?.let { LineSeries("Waist", c.water, it) },
                hips.takeIf { it.isNotEmpty() }?.let { LineSeries("Hips", c.protein, it) },
            ),
            unit = "cm",
            summary = "Waist " + (waist.lastOrNull()?.second?.fmt() ?: "–") + " cm, hips " + (hips.lastOrNull()?.second?.fmt() ?: "–") + " cm",
        )
    }
}

@Composable
fun TrainingWeeksCard(weeks: List<WeekBucket>, plannedStrength: Int) {
    if (weeks.all { it.workouts == 0 && it.runs == 0 }) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (weeks.any { it.workouts > 0 }) BloomCard(Modifier.fillMaxWidth()) {
        Text("Strength workouts per week", style = MaterialTheme.typography.titleLarge)
        val thisWeek = weeks.lastOrNull()
        Text(
            "This week ${thisWeek?.workouts ?: 0} of $plannedStrength planned",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        ColumnChart(
            data = weeks.map { ColumnDatum(it.start.short(), it.workouts.toDouble(), "Week of ${it.start.short()}\n${it.workouts} workout${if (it.workouts == 1) "" else "s"}") },
            reference = plannedStrength.toDouble().takeIf { it > 0 },
            referenceLabel = "Planned",
            labelEvery = 2,
            summary = "Strength workouts per week for 8 weeks; this week ${thisWeek?.workouts ?: 0}",
            height = 150.dp,
        )
    }
    if (weeks.any { it.km > 0 }) {
        BloomCard(Modifier.fillMaxWidth()) {
            Text("Run-walk distance per week", style = MaterialTheme.typography.titleLarge)
            Text(
                "${weeks.sumOf { it.km }.fmt(1)} km in 8 weeks",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            ColumnChart(
                data = weeks.map { ColumnDatum(it.start.short(), it.km, "Week of ${it.start.short()}\n${it.km.fmt(1)} km · ${it.runs} run${if (it.runs == 1) "" else "s"}") },
                labelEvery = 2,
                summary = "Run-walk kilometres per week for 8 weeks",
                height = 150.dp,
            )
        }
    }
    }
}

@Composable
fun PhotosCard(
    photos: List<ProgressPhotoEntity>,
    onAdd: (List<android.net.Uri>) -> Unit,
    onDelete: (Long) -> Unit,
    onCamera: () -> Unit,
    onCompare: () -> Unit,
) {
    val pickers = rememberPhotoPickers(onAdd)
    var open by remember { mutableStateOf<ProgressPhotoEntity?>(null) }
    BloomCard(Modifier.fillMaxWidth()) {
        Text("Progress photos", style = MaterialTheme.typography.titleLarge)
        Text(
            if (photos.isEmpty()) "Same spot, same light, every few weeks. Photos stay on this phone."
            else "${photos.size} photo${if (photos.size == 1) "" else "s"} · first ${photos.last().date.shortWithYear()}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (photos.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(photos, key = { it.id }) { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PhotoThumb(p.path, Modifier.width(96.dp).aspectRatio(3f / 4f).clickable { open = p })
                        Text(p.date.short(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onCamera) { Icon(Icons.Rounded.AddAPhoto, null); Spacer(Modifier.width(8.dp)); Text("Take photo") }
            OutlinedButton(onClick = pickers.gallery) { Icon(Icons.Rounded.PhotoLibrary, null); Spacer(Modifier.width(8.dp)); Text("Choose") }
            if (photos.size >= 2) Button(onClick = onCompare) { Text("Compare") }
        }
    }
    open?.let { p ->
        var confirm by remember(p.id) { mutableStateOf(false) }
        Dialog(onDismissRequest = { open = null }) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp)) {
                    PhotoThumb(p.path, Modifier.fillMaxWidth().aspectRatio(3f / 4f), size = 1280)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.date.shortWithYear(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { confirm = true }) { Icon(Icons.Rounded.DeleteOutline, null); Text("Delete") }
                        TextButton(onClick = { open = null }) { Text("Close") }
                    }
                }
            }
        }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Delete this photo?") },
                text = { Text("It's removed from this phone. This can't be undone.") },
                confirmButton = { TextButton(onClick = { onDelete(p.id); confirm = false; open = null }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun PhotoThumb(path: String, modifier: Modifier, size: Int = 384) {
    val img = rememberThumbnail(path, size)
    Box(modifier.clip(MaterialTheme.shapes.large)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.matchParentSize()) {}
        if (img != null) Image(img, "Progress photo", contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

@Composable
fun ReviewsCard(reviews: List<WeeklyReviewEntity>, due: Boolean, next: java.time.LocalDate?, onStart: () -> Unit, onOpen: (Long) -> Unit) {
    BloomCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.EventAvailable, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Weekly reviews", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        if (reviews.isEmpty()) {
            Text(
                "Once a week the app compares what you ate with how your weight moved and fine-tunes your target.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        reviews.take(6).forEach { r ->
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onOpen(r.id) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Week of ${r.weekStart.short()}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        listOfNotNull(
                            r.weeklyRateKg?.let { "${it.signed(2)} kg/wk" },
                            r.avgIntakeKcal?.let { "ate ${it.fmt()} kcal" },
                            "${r.workoutsDone}/${r.workoutsPlanned} workouts",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (r.applied && r.proposedKcal != r.oldKcal) "${r.oldKcal.fmt()} → ${r.proposedKcal.fmt()}" else "${r.oldKcal.fmt()} kcal",
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))
        // One review per week: reviewing the same week twice would count its data twice.
        if (due) Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("Start this week's review") }
        else if (next != null) Text(
            "Next review: ${next.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${next.short()}",
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun MilestonesCard(state: be.lain.bloom.data.MilestoneState) {
    if (state.reached.isEmpty() && state.next == null) return
    val shapes = listOf(
        androidx.compose.material3.MaterialShapes.Cookie9Sided, androidx.compose.material3.MaterialShapes.Flower,
        androidx.compose.material3.MaterialShapes.Sunny, androidx.compose.material3.MaterialShapes.Clover4Leaf,
    )
    BloomCard(Modifier.fillMaxWidth()) {
        Text("Milestones", style = MaterialTheme.typography.titleLarge)
        state.next?.let { (label, left) ->
            Text("Next: $label, ${left.fmt()} kg to go on your trend", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.reached.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.reached.sortedByDescending { it.rank }, key = { it.id }) { m ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
                        Box(
                            Modifier.size(64.dp).clip(shapes[(m.rank % shapes.size)].toShape())
                                .background(if (m.big) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(m.hero, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                                color = if (m.big) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Text(m.title, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
