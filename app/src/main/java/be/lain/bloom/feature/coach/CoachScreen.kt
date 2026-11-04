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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
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
import kotlinx.coroutines.flow.map
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

/** Local day a chat row belongs to (for day separators). */
fun ChatItem.date(zone: ZoneId): LocalDate? = when (this) {
    is ChatItem.User -> m.createdAt.atZone(zone).toLocalDate()
    is ChatItem.Assistant -> m.createdAt.atZone(zone).toLocalDate()
    is ChatItem.Action -> a.createdAt.atZone(zone).toLocalDate()
    is ChatItem.Tools -> null
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
    fun retry() { chatId.value?.let { engine.retry(it) } }

    val title = chatId.flatMapLatest { id -> if (id == null) flowOf(null) else db.chatDao().observeChats(ChatMode.COACH.kind).map { l -> l.firstOrNull { it.id == id }?.title } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
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
    val title by vm.title.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var history by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val pickers = rememberPhotoPickers(vm::attach)
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()

    val imeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    LaunchedEffect(items.size, run.streaming.length / 80, run.busy, imeVisible) {
        val count = list.layoutInfo.totalItemsCount
        if (count > 0) list.animateScrollToItem(count - 1)
    }
    val atBottom by remember { androidx.compose.runtime.derivedStateOf { !list.canScrollForward } }

    // The last assistant message of the conversation gets the copy button.
    val lastAssistant = items.lastOrNull { it is ChatItem.Assistant }?.key

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(MaterialShapes.Sunny, Icons.Rounded.AutoAwesome, size = 40.dp)
            Spacer(Modifier.padding(6.dp))
            Column(Modifier.weight(1f)) {
                Text("Coach", style = MaterialTheme.typography.headlineMedium)
                title?.takeIf { it != "New chat" && items.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = { history = true }) { Icon(Icons.Rounded.History, "Chat history") }
            IconButton(onClick = vm::newChat) { Icon(Icons.Rounded.EditNote, "New chat") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!hasKey) item { ApiKeyCard(vm::saveKey) }
                if (items.isEmpty() && hasKey) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            ShapeIcon(MaterialShapes.Sunny, Icons.Rounded.AutoAwesome, size = 72.dp)
                            Spacer(Modifier.height(12.dp))
                            Text("What can I help with?", style = MaterialTheme.typography.headlineSmall)
                            Text("Tell me what you ate, send a photo, or ask about your progress.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    item {
                        StarterCard("Log", listOf(
                            "I had skyr with blueberries and oats for breakfast",
                            "Save my usual breakfast as a recipe",
                            "I walked for 30 minutes this afternoon",
                        )) { text = it }
                    }
                    item {
                        StarterCard("Ask", listOf(
                            "How has my protein been this month?",
                            "Which exercises should I make harder next workout?",
                            "Why did my weight go up this week?",
                        )) { text = it }
                    }
                }
                items.forEachIndexed { i, item ->
                    val day = item.date(zone)
                    val prevDay = items.getOrNull(i - 1)?.date(zone)
                    if (day != null && day != prevDay) {
                        item(key = "day-$day-${item.key}") { DaySeparator(day.relative(today)) }
                    }
                    // A "Coach" label where a reply starts (after the user's message).
                    val startsReply = item !is ChatItem.User && (i == 0 || items[i - 1] is ChatItem.User)
                    item(key = item.key) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (startsReply) CoachLabel()
                            when (item) {
                                is ChatItem.User -> {
                                    val imgs = item.m.images?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
                                    if (isAppNote(item.m.content)) AppNote(item.m.content, imgs) { viewing = it }
                                    else UserBubble(item.m.content, imgs) { viewing = it }
                                }
                                is ChatItem.Assistant -> AssistantBubble(item.m.content, showActions = item.key == lastAssistant && !run.busy)
                                is ChatItem.Tools -> ToolChips(item.names)
                                is ChatItem.Action -> ActionCard(item.a, { vm.undo(item.a.id) }, { vm.accept(item.a.id) }, { vm.decline(item.a.id) })
                            }
                        }
                    }
                }
                item { RunIndicator(run) }
                run.error?.let { e -> item { ErrorCard(e, vm::retry) } }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = !atBottom && items.isNotEmpty(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
            ) {
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = { scope.launch { list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) } },
                ) { Icon(Icons.Rounded.KeyboardArrowDown, "Jump to latest") }
            }
        }
        ChatInput(
            text = text, onText = { text = it }, attachments = attachments, onRemoveAttachment = vm::removeAttachment,
            run = run, pickers = pickers, onSend = { vm.send(text); text = "" }, onStop = vm::stop,
            placeholder = "Message your coach", modifier = Modifier.padding(start = 4.dp, end = 12.dp, bottom = 8.dp),
        )
    }

    viewing?.let { PhotoViewer(it) { viewing = null } }

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
