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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.util.fmt
import be.lain.bloom.core.util.relative
import be.lain.bloom.data.db.FoodEntity
import be.lain.bloom.data.db.MealTemplateEntity
import be.lain.bloom.data.db.MealTemplateItemEntity
import be.lain.bloom.data.food.FoodRepository
import be.lain.bloom.domain.EntrySource
import be.lain.bloom.domain.FoodCandidate
import be.lain.bloom.domain.Meal
import be.lain.bloom.domain.Nutrients
import be.lain.bloom.ui.Route
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class SearchResults(
    val query: String = "",
    val local: List<FoodCandidate> = emptyList(),
    val generic: List<FoodCandidate> = emptyList(),
    val online: List<FoodCandidate> = emptyList(),
    val onlineLoading: Boolean = false,
    val onlineError: String? = null,
)

/** Debounced three-source search shared by the food search screen and the recipe ingredient picker. */
class FoodSearcher(private val repo: FoodRepository, private val scope: CoroutineScope) {
    private val _results = MutableStateFlow(SearchResults())
    val results: StateFlow<SearchResults> = _results.asStateFlow()
    private var job: Job? = null

    @OptIn(FlowPreview::class)
    fun setQuery(q: String) {
        _results.update { it.copy(query = q) }
        job?.cancel()
        val query = q.trim()
        if (query.length < 2) {
            _results.value = SearchResults(query = q)
            return
        }
        job = scope.launch {
            delay(250)
            val local = repo.searchLocal(query)
            // Hide generic foods already saved locally (they show under "Your foods").
            val generic = runCatching { repo.searchGeneric(query) }.getOrDefault(emptyList())
                .filter { g -> local.none { it.source == g.source && it.externalId == g.externalId } }
            _results.update { it.copy(local = local, generic = generic, online = emptyList(), onlineLoading = true, onlineError = null) }
            delay(250)
            val online = runCatching { repo.searchOnline(query) }
            _results.update {
                it.copy(
                    online = online.getOrDefault(emptyList()).filter { c -> local.none { l -> l.barcode != null && l.barcode == c.barcode } },
                    onlineLoading = false,
                    onlineError = online.exceptionOrNull()?.let { "Couldn't reach Open Food Facts" },
                )
            }
        }
    }
}

enum class BrowseTab(val label: String) { RECENT("Recent"), FAVORITES("Favourites"), MINE("My foods"), MEALS("Meals") }

data class TemplateWithItems(val template: MealTemplateEntity, val items: List<MealTemplateItemEntity>) {
    val kcal: Double get() = items.sumOf { it.nutrients.kcal ?: 0.0 }
}

sealed interface FoodEvent {
    data class Logged(val message: String, val entryIds: List<Long>) : FoodEvent
    data class Open(val route: Route) : FoodEvent
    data class Message(val text: String) : FoodEvent
}

@HiltViewModel(assistedFactory = FoodSearchViewModel.Factory::class)
class FoodSearchViewModel @AssistedInject constructor(
    @Assisted private val key: Route.FoodSearch,
    private val repo: FoodRepository,
) : ViewModel() {
    @AssistedFactory interface Factory { fun create(key: Route.FoodSearch): FoodSearchViewModel }

    val date: LocalDate = LocalDate.ofEpochDay(key.date)
    val today: LocalDate get() = repo.today()
    private val _meal = MutableStateFlow(key.meal)
    val meal: StateFlow<Meal> = _meal.asStateFlow()
    val searcher = FoodSearcher(repo, viewModelScope)
    private val _events = MutableSharedFlow<FoodEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    val recent = repo.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val favorites = repo.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val mine = repo.myFoods.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val templates = combine(repo.templates, repo.templateItems) { t, items ->
        t.map { TemplateWithItems(it, items.filter { i -> i.templateId == it.id }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setMeal(m: Meal) { _meal.value = m }

    fun open(c: FoodCandidate) = viewModelScope.launch {
        val food = repo.materialize(c)
        _events.emit(FoodEvent.Open(Route.LogFood(food.id, key.date, _meal.value)))
    }

    fun quickLog(c: FoodCandidate) = viewModelScope.launch {
        val food = repo.materialize(c)
        val (grams, amount, unit) = repo.defaultPortion(food)
        val id = repo.log(food, grams, amount, unit, _meal.value, date)
        val kcal = food.per100.forGrams(grams).kcal
        _events.emit(FoodEvent.Logged("Added ${food.name} · ${kcal.kcalText()}", listOf(id)))
    }

    fun quickLogEntity(f: FoodEntity) = quickLog(f.toCandidate())

    fun quickAdd(name: String, n: Nutrients) = viewModelScope.launch {
        val id = repo.quickAdd(name, n, _meal.value, date, EntrySource.QUICK)
        _events.emit(FoodEvent.Logged("Added ${n.kcal.kcalText()} to ${_meal.value.label}", listOf(id)))
    }

    fun logTemplate(t: TemplateWithItems) = viewModelScope.launch {
        val ids = repo.logTemplate(t.template.id, _meal.value, date)
        _events.emit(FoodEvent.Logged("Added ${t.template.name} · ${t.kcal.kcalText()}", ids))
    }

    fun deleteTemplate(id: Long) = viewModelScope.launch { repo.deleteTemplate(id) }

    fun undo(ids: List<Long>) = viewModelScope.launch { repo.deleteEntries(ids) }
}

@Composable
fun FoodSearchScreen(
    key: Route.FoodSearch,
    navigate: (Route) -> Unit,
    onBack: () -> Unit,
    vm: FoodSearchViewModel = hiltViewModel<FoodSearchViewModel, FoodSearchViewModel.Factory>(key = key.toString()) { it.create(key) },
) {
    val results by vm.searcher.results.collectAsStateWithLifecycle()
    val meal by vm.meal.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val mine by vm.mine.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var quickAdd by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is FoodEvent.Open -> navigate(e.route)
                is FoodEvent.Message -> snackbar.showSnackbar(e.text)
                is FoodEvent.Logged -> launch {
                    val r = snackbar.showSnackbar(e.message, actionLabel = "Undo", withDismissAction = true)
                    if (r == SnackbarResult.ActionPerformed) vm.undo(e.entryIds)
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                TextField(
                    value = results.query,
                    onValueChange = vm.searcher::setQuery,
                    placeholder = { Text("Search foods") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (results.query.isNotEmpty()) IconButton(onClick = { vm.searcher.setQuery("") }) { Icon(Icons.Rounded.Close, "Clear") }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.weight(1f).padding(end = 12.dp).focusRequester(focus),
                )
            }
            Text(
                "Adding to ${meal.label.lowercase()} · ${vm.date.relative(vm.today)}",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            MealChips(meal, vm::setMeal, Modifier.padding(horizontal = 16.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    AssistChip(onClick = { navigate(Route.Scan(vm.date.toEpochDay(), meal)) }, label = { Text("Scan barcode") },
                        leadingIcon = { Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(AssistChipDefaults.IconSize)) })
                }
                item {
                    AssistChip(onClick = { navigate(Route.QuickLog(vm.date.toEpochDay(), meal)) }, label = { Text("Describe or snap") },
                        leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(AssistChipDefaults.IconSize)) })
                }
                item {
                    AssistChip(onClick = { quickAdd = true }, label = { Text("Quick add") },
                        leadingIcon = { Icon(Icons.Rounded.Bolt, null, Modifier.size(AssistChipDefaults.IconSize)) })
                }
                item {
                    AssistChip(onClick = { navigate(Route.FoodEditor(date = vm.date.toEpochDay(), meal = meal)) }, label = { Text("Create food") },
                        leadingIcon = { Icon(Icons.Rounded.EditNote, null, Modifier.size(AssistChipDefaults.IconSize)) })
                }
                item {
                    AssistChip(onClick = { navigate(Route.Recipes) }, label = { Text("Recipes") },
                        leadingIcon = { Icon(Icons.Rounded.MenuBook, null, Modifier.size(AssistChipDefaults.IconSize)) })
                }
            }
            if (results.query.trim().length < 2) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    BrowseTab.entries.forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t.label, maxLines = 1) })
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 96.dp)) {
                    when (BrowseTab.entries[tab]) {
                        BrowseTab.RECENT -> entityList(recent, "Foods you log appear here for one-tap adding.", vm)
                        BrowseTab.FAVORITES -> entityList(favorites, "Tap the heart on a food to keep it here.", vm)
                        BrowseTab.MINE -> entityList(mine, "Foods you create (or the AI finds) appear here.", vm)
                        BrowseTab.MEALS -> {
                            if (templates.isEmpty()) item { Hint("Save a meal from your diary (⋮ on a meal) to log it again in one tap.") }
                            items(templates, key = { it.template.id }) { t ->
                                FoodRow(
                                    title = t.template.name,
                                    subtitle = t.items.joinToString(", ") { it.name },
                                    trailing = t.kcal.kcalText(),
                                    onClick = { vm.logTemplate(t) },
                                    onQuickAdd = { vm.logTemplate(t) },
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 96.dp)) {
                    resultSection("Your foods", results.local, vm)
                    resultSection("Basic foods", results.generic, vm)
                    item { SectionLabel("Products") }
                    if (results.onlineLoading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { LoadingIndicator() } }
                    results.onlineError?.let { item { Hint(it) } }
                    items(results.online, key = { "o-${it.barcode}-${it.name}" }) { c ->
                        FoodRow(c.name, c.subtitle(), onClick = { vm.open(c) }, onQuickAdd = { vm.quickLog(c) })
                    }
                    if (!results.onlineLoading && results.local.isEmpty() && results.generic.isEmpty() && results.online.isEmpty()) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Nothing found for “${results.query}”", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { navigate(Route.FoodEditor(date = vm.date.toEpochDay(), meal = meal)) }) { Text("Create it") }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding())
    }

    if (quickAdd) QuickAddSheet(onDismiss = { quickAdd = false }, onSave = vm::quickAdd)
}

private fun LazyListScope.entityList(list: List<FoodEntity>, empty: String, vm: FoodSearchViewModel) {
    if (list.isEmpty()) item { Hint(empty) }
    items(list, key = { "e-${it.id}" }) { f ->
        FoodRow(f.name, f.toCandidate().subtitle(), onClick = { vm.open(f.toCandidate()) }, onQuickAdd = { vm.quickLogEntity(f) })
    }
}

private fun LazyListScope.resultSection(title: String, list: List<FoodCandidate>, vm: FoodSearchViewModel) {
    if (list.isEmpty()) return
    item { SectionLabel(title) }
    items(list, key = { "${title}-${it.localId}-${it.externalId}-${it.name}" }) { c ->
        FoodRow(c.name, c.subtitle(), onClick = { vm.open(c) }, onQuickAdd = { vm.quickLog(c) })
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp))
}

@Composable
fun QuickAddSheet(onDismiss: () -> Unit, onSave: (String, Nutrients) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    fun num(s: String) = s.replace(',', '.').toDoubleOrNull()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).imePadding()) {
            Text("Quick add", style = MaterialTheme.typography.headlineSmall)
            Text("For when you just know the numbers.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(kcal, { kcal = it }, label = { Text("Calories (kcal)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(protein, { protein = it }, label = { Text("Protein g") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedTextField(carbs, { carbs = it }, label = { Text("Carbs g") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedTextField(fat, { fat = it }, label = { Text("Fat g") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(), modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            val k = num(kcal) ?: Nutrients.kcalFromMacros(num(protein) ?: 0.0, num(carbs) ?: 0.0, num(fat) ?: 0.0)?.takeIf { it > 0 }
            Button(
                onClick = { onSave(name, Nutrients(kcal = k, protein = num(protein), carbs = num(carbs), fat = num(fat))); onDismiss() },
                enabled = k != null && k > 0,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
            ) { Text(k?.let { "Add ${it.fmt(0)} kcal" } ?: "Add", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
        }
    }
}
