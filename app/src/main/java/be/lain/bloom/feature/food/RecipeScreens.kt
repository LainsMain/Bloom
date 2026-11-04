package be.lain.bloom.feature.food

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.AppMessages
import be.lain.bloom.core.designsystem.BloomCard
import be.lain.bloom.core.designsystem.DecimalField
import be.lain.bloom.core.designsystem.EmptyState
import be.lain.bloom.core.designsystem.NumberStepper
import be.lain.bloom.core.util.fmt
import be.lain.bloom.data.db.RecipeEntity
import be.lain.bloom.data.db.RecipeIngredientEntity
import be.lain.bloom.data.food.FoodRepository
import be.lain.bloom.data.food.RecipeDraft
import be.lain.bloom.domain.FoodCandidate
import be.lain.bloom.domain.Recipes
import be.lain.bloom.ui.Route
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class RecipesViewModel @Inject constructor(repo: FoodRepository) : ViewModel() {
    val recipes = repo.recipes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun RecipesScreen(onBack: () -> Unit, navigate: (Route) -> Unit, vm: RecipesViewModel = hiltViewModel()) {
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Recipes") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { navigate(Route.RecipeEditor()) }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("New recipe") })
        },
    ) { padding ->
        val list = recipes ?: return@Scaffold
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(MaterialShapes.SoftBurst, Icons.Rounded.MenuBook, "No recipes yet",
                    "Add the ingredients once, weigh the finished dish, and log any portion in seconds.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 96.dp)) {
                items(list, key = { it.id }) { r ->
                    FoodRow(r.name, "${r.servings} portion${if (r.servings == 1) "" else "s"}" + (r.cookedWeightG?.let { " · ${it.fmt(0)} g cooked" } ?: ""),
                        onClick = { navigate(Route.RecipeEditor(r.id)) })
                }
            }
        }
    }
}

data class RecipeEditorState(
    val loaded: Boolean = false,
    val existing: RecipeEntity? = null,
    val name: String = "",
    val servings: Int = 4,
    val cookedWeightG: Double? = null,
    val ingredients: List<RecipeIngredientEntity> = emptyList(),
    val version: Int = 0,
) {
    private val parts get() = ingredients.map { Recipes.Ingredient(it.grams, it.per100) }
    val per100 get() = Recipes.per100(parts, cookedWeightG)
    val totalWeight get() = Recipes.totalWeight(parts, cookedWeightG)
    val perPortion get() = per100.forGrams(totalWeight / servings.coerceAtLeast(1))
    val totalKcal get() = ingredients.sumOf { it.per100.forGrams(it.grams).kcal ?: 0.0 }
}

@HiltViewModel(assistedFactory = RecipeEditorViewModel.Factory::class)
class RecipeEditorViewModel @AssistedInject constructor(
    @Assisted private val key: Route.RecipeEditor,
    private val repo: FoodRepository,
    private val messages: AppMessages,
) : ViewModel() {
    @AssistedFactory interface Factory { fun create(key: Route.RecipeEditor): RecipeEditorViewModel }

    private val _state = MutableStateFlow(RecipeEditorState())
    val state = _state.asStateFlow()
    val searcher = FoodSearcher(repo, viewModelScope)
    private val _done = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val done = _done.asSharedFlow()

    init {
        viewModelScope.launch {
            val r = key.recipeId?.let { repo.recipe(it) }
            _state.value = if (r == null) RecipeEditorState(loaded = true)
            else RecipeEditorState(true, r, r.name, r.servings, r.cookedWeightG, repo.recipeIngredients(r.id))
        }
    }

    fun setName(n: String) = _state.update { it.copy(name = n) }
    fun setServings(n: Int) = _state.update { it.copy(servings = n.coerceIn(1, 50)) }
    fun setCooked(g: Double?) = _state.update { it.copy(cookedWeightG = g) }
    fun remove(i: Int) = _state.update { it.copy(ingredients = it.ingredients.filterIndexed { idx, _ -> idx != i }, version = it.version + 1) }

    fun add(c: FoodCandidate, grams: Double) = viewModelScope.launch {
        val food = repo.materialize(c)
        _state.update {
            it.copy(ingredients = it.ingredients + RecipeIngredientEntity(recipeId = 0, foodId = food.id, name = food.name, grams = grams, per100 = food.per100))
        }
    }

    fun save() = viewModelScope.launch {
        val s = _state.value
        if (s.name.isBlank() || s.ingredients.isEmpty()) return@launch
        repo.saveRecipe(RecipeDraft(s.existing?.id, s.name.trim(), s.servings, s.cookedWeightG, s.ingredients))
        messages.show("Saved ${s.name}")
        _done.tryEmit(Unit)
    }

    fun delete() = viewModelScope.launch {
        val r = _state.value.existing ?: return@launch
        repo.deleteRecipe(r.id)
        messages.show("Deleted ${r.name}")
        _done.tryEmit(Unit)
    }
}

@Composable
fun RecipeEditorScreen(
    key: Route.RecipeEditor,
    onBack: () -> Unit,
    vm: RecipeEditorViewModel = hiltViewModel<RecipeEditorViewModel, RecipeEditorViewModel.Factory>(key = key.toString()) { it.create(key) },
) {
    val s by vm.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.done.collect { onBack() } }
    if (!s.loaded) return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (s.existing == null) "New recipe" else "Edit recipe") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { if (s.existing != null) IconButton(onClick = vm::delete) { Icon(Icons.Rounded.DeleteOutline, "Delete recipe") } },
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding().padding(16.dp)) {
                Button(
                    onClick = vm::save, enabled = s.name.isNotBlank() && s.ingredients.isNotEmpty(), shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
                ) { Text("Save recipe", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { OutlinedTextField(s.name, vm::setName, label = { Text("Recipe name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ingredients", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { picking = true }) { Icon(Icons.Rounded.Add, null); Text("Add") }
                }
            }
            if (s.ingredients.isEmpty()) item { Hint("Add each ingredient with its raw weight.") }
            items(s.ingredients.size) { i ->
                val ing = s.ingredients[i]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(ing.name, style = MaterialTheme.typography.titleSmall)
                        Text("${ing.grams.fmt(0)} g · ${ing.per100.forGrams(ing.grams).kcal.kcalText()}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.remove(i) }) { Icon(Icons.Rounded.Close, "Remove ${ing.name}") }
                }
            }
            item {
                NumberStepper(s.servings.toDouble(), { vm.setServings(it.roundToInt()) }, "portions", label = "Makes", min = 1.0, max = 50.0,
                    modifier = Modifier.fillMaxWidth())
            }
            item {
                DecimalField("Finished dish weight (optional)", s.cookedWeightG, vm::setCooked, Modifier.fillMaxWidth(), suffix = "g", decimals = 0)
                Text(
                    "Weigh the pot after cooking (minus the pot) — cooking changes water weight, so this makes gram portions accurate.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (s.ingredients.isNotEmpty()) {
                item {
                    BloomCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text("Per portion", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(s.perPortion.kcal.kcalText(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        MacroLine(s.perPortion)
                        Spacer(Modifier.height(6.dp))
                        Text("Whole dish ${s.totalKcal.fmt(0)} kcal · ${s.totalWeight.fmt(0)} g · ${s.per100.kcal.kcalText()} per 100 g",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
    }

    if (picking) IngredientPicker(vm, onDismiss = { picking = false })
}

@Composable
private fun IngredientPicker(vm: RecipeEditorViewModel, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val results by vm.searcher.results.collectAsStateWithLifecycle()
    var chosen by remember { mutableStateOf<FoodCandidate?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().fillMaxSize(0.92f).imePadding()) {
            OutlinedTextField(
                results.query, vm.searcher::setQuery, placeholder = { Text("Search ingredient") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            LazyColumn(contentPadding = PaddingValues(8.dp)) {
                val all = results.local + results.generic + results.online
                items(all, key = { "${it.source}-${it.localId}-${it.externalId}-${it.name}" }) { c ->
                    FoodRow(c.name, c.subtitle(), onClick = { chosen = c })
                }
                if (results.onlineLoading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
            }
        }
    }
    chosen?.let { c ->
        var grams by remember { mutableDoubleStateOf(c.servings.firstOrNull()?.grams ?: 100.0) }
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(c.name) },
            text = { NumberStepper(grams, { grams = it }, c.unit, step = 10.0, min = 1.0, max = 10000.0, label = "Raw weight") },
            confirmButton = { TextButton(onClick = { vm.add(c, grams); chosen = null; onDismiss() }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { chosen = null }) { Text("Cancel") } },
        )
    }
}
