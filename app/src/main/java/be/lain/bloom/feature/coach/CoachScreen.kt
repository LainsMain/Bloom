package be.lain.bloom.feature.coach

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.AppMessages
import be.lain.bloom.core.designsystem.EmptyState
import be.lain.bloom.core.designsystem.ShapeIcon
import be.lain.bloom.core.util.relative
import be.lain.bloom.data.ai.AiActions
import be.lain.bloom.data.ai.ChatMode
import be.lain.bloom.data.ai.CoachEngine
import be.lain.bloom.data.ai.RunState
import be.lain.bloom.data.ai.SecretStore
import be.lain.bloom.data.db.AiActionEntity
import be.lain.bloom.data.db.BloomDatabase
import be.lain.bloom.data.db.ChatMessageEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** One rendered row in the conversation. */
sealed interface ChatItem {
    val key: String
    data class User(val m: ChatMessageEntity) : ChatItem { override val key = "u${m.id}" }
    data class Assistant(val m: ChatMessageEntity) : ChatItem { override val key = "a${m.id}" }
    data class Tools(val id: Long, val names: List<String>) : ChatItem { override val key = "t$id" }
    data class Action(val a: AiActionEntity) : ChatItem { override val key = "x${a.id}" }
}

fun buildItems(messages: List<ChatMessageEntity>, actions: List<AiActionEntity>): List<ChatItem> {
    val out = ArrayList<ChatItem>()
    val byMessage = actions.groupBy { it.messageId }
    val toolNames = ArrayList<String>()
    var toolGroupId = 0L
    fun flushTools() {
        if (toolNames.isNotEmpty()) { out += ChatItem.Tools(toolGroupId, toolNames.toList()); toolNames.clear() }
    }
    messages.forEach { m ->
        when (m.role) {
            "user" -> { flushTools(); out += ChatItem.User(m) }
            "assistant" -> {
                m.toolCalls?.let { tc ->
                    if (toolNames.isEmpty()) toolGroupId = m.id
                    runCatching { Json.parseToJsonElement(tc).jsonArray.forEach { toolNames += it.jsonObject["name"]!!.jsonPrimitive.content } }
                }
                if (m.content.isNotBlank()) { flushTools(); out += ChatItem.Assistant(m) }
                byMessage[m.id]?.forEach { out += ChatItem.Action(it) }
            }
        }
    }
    flushTools()
    byMessage[null]?.forEach { out += ChatItem.Action(it) }
    return out
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CoachViewModel @Inject constructor(
    private val engine: CoachEngine,
    private val db: BloomDatabase,
    private val actions: AiActions,
    private val secrets: SecretStore,
    private val messages: AppMessages,
) : ViewModel() {
    val chatId = MutableStateFlow<Long?>(null)
    val attachments = MutableStateFlow<List<String>>(emptyList())
    val hasKey = secrets.hasDeepSeekKey.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val chats = db.chatDao().observeChats(ChatMode.COACH.kind).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val items = chatId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList())
        else combine(db.chatDao().observeMessages(id), db.chatDao().observeActions(id), ::buildItems)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val run = combine(engine.runs, chatId) { runs, id -> id?.let { runs[it] } ?: RunState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RunState())

    init {
        viewModelScope.launch { chatId.value = db.chatDao().latest(ChatMode.COACH.kind)?.id }
    }

    fun send(text: String) = viewModelScope.launch {
        val id = chatId.value ?: engine.newChat().also { chatId.value = it }
        engine.send(id, text.trim(), attachments.value)
        attachments.value = emptyList()
    }

    fun stop() { chatId.value?.let(engine::cancel) }
    fun newChat() { chatId.value = null }
    fun open(id: Long) { chatId.value = id }
    fun deleteChat(id: Long) = viewModelScope.launch {
        db.chatDao().deleteMessages(id); db.chatDao().deleteChat(id)
        if (chatId.value == id) chatId.value = null
    }

    fun attach(uris: List<android.net.Uri>) = viewModelScope.launch {
        uris.take(4).forEach { u ->
            runCatching { engine.importImage(u) }.onSuccess { p -> attachments.update { it + p } }
                .onFailure { messages.show("Couldn't load that photo") }
        }
    }
    fun removeAttachment(p: String) = attachments.update { it - p }

    fun undo(id: Long) = viewModelScope.launch { actions.undo(id); messages.show("Undone") }
    fun accept(id: Long) = viewModelScope.launch { actions.accept(id); messages.show("Plan updated") }
    fun decline(id: Long) = viewModelScope.launch { actions.decline(id) }
    fun saveKey(k: String) = viewModelScope.launch { secrets.setDeepSeekKey(k); messages.show("API key saved") }
}

@Composable
fun CoachScreen(vm: CoachViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    val chats by vm.chats.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var history by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val pickers = rememberPhotoPickers(vm::attach)
    val scope = rememberCoroutineScope()

    LaunchedEffect(items.size, run.streaming.length / 80, run.busy) {
        val count = list.layoutInfo.totalItemsCount
        if (count > 0) list.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(MaterialShapes.Sunny, Icons.Rounded.AutoAwesome, size = 40.dp)
            Spacer(Modifier.padding(6.dp))
            Text("Coach", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { history = true }) { Icon(Icons.Rounded.History, "Chat history") }
            IconButton(onClick = vm::newChat) { Icon(Icons.Rounded.EditNote, "New chat") }
        }
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasKey) item { ApiKeyCard(vm::saveKey) }
            if (items.isEmpty() && hasKey) {
                item {
                    EmptyState(MaterialShapes.Sunny, Icons.Rounded.AutoAwesome, "Hi! What can I help with?",
                        "Tell me what you ate (or send a photo) and I'll log it. Ask about your progress, meals, workouts — anything.")
                }
                item {
                    SuggestionChips(
                        listOf(
                            "I had 2 slices of toast with peanut butter and a coffee with milk",
                            "How am I doing this week?",
                            "High-protein dinner ideas under 500 kcal",
                            "What should I eat for the rest of today?",
                        ),
                    ) { text = it }
                }
            }
            items(items, key = { it.key }) { item ->
                when (item) {
                    is ChatItem.User -> UserBubble(item.m.content, item.m.images?.split('\n')?.filter { it.isNotBlank() }.orEmpty())
                    is ChatItem.Assistant -> AssistantBubble(item.m.content)
                    is ChatItem.Tools -> ToolChips(item.names)
                    is ChatItem.Action -> ActionCard(item.a, { vm.undo(item.a.id) }, { vm.accept(item.a.id) }, { vm.decline(item.a.id) })
                }
            }
            item { RunIndicator(run) }
            run.error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) } }
        }
        ChatInput(
            text = text, onText = { text = it }, attachments = attachments, onRemoveAttachment = vm::removeAttachment,
            run = run, pickers = pickers, onSend = { vm.send(text); text = "" }, onStop = vm::stop,
            placeholder = "Message your coach", modifier = Modifier.padding(start = 4.dp, end = 12.dp, bottom = 8.dp),
        )
    }

    if (history) {
        ModalBottomSheet(onDismissRequest = { history = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Chats", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.newChat(); history = false }) { Text("New chat") }
                }
                val today = LocalDate.now()
                chats.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.open(c.id); history = false }.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.updatedAt.atZone(ZoneId.systemDefault()).toLocalDate().relative(today), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { scope.launch { vm.deleteChat(c.id) } }) { Icon(Icons.Rounded.DeleteOutline, "Delete chat") }
                    }
                }
                if (chats.isEmpty()) Text("No chats yet", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
