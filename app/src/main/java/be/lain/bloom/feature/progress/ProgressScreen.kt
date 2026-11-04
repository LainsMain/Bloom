package be.lain.bloom.feature.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.lain.bloom.core.designsystem.BloomCard
import be.lain.bloom.core.designsystem.ConnectedChoice
import be.lain.bloom.core.designsystem.EmptyState
import be.lain.bloom.core.designsystem.SectionHeader
import be.lain.bloom.core.designsystem.StatTile
import be.lain.bloom.core.designsystem.WeightChart
import be.lain.bloom.core.util.fmt
import be.lain.bloom.core.util.relative
import be.lain.bloom.core.util.shortWithYear
import be.lain.bloom.core.util.signed
import be.lain.bloom.data.db.MeasurementEntity
import be.lain.bloom.feature.body.LogWeightSheet

@Composable
fun ProgressScreen(navigate: (be.lain.bloom.ui.Route) -> Unit, vm: ProgressViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val extras by vm.extras.collectAsStateWithLifecycle()
    var weightSheet by rememberSaveable { mutableStateOf(false) }
    var measureSheet by rememberSaveable { mutableStateOf(false) }
    var showAllWeights by rememberSaveable { mutableStateOf(false) }
    if (!state.loaded) return
    val profile = state.profile

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Progress", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { weightSheet = true }) {
                    Icon(Icons.Rounded.Add, null)
                    Text("Weigh in")
                }
            }
        }
        if (state.points.isEmpty() && state.weights.isEmpty()) {
            item {
                EmptyState(MaterialShapes.Cookie7Sided, Icons.Rounded.MonitorWeight, "No weigh-ins yet",
                    "Log your weight a few times a week. The trend line smooths out daily water swings.")
            }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Trend", state.currentTrend?.fmt() ?: "–", Modifier.weight(1f), unit = "kg")
                    StatTile("Since start", state.changeSinceStart?.signed() ?: "–", Modifier.weight(1f), unit = "kg")
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Per week", state.weeklyRate?.signed(2) ?: "–", Modifier.weight(1f), unit = "kg",
                        supporting = "last 14 days")
                    StatTile("To goal", state.toGo?.fmt() ?: "–", Modifier.weight(1f), unit = state.toGo?.let { "kg" },
                        supporting = profile?.let { "goal ${it.goalWeightKg.fmt()} kg" })
                }
            }
            item {
                BloomCard(Modifier.fillMaxWidth()) {
                    Text("Weight", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    ConnectedChoice(ChartRange.entries, state.range, { it.label }, vm::setRange)
                    Spacer(Modifier.height(16.dp))
                    WeightChart(
                        points = state.points,
                        goalKg = profile?.goalWeightKg?.takeIf { state.showProjection },
                        projection = state.projection,
                        periodDays = extras.periodDays,
                    )
                    if (state.plannedGoalDate != null) {
                        Spacer(Modifier.height(12.dp))
                        FilterChip(
                            selected = state.showProjection,
                            onClick = vm::toggleProjection,
                            label = { Text("Show projection to goal") },
                        )
                    }
                }
            }
            if (state.plannedGoalDate != null) {
                item {
                    BloomCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Text("Goal outlook", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "On your planned pace: around ${state.plannedGoalDate!!.shortWithYear()}." +
                                (state.currentRateGoalDate?.let { "\nAt your current rate: around ${it.shortWithYear()}." } ?: ""),
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }

        item {
            val ms by vm.milestones.collectAsStateWithLifecycle()
            ms?.let { MilestonesCard(it) }
        }
        item { IntakeCard(extras.intake) }
        item {
            ReviewsCard(extras.reviews, extras.reviewDue, extras.nextReview, onStart = { navigate(be.lain.bloom.ui.Route.WeeklyReview) },
                onOpen = { navigate(be.lain.bloom.ui.Route.ReviewDetail(it)) })
        }

        item { SectionHeader("Body measurements") { TextButton(onClick = { measureSheet = true }) { Text("Add") } } }
        val latest = state.measurements.firstOrNull()
        if (latest == null) {
            item {
                BloomCard(Modifier.fillMaxWidth(), onClick = { measureSheet = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Straighten, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.padding(6.dp))
                        Text(
                            "Measure your waist and hips every couple of weeks. Shape often changes before the scale does.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        } else {
            item { MeasurementCard(latest, state.measurements.getOrNull(1), state.today, onDelete = { vm.deleteMeasurement(latest.id) }) }
            item { MeasurementTrendCard(state.measurements) }
        }
        item {
            PhotosCard(extras.photos, vm::addPhotos, vm::deletePhoto,
                onCamera = { navigate(be.lain.bloom.ui.Route.ProgressCamera) }, onCompare = { navigate(be.lain.bloom.ui.Route.PhotoCompare) })
        }
        if (extras.weeks.any { it.workouts > 0 || it.runs > 0 }) {
            item { SectionHeader("Training") }
            item { TrainingWeeksCard(extras.weeks, extras.plannedStrength) }
        }

        if (state.weights.isNotEmpty()) {
            item { SectionHeader("Weigh-ins") }
            val shown = if (showAllWeights) state.weights else state.weights.take(10)
            items(shown, key = { it.id }) { w ->
                Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(w.date.relative(state.today), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("${w.kg.fmt()} kg", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { vm.deleteWeight(w.id) }) { Icon(Icons.Rounded.DeleteOutline, "Delete weigh-in of ${w.date}") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            if (state.weights.size > 10 && !showAllWeights) {
                item { TextButton(onClick = { showAllWeights = true }) { Text("Show all ${state.weights.size}") } }
            }
        }
    }

    if (weightSheet) {
        LogWeightSheet(
            initialKg = state.weights.firstOrNull()?.kg ?: profile?.startWeightKg ?: 70.0,
            onDismiss = { weightSheet = false },
            onSave = vm::logWeight,
        )
    }
    if (measureSheet) {
        MeasurementSheet(previous = state.measurements.firstOrNull(), onDismiss = { measureSheet = false }, onSave = vm::logMeasurement)
    }
}

@Composable
private fun MeasurementCard(m: MeasurementEntity, prev: MeasurementEntity?, today: java.time.LocalDate, onDelete: () -> Unit) {
    BloomCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Latest · ${m.date.relative(today)}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Delete measurement") }
        }
        val whr = if (m.waistCm != null && m.hipsCm != null && m.hipsCm > 0) m.waistCm / m.hipsCm else null
        if (whr != null) {
            Text("Waist-to-hip ratio ${whr.fmt(2)}", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
        }
        listOf(
            "Waist" to (m.waistCm to prev?.waistCm),
            "Hips" to (m.hipsCm to prev?.hipsCm),
            "Chest" to (m.chestCm to prev?.chestCm),
            "Thigh" to (m.thighCm to prev?.thighCm),
            "Arm" to (m.armCm to prev?.armCm),
        ).filter { it.second.first != null }.forEach { (label, values) ->
            val (now, before) = values
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("${now!!.fmt()} cm", style = MaterialTheme.typography.bodyLarge)
                if (before != null) {
                    Text("  ${(now - before).signed()}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MeasurementSheet(previous: MeasurementEntity?, onDismiss: () -> Unit, onSave: (MeasurementEntity) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val fields = remember {
        listOf("Waist", "Hips", "Chest", "Thigh", "Arm").associateWith { mutableStateOf("") }
    }
    fun v(k: String) = fields.getValue(k).value.replace(',', '.').toDoubleOrNull()?.takeIf { it in 10.0..250.0 }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text("Body measurements", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Measure relaxed, tape level. Waist at the narrowest point, hips at the widest.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            fields.forEach { (label, state) ->
                val prevValue = when (label) {
                    "Waist" -> previous?.waistCm; "Hips" -> previous?.hipsCm; "Chest" -> previous?.chestCm
                    "Thigh" -> previous?.thighCm; else -> previous?.armCm
                }
                OutlinedTextField(
                    value = state.value,
                    onValueChange = { state.value = it.take(6) },
                    label = { Text("$label (cm)") },
                    placeholder = prevValue?.let { { Text("Last: ${it.fmt()}") } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            val any = fields.keys.any { v(it) != null }
            Button(
                onClick = {
                    onSave(MeasurementEntity(date = java.time.LocalDate.now(), waistCm = v("Waist"), hipsCm = v("Hips"),
                        chestCm = v("Chest"), thighCm = v("Thigh"), armCm = v("Arm")))
                    onDismiss()
                },
                enabled = any,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
            ) { Text("Save", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}
