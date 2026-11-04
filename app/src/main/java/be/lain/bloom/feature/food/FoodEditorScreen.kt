package be.lain.bloom.feature.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.AppMessages
import be.lain.bloom.core.designsystem.BloomCard
import be.lain.bloom.core.designsystem.ConnectedChoice
import be.lain.bloom.core.designsystem.DecimalField
import be.lain.bloom.data.db.FoodEntity
import be.lain.bloom.data.food.FoodRepository
import be.lain.bloom.domain.FoodSource
import be.lain.bloom.domain.Nutrients
import be.lain.bloom.domain.Serving
import be.lain.bloom.ui.Route
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import be.lain.bloom.data.ai.AiFood
import be.lain.bloom.data.ai.CoachEngine
import be.lain.bloom.data.ai.FoodAi
import be.lain.bloom.data.ai.SecretStore
import be.lain.bloom.feature.coach.rememberPhotoPickers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

enum class Basis(val label: String) { PER_100("Per 100 g/ml"), PER_SERVING("Per serving") }

data class FoodForm(
    val name: String = "",
    val brand: String = "",
    val barcode: String = "",
    val liquid: Boolean = false,
    val basis: Basis = Basis.PER_100,
    val servingLabel: String = "",
    val servingGrams: Double? = null,
    /** Values as entered (per 100 or per serving, see [basis]). */
    val values: Nutrients = Nutrients(),
    val source: FoodSource = FoodSource.USER,
) {
    val per100: Nutrients
        get() = if (basis == Basis.PER_SERVING && (servingGrams ?: 0.0) > 0) values.scaled(100.0 / servingGrams!!) else values

    val error: String?
        get() = when {
            name.isBlank() -> "Give it a name"
            values.kcal == null && Nutrients.kcalFromMacros(values.protein, values.carbs, values.fat) == null -> "Add calories (or protein, carbs and fat)"
            basis == Basis.PER_SERVING && (servingGrams ?: 0.0) <= 0 -> "Add the serving size in grams"
            else -> null
        }

    companion object {
        fun from(f: FoodEntity) = FoodForm(
            name = f.name, brand = f.brand.orEmpty(), barcode = f.barcode.orEmpty(), liquid = f.liquid,
            servingLabel = f.servings.firstOrNull()?.label.orEmpty(), servingGrams = f.servings.firstOrNull()?.grams,
            values = f.per100, source = f.source,
        )
    }
}

data class FoodEditorState(
    val loaded: Boolean = false,
    val existing: FoodEntity? = null,
    val form: FoodForm = FoodForm(),
    /** Bumped when the form is filled from outside (e.g. by the AI) so fields re-read their values. */
    val version: Int = 0,
    val busy: String? = null,
    val note: String? = null,
)

@HiltViewModel(assistedFactory = FoodEditorViewModel.Factory::class)
class FoodEditorViewModel @AssistedInject constructor(
    @Assisted val key: Route.FoodEditor,
    private val repo: FoodRepository,
    private val messages: AppMessages,
    private val foodAi: FoodAi,
    private val engine: CoachEngine,
    secrets: SecretStore,
) : ViewModel() {
    @AssistedFactory interface Factory { fun create(key: Route.FoodEditor): FoodEditorViewModel }

    private val _state = MutableStateFlow(FoodEditorState())
    val state = _state.asStateFlow()
    private val _done = MutableSharedFlow<Route?>(extraBufferCapacity = 1)
    val done = _done.asSharedFlow()
    val hasKey = secrets.hasDeepSeekKey.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            val existing = key.foodId?.let { repo.food(it) }
            _state.value = FoodEditorState(
                loaded = true, existing = existing,
                form = existing?.let { FoodForm.from(it) } ?: FoodForm(barcode = key.barcode.orEmpty()),
            )
            if (key.aiMode == "barcode" && !key.barcode.isNullOrBlank() && secrets.deepSeekKey() != null) findOnline()
        }
    }

    private fun apply(r: AiFood, sourceIsAi: Boolean = true) {
        val f = _state.value.form
        if (!r.found) { setNote(r.note.ifBlank { "Couldn't find reliable nutrition data. You can type it from the label." }); return }
        fill(
            f.copy(
                name = r.name.ifBlank { f.name }, brand = r.brand.ifBlank { f.brand }, liquid = r.liquid, basis = Basis.PER_100,
                servingLabel = r.servingLabel.ifBlank { f.servingLabel }, servingGrams = r.servingGrams ?: f.servingGrams,
                values = r.per100, source = if (sourceIsAi) FoodSource.AI else f.source,
            ),
            listOfNotNull("Filled in by AI — please check against the label.", r.note.ifBlank { null }, r.source.ifBlank { null }?.let { "Source: $it" })
                .joinToString("\n"),
        )
    }

    fun findOnline() = viewModelScope.launch {
        val f = _state.value.form
        setBusy("Searching the web…")
        runCatching {
            foodAi.findByBarcode(f.barcode.ifBlank { "(no barcode)" }, listOf(f.brand, f.name).filter { it.isNotBlank() }.joinToString(" ")) { setBusy(it) }
        }.onSuccess { apply(it) }.onFailure { setNote(it.message) }
    }

    fun readLabel(uri: android.net.Uri) = viewModelScope.launch {
        setBusy("Reading the label…")
        runCatching { foodAi.readLabel(engine.importImage(uri)) }.onSuccess { apply(it) }.onFailure { setNote(it.message) }
    }

    fun update(f: FoodForm) = _state.update { it.copy(form = f) }

    /** Replaces the form from an outside source (AI) and refreshes the fields. */
    fun fill(f: FoodForm, note: String?) = _state.update { it.copy(form = f, version = it.version + 1, note = note, busy = null) }
    fun setBusy(text: String?) = _state.update { it.copy(busy = text) }
    fun setNote(text: String?) = _state.update { it.copy(note = text, busy = null) }

    fun save() = viewModelScope.launch {
        val s = _state.value
        val f = s.form
        if (f.error != null) return@launch
        val per100 = f.per100.let { n -> if (n.kcal == null) n.copy(kcal = Nutrients.kcalFromMacros(n.protein, n.carbs, n.fat)) else n }
        val servings = if ((f.servingGrams ?: 0.0) > 0) listOf(Serving(f.servingLabel.ifBlank { "1 serving" }, f.servingGrams!!)) else emptyList()
        val base = s.existing ?: FoodEntity(name = f.name, source = f.source, per100 = per100, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)
        val saved = repo.saveFood(
            base.copy(
                name = f.name.trim(), brand = f.brand.trim().ifBlank { null }, barcode = f.barcode.trim().ifBlank { null },
                liquid = f.liquid, per100 = per100,
                servings = if (s.existing != null && servings.isEmpty()) s.existing.servings else servings + (s.existing?.servings?.drop(1) ?: emptyList()),
                source = if (s.existing == null) f.source else s.existing.source,
            ),
        )
        messages.show("Saved ${saved.name}")
        _done.tryEmit(if (key.date != null && key.meal != null) Route.LogFood(saved.id, key.date, key.meal) else null)
    }

    fun delete() = viewModelScope.launch {
        val e = _state.value.existing ?: return@launch
        repo.hideFood(e.id)
        messages.show("Deleted ${e.name}")
        _done.tryEmit(null)
    }
}

@Composable
fun FoodEditorScreen(
    key: Route.FoodEditor,
    onBack: () -> Unit,
    replace: (Route) -> Unit,
    vm: FoodEditorViewModel = hiltViewModel<FoodEditorViewModel, FoodEditorViewModel.Factory>(key = key.toString()) { it.create(key) },
) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.done.collect { next -> if (next != null) replace(next) else onBack() } }
    if (!s.loaded) return
    val f = s.form
    val unit = if (f.liquid) "ml" else "g"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (s.existing == null) "New food" else "Edit food") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (s.existing != null && s.existing!!.source != FoodSource.RECIPE) {
                        IconButton(onClick = vm::delete) { Icon(Icons.Rounded.DeleteOutline, "Delete food") }
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding().padding(16.dp)) {
                f.error?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp)) }
                Button(
                    onClick = vm::save, enabled = f.error == null && s.busy == null, shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
                ) {
                    Text(if (key.date != null) "Save & continue" else "Save", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight))
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AiFillCard(vm, s)
            key(s.version) {
                OutlinedTextField(f.name, { vm.update(f.copy(name = it)) }, label = { Text("Name") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(f.brand, { vm.update(f.copy(brand = it)) }, label = { Text("Brand") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(f.barcode, { v -> vm.update(f.copy(barcode = v.filter { it.isDigit() }.take(14))) }, label = { Text("Barcode") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("It's a drink (values per 100 ml)", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = f.liquid, onCheckedChange = { vm.update(f.copy(liquid = it)) })
                }
                Text("Nutrition values", style = MaterialTheme.typography.titleMedium)
                ConnectedChoice(Basis.entries, f.basis, { if (it == Basis.PER_100) "Per 100 $unit" else "Per serving" }, { vm.update(f.copy(basis = it)) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(f.servingLabel, { vm.update(f.copy(servingLabel = it)) }, label = { Text("Serving name") },
                        placeholder = { Text("e.g. 1 bar") }, singleLine = true, modifier = Modifier.weight(1.3f))
                    DecimalField("Serving size", f.servingGrams, { vm.update(f.copy(servingGrams = it)) }, Modifier.weight(1f), suffix = unit)
                }
                BloomCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
                    val v = f.values
                    fun set(n: Nutrients) = vm.update(f.copy(values = n))
                    DecimalField("Energy", v.kcal, { set(v.copy(kcal = it)) }, Modifier.fillMaxWidth(), suffix = "kcal", decimals = 0)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DecimalField("Protein", v.protein, { set(v.copy(protein = it)) }, Modifier.weight(1f), suffix = "g")
                        DecimalField("Fibre", v.fiber, { set(v.copy(fiber = it)) }, Modifier.weight(1f), suffix = "g")
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DecimalField("Carbs", v.carbs, { set(v.copy(carbs = it)) }, Modifier.weight(1f), suffix = "g")
                        DecimalField("of which sugars", v.sugar, { set(v.copy(sugar = it)) }, Modifier.weight(1f), suffix = "g")
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DecimalField("Fat", v.fat, { set(v.copy(fat = it)) }, Modifier.weight(1f), suffix = "g")
                        DecimalField("of which saturated", v.satFat, { set(v.copy(satFat = it)) }, Modifier.weight(1f), suffix = "g")
                    }
                    Spacer(Modifier.height(8.dp))
                    DecimalField("Salt", v.salt, { set(v.copy(salt = it)) }, Modifier.fillMaxWidth(), suffix = "g", decimals = 2)
                }
                Text(
                    "Leave a value empty if the label doesn't say — empty means unknown, not zero.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun AiFillCard(vm: FoodEditorViewModel, s: FoodEditorState) {
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val pickers = rememberPhotoPickers { uris -> uris.firstOrNull()?.let(vm::readLabel) }
    BloomCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(Icons.Rounded.AutoAwesome, null)
            Spacer(Modifier.width(8.dp))
            Text("Fill it in for me", style = MaterialTheme.typography.titleMedium)
        }
        if (!hasKey) {
            Text("Add your DeepSeek key in Settings → AI coach to let the AI search the web or read a label photo.",
                style = MaterialTheme.typography.bodyMedium)
        } else {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = vm::findOnline, enabled = s.busy == null && (s.form.barcode.isNotBlank() || s.form.name.isNotBlank())) {
                    androidx.compose.material3.Icon(Icons.Rounded.TravelExplore, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Search web")
                }
                FilledTonalButton(onClick = pickers.camera, enabled = s.busy == null) {
                    androidx.compose.material3.Icon(Icons.Rounded.DocumentScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Read label")
                }
            }
        }
        s.busy?.let {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(32.dp)); Spacer(Modifier.width(8.dp)); Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        s.note?.let { Spacer(Modifier.height(6.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
