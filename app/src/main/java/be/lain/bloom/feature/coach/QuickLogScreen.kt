package be.lain.bloom.feature.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.AppMessages
import be.lain.bloom.core.designsystem.EmptyState
import be.lain.bloom.data.ai.AiActions
import be.lain.bloom.data.ai.ChatMode
import be.lain.bloom.data.ai.CoachEngine
import be.lain.bloom.data.ai.RunState
import be.lain.bloom.data.ai.SecretStore
import be.lain.bloom.data.db.BloomDatabase
import be.lain.bloom.feature.food.MealChips
import be.lain.bloom.ui.Route
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = QuickLogViewModel.Factory::class)
class QuickLogViewModel @AssistedInject constructor(
    @Assisted val key: Route.QuickLog,
    private val engine: CoachEngine,
    private val db: BloomDatabase,
    private val actions: AiActions,
    private val secrets: SecretStore,
    private val messages: AppMessages,
) : ViewModel() {
    @AssistedFactory interface Factory { fun create(key: Route.QuickLog): QuickLogViewModel }

    private val chat = MutableStateFlow<Long?>(null)
    private var firstMessageId = Long.MAX_VALUE
    val meal = MutableStateFlow(key.meal)
    val attachments = MutableStateFlow<List<String>>(emptyList())
    val hasKey = secrets.hasDeepSeekKey.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val items = chat.filterNotNull().flatMapLatest { id ->
        combine(db.chatDao().observeMessages(id), db.chatDao().observeActions(id)) { m, a ->
            // Only this session's exchange.
            buildItems(m.filter { it.id >= firstMessageId }, a.filter { (it.messageId ?: Long.MAX_VALUE) >= firstMessageId })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val run = combine(engine.runs, chat) { runs, id -> id?.let { runs[it] } ?: RunState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RunState())

    init {
        viewModelScope.launch {
            val id = engine.quickLogChat()
            firstMessageId = (db.chatDao().messages(id).lastOrNull()?.id ?: 0) + 1
            chat.value = id
        }
    }

    fun send(text: String) = viewModelScope.launch {
        val id = chat.value ?: return@launch
        engine.send(id, text.trim(), attachments.value, ChatMode.QUICK_LOG, meal.value, LocalDate.ofEpochDay(key.date))
        attachments.value = emptyList()
    }

    fun stop() { chat.value?.let(engine::cancel) }
    fun attach(uris: List<android.net.Uri>) = viewModelScope.launch {
        uris.take(4).forEach { u -> runCatching { engine.importImage(u) }.onSuccess { p -> attachments.update { it + p } } }
    }
    fun removeAttachment(p: String) = attachments.update { it - p }
    fun undo(id: Long) = viewModelScope.launch { actions.undo(id); messages.show("Undone") }
    fun saveKey(k: String) = viewModelScope.launch { secrets.setDeepSeekKey(k) }
}

@Composable
fun QuickLogScreen(
    key: Route.QuickLog,
    onBack: () -> Unit,
    vm: QuickLogViewModel = hiltViewModel<QuickLogViewModel, QuickLogViewModel.Factory>(key = key.toString()) { it.create(key) },
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val meal by vm.meal.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    val pickers = rememberPhotoPickers(vm::attach)
    val list = rememberLazyListState()
    var launched by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (key.camera && !launched) { launched = true; pickers.camera() } }
    LaunchedEffect(items.size, run.busy) { if (items.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log with AI") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = { if (items.isNotEmpty() && !run.busy) TextButton(onClick = onBack) { Text("Done") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            MealChips(meal, { vm.meal.value = it }, Modifier.padding(horizontal = 16.dp))
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!hasKey) item { ApiKeyCard(vm::saveKey) }
                if (items.isEmpty() && hasKey && !run.busy) {
                    item {
                        EmptyState(MaterialShapes.Clover4Leaf, Icons.Rounded.AutoAwesome, "What did you eat?",
                            "Snap your plate or a label, or just type it — “a bowl of oats with banana and a spoon of honey”. I'll log it, and you can undo.")
                    }
                }
                items(items, key = { it.key }) { item ->
                    when (item) {
                        is ChatItem.User -> UserBubble(item.m.content, item.m.images?.split('\n')?.filter { it.isNotBlank() }.orEmpty())
                        is ChatItem.Assistant -> AssistantBubble(item.m.content)
                        is ChatItem.Tools -> ToolChips(item.names)
                        is ChatItem.Action -> ActionCard(item.a, { vm.undo(item.a.id) }, {}, {})
                    }
                }
                item { RunIndicator(run) }
                run.error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
            }
            Row(Modifier.navigationBarsPadding()) {
                ChatInput(
                    text, { text = it }, attachments, vm::removeAttachment, run, pickers,
                    onSend = { vm.send(text); text = "" }, onStop = vm::stop,
                    placeholder = "Describe your food", modifier = Modifier.padding(start = 4.dp, end = 12.dp, bottom = 8.dp),
                )
            }
        }
    }
}
