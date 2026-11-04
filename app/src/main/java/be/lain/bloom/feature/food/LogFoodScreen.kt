package be.lain.bloom.feature.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.AppMessages
import be.lain.bloom.core.designsystem.BloomCard
import be.lain.bloom.core.designsystem.DecimalField
import be.lain.bloom.core.designsystem.NumberStepper
import be.lain.bloom.core.util.fmt
import be.lain.bloom.core.util.relative
import be.lain.bloom.data.db.DiaryEntryEntity
import be.lain.bloom.data.db.FoodEntity
import be.lain.bloom.data.food.FoodRepository
import be.lain.bloom.domain.FoodSource
import be.lain.bloom.domain.Meal
import be.lain.bloom.domain.Nutrients
import be.lain.bloom.ui.Route
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class LogFoodState(
    val loaded: Boolean = false,
    val food: FoodEntity? = null,
    val entry: DiaryEntryEntity? = null,
    val date: LocalDate = LocalDate.now(),
    val meal: Meal = Meal.LUNCH,
    /** 0 = grams/ml, i > 0 = food.servings[i - 1]. */
    val unitIndex: Int = 0,
    val amount: Double = 100.0,
    // Quick-add editing (entries without a food)
    val quick: Nutrients = Nutrients(),
    val quickName: String = "",
) {
    val baseUnit: String get() = if (food?.liquid == true) "ml" else "g"
    val unitLabel: String get() = if (unitIndex == 0) baseUnit else food?.servings?.getOrNull(unitIndex - 1)?.label ?: baseUnit
    val grams: Double
        get() = if (unitIndex == 0) amount else amount * (food?.servings?.getOrNull(unitIndex - 1)?.grams ?: 1.0)
    val nutrients: Nutrients get() = food?.per100?.forGrams(grams) ?: quick
    val editing: Boolean get() = entry != null
}

@HiltViewModel(assistedFactory = LogFoodViewModel.Factory::class)
class LogFoodViewModel @AssistedInject constructor(
    @Assisted private val key: Route,
    private val repo: FoodRepository,
    private val messages: AppMessages,
) : ViewModel() {
    @AssistedFactory interface Factory { fun create(key: Route): LogFoodViewModel }

    private val _state = MutableStateFlow(LogFoodState())
    val state = _state.asStateFlow()
    private val _done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val done = _done.asSharedFlow()
    val today: LocalDate get() = repo.today()

    init {
        viewModelScope.launch {
            when (key) {
                is Route.LogFood -> {
                    val food = repo.food(key.foodId)
                    val entry = key.entryId?.let { repo.entry(it) }
                    load(food, entry, LocalDate.ofEpochDay(key.date), key.meal)
                }
                is Route.EditEntry -> {
                    val entry = repo.entry(key.entryId)
                    val food = entry?.foodId?.let { repo.food(it) }
                    load(food, entry, entry?.date ?: repo.today(), entry?.meal ?: repo.defaultMeal())
                }
                else -> Unit
            }
        }
    }

    private suspend fun load(food: FoodEntity?, entry: DiaryEntryEntity?, date: LocalDate, meal: Meal) {
        var unitIndex = 0
        var amount = 100.0
        if (entry != null && food != null) {
            val idx = food.servings.indexOfFirst { it.label == entry.unit }
            if (idx >= 0) { unitIndex = idx + 1; amount = entry.amount } else amount = entry.grams
        } else if (food != null) {
            val (grams, a, unit) = repo.defaultPortion(food)
            val idx = food.servings.indexOfFirst { it.label == unit }
            if (idx >= 0) { unitIndex = idx + 1; amount = a } else amount = grams
        }
        _state.value = LogFoodState(
            loaded = true, food = food, entry = entry, date = date, meal = entry?.meal ?: meal,
            unitIndex = unitIndex, amount = amount, quick = entry?.nutrients ?: Nutrients(), quickName = entry?.name.orEmpty(),
        )
    }

    fun setAmount(a: Double) = _state.update { it.copy(amount = a) }
    fun setMeal(m: Meal) = _state.update { it.copy(meal = m) }
    fun setUnit(i: Int) = _state.update { s ->
        // Keep the same weight when switching units.
        val grams = s.grams
        val newAmount = if (i == 0) grams else (grams / (s.food?.servings?.getOrNull(i - 1)?.grams ?: grams)).let { (it * 4).let(Math::round) / 4.0 }.coerceAtLeast(0.25)
        s.copy(unitIndex = i, amount = if (i == 0) Math.round(grams).toDouble() else newAmount)
    }
    fun setQuick(n: Nutrients) = _state.update { it.copy(quick = n) }
    fun setQuickName(s: String) = _state.update { it.copy(quickName = s) }

    fun toggleFavorite() = viewModelScope.launch {
        val f = _state.value.food ?: return@launch
        repo.setFavorite(f.id, !f.favorite)
        _state.update { it.copy(food = f.copy(favorite = !f.favorite)) }
    }

    fun save() = viewModelScope.launch {
        val s = _state.value
        val food = s.food
        when {
            s.entry != null && food != null -> repo.updateEntry(s.entry, s.grams, s.amount, s.unitLabel, s.meal)
            s.entry != null -> repo.updateEntryRaw(s.entry.copy(name = s.quickName.ifBlank { "Quick add" }, nutrients = s.quick, amount = s.quick.kcal ?: 0.0, meal = s.meal))
            food != null -> {
                val id = repo.log(food, s.grams, s.amount, s.unitLabel, s.meal, s.date)
                messages.show("Added ${food.name} · ${s.nutrients.kcal.kcalText()} to ${s.meal.label.lowercase()}", "Undo") { repo.deleteEntry(id) }
            }
        }
        _done.tryEmit(Unit)
    }

    fun delete() = viewModelScope.launch {
        val e = _state.value.entry ?: return@launch
        repo.deleteEntry(e.id)
        messages.show("Removed ${e.name}", "Undo") { repo.restoreEntry(e) }
        _done.tryEmit(Unit)
    }
}

@Composable
fun LogFoodScreen(
    key: Route,
    onBack: () -> Unit,
    navigate: (Route) -> Unit,
    vm: LogFoodViewModel = hiltViewModel<LogFoodViewModel, LogFoodViewModel.Factory>(key = key.toString()) { it.create(key) },
) {
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.done.collect { onBack() } }
    if (!s.loaded) return
    val food = s.food

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(food?.name ?: s.quickName.ifBlank { "Quick add" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (food != null) {
                        IconToggleButton(checked = food.favorite, onCheckedChange = { vm.toggleFavorite() }) {
                            Icon(if (food.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                if (food.favorite) "Remove from favourites" else "Add to favourites")
                        }
                        if (food.source != FoodSource.RECIPE) {
                            IconButton(onClick = { navigate(Route.FoodEditor(foodId = food.id)) }) { Icon(Icons.Rounded.Edit, "Edit food") }
                        } else if (food.recipeId != null) {
                            IconButton(onClick = { navigate(Route.RecipeEditor(food.recipeId)) }) { Icon(Icons.Rounded.Edit, "Edit recipe") }
                        }
                    }
                    if (s.editing) IconButton(onClick = vm::delete) { Icon(Icons.Rounded.DeleteOutline, "Remove from diary") }
                },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding().padding(16.dp)) {
                Button(
                    onClick = vm::save,
                    enabled = s.grams > 0 || food == null,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
                ) {
                    Text(
                        if (s.editing) "Save changes" else "Add to ${s.meal.label.lowercase()}",
                        style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            food?.brand?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            BloomCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(s.nutrients.kcal?.fmt(0) ?: "?", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(" kcal", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(bottom = 10.dp))
                }
                MacroLine(s.nutrients)
            }

            if (food != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    val grams = s.unitIndex == 0
                    NumberStepper(
                        value = s.amount, onValueChange = vm::setAmount,
                        unit = if (grams) s.baseUnit else "×",
                        step = if (grams) 10.0 else 0.5, decimals = if (grams) 0 else 2, min = 0.0, max = 5000.0,
                        label = if (grams) "Amount" else "Number of portions",
                        trimZeros = true,
                    )
                    if (!grams) {
                        Text("${s.unitLabel} = ${food.servings[s.unitIndex - 1].grams.fmt(0)} ${s.baseUnit} · total ${s.grams.fmt(0)} ${s.baseUnit}",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (food.servings.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = s.unitIndex == 0, onClick = { vm.setUnit(0) }, label = { Text(s.baseUnit) })
                        food.servings.forEachIndexed { i, sv ->
                            FilterChip(selected = s.unitIndex == i + 1, onClick = { vm.setUnit(i + 1) },
                                label = { Text("${sv.label} (${sv.grams.fmt(0)} ${s.baseUnit})", maxLines = 1, overflow = TextOverflow.Ellipsis) })
                        }
                    }
                }
            } else {
                QuickEditor(s.quickName, s.quick, vm::setQuickName, vm::setQuick)
            }

            Column {
                Text("Meal · ${s.date.relative(vm.today)}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                MealChips(s.meal, vm::setMeal)
            }

            if (food != null) {
                BloomCard(Modifier.fillMaxWidth()) {
                    NutritionTable(s.nutrients, food.per100, "${s.grams.fmt(0)} ${s.baseUnit}", s.baseUnit)
                }
                Text(
                    buildString {
                        append("Source: ${food.source.label}")
                        food.barcode?.let { append(" · barcode $it") }
                        if (food.source == FoodSource.GENERIC) append(" · USDA FoodData Central")
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun QuickEditor(name: String, n: Nutrients, onName: (String) -> Unit, onChange: (Nutrients) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, onName, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        DecimalField("Calories", n.kcal, { onChange(n.copy(kcal = it)) }, Modifier.fillMaxWidth(), suffix = "kcal", decimals = 0)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DecimalField("Protein", n.protein, { onChange(n.copy(protein = it)) }, Modifier.weight(1f), suffix = "g")
            DecimalField("Carbs", n.carbs, { onChange(n.copy(carbs = it)) }, Modifier.weight(1f), suffix = "g")
            DecimalField("Fat", n.fat, { onChange(n.copy(fat = it)) }, Modifier.weight(1f), suffix = "g")
        }
    }
}
