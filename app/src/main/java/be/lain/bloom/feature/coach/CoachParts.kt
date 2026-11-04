package be.lain.bloom.feature.coach

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import be.lain.bloom.core.designsystem.BloomCard
import be.lain.bloom.core.designsystem.MarkdownText
import be.lain.bloom.data.ai.RunState
import be.lain.bloom.data.db.AiActionEntity
import be.lain.bloom.data.db.AiActionKind
import be.lain.bloom.data.db.AiActionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun rememberThumbnail(path: String, size: Int = 512): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= size) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bmp
}

@Composable
fun Thumb(path: String, size: Int, modifier: Modifier = Modifier) {
    val img = rememberThumbnail(path)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier.size(size.dp)) {
        if (img != null) Image(img, "Attached photo", contentScale = ContentScale.Crop, modifier = Modifier.clip(MaterialTheme.shapes.large))
    }
}

@Composable
fun UserBubble(text: String, images: List<String>) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (images.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { images.forEach { Thumb(it, 120) } }
            Spacer(Modifier.height(6.dp))
        }
        if (text.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
fun AssistantBubble(text: String) {
    MarkdownText(text, Modifier.fillMaxWidth().padding(end = 24.dp))
}

@Composable
fun ToolChips(names: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        names.distinct().forEach { n ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(toolLabel(n), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

private fun toolLabel(n: String) = when (n) {
    "search_foods" -> "🔎 searched foods"
    "lookup_barcode" -> "🏷 barcode"
    "web_search" -> "🌐 web search"
    "fetch_url" -> "📄 read a page"
    "get_diary" -> "📔 read diary"
    "get_progress" -> "📈 checked progress"
    "get_training" -> "🏃 checked training"
    "log_food" -> "✅ logged food"
    "create_food" -> "➕ saved food"
    "update_entry" -> "✏️ edited entry"
    "delete_entry" -> "🗑 removed entry"
    "log_weight" -> "⚖️ logged weight"
    "propose_plan_change", "propose_training_change" -> "💡 suggestion"
    else -> n
}

@Composable
fun ActionCard(a: AiActionEntity, onUndo: () -> Unit, onAccept: () -> Unit, onDecline: () -> Unit, onOpen: (() -> Unit)? = null) {
    val pending = a.status == AiActionStatus.PENDING
    val color = when {
        pending -> MaterialTheme.colorScheme.tertiaryContainer
        a.status == AiActionStatus.UNDONE || a.status == AiActionStatus.DECLINED -> MaterialTheme.colorScheme.surfaceContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    BloomCard(Modifier.fillMaxWidth(), color = color, contentPadding = 14.dp, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (a.kind) {
                        AiActionKind.PLAN_PROPOSAL -> "Suggested new targets"
                        AiActionKind.TRAINING_PROPOSAL -> "Training suggestion"
                        else -> a.summary
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (a.kind == AiActionKind.PLAN_PROPOSAL || a.kind == AiActionKind.TRAINING_PROPOSAL) {
                    Text(a.summary, style = MaterialTheme.typography.bodyMedium)
                    planText(a.payload)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                }
                when (a.status) {
                    AiActionStatus.UNDONE -> Text("Undone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AiActionStatus.ACCEPTED -> Text("Accepted ✓", style = MaterialTheme.typography.labelMedium)
                    AiActionStatus.DECLINED -> Text("Declined", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Unit
                }
            }
            if (a.status == AiActionStatus.APPLIED && a.kind != AiActionKind.PLAN_PROPOSAL) {
                TextButton(onClick = onUndo) { Text("Undo") }
            }
        }
        if (pending) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(6.dp)); Text("Accept") }
                OutlinedButton(onClick = onDecline) { Text("Decline") }
            }
        }
    }
}

private fun planText(payload: String): String? = runCatching {
    val t = kotlinx.serialization.json.Json.parseToJsonElement(payload).let { it as kotlinx.serialization.json.JsonObject }["targets"]
        as? kotlinx.serialization.json.JsonObject ?: return null
    fun v(k: String) = t[k]?.toString()?.trim('"')?.toDoubleOrNull()?.toInt() ?: 0
    "${v("kcal")} kcal · P ${v("protein")} · C ${v("carbs")} · F ${v("fat")} g"
}.getOrNull()

@Composable
fun RunIndicator(run: RunState) {
    if (!run.busy) return
    Column {
        if (run.streaming.isNotBlank()) AssistantBubble(run.streaming)
        if (run.activity != null || run.streaming.isBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(Modifier.size(36.dp))
                Spacer(Modifier.width(8.dp))
                Text(run.activity ?: "Thinking…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Camera + gallery launchers that deliver content URIs. */
class PhotoPickers(val camera: () -> Unit, val gallery: () -> Unit)

@Composable
fun rememberPhotoPickers(onPicked: (List<Uri>) -> Unit): PhotoPickers {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Uri?>(null) }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> pending?.let { if (ok) onPicked(listOf(it)) } }
    val gal = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris -> if (uris.isNotEmpty()) onPicked(uris) }
    return remember {
        PhotoPickers(
            camera = { cameraUri(context).also { pending = it }.let(cam::launch) },
            gallery = { gal.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
    }
}

fun cameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "photo_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

@Composable
fun ChatInput(
    text: String,
    onText: (String) -> Unit,
    attachments: List<String>,
    onRemoveAttachment: (String) -> Unit,
    run: RunState,
    pickers: PhotoPickers,
    onSend: () -> Unit,
    onStop: () -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (attachments.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                items(attachments) { p ->
                    Box {
                        Thumb(p, 72)
                        Surface(onClick = { onRemoveAttachment(p) }, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.inverseSurface,
                            modifier = Modifier.align(Alignment.TopEnd).size(24.dp)) {
                            Icon(Icons.Rounded.Close, "Remove photo", tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(3.dp))
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = pickers.camera) { Icon(Icons.Rounded.AddAPhoto, "Take a photo") }
            IconButton(onClick = pickers.gallery) { Icon(Icons.Rounded.PhotoLibrary, "Choose photos") }
            TextField(
                value = text, onValueChange = onText, placeholder = { Text(placeholder) }, maxLines = 5,
                shape = MaterialTheme.shapes.extraLarge,
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            if (run.busy) {
                FilledIconButton(onClick = onStop, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.Stop, "Stop") }
            } else {
                FilledIconButton(onClick = onSend, enabled = text.isNotBlank() || attachments.isNotEmpty(), modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.Send, "Send")
                }
            }
        }
    }
}

@Composable
fun ApiKeyCard(onSave: (String) -> Unit) {
    var key by remember { mutableStateOf("") }
    val uri = LocalUriHandler.current
    BloomCard(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text("Connect your AI coach", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "Bloom's coach uses DeepSeek with your own API key. It's stored encrypted on this phone. " +
                "Messages, photos and the relevant diary data you share are sent to DeepSeek when you use the coach.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(key, { key = it.trim() }, label = { Text("DeepSeek API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { uri.openUri("https://platform.deepseek.com/api_keys") }) { Text("Get a key") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { onSave(key) }, enabled = key.length > 10) { Text("Save key") }
        }
    }
}

@Composable
fun SuggestionChips(items: List<String>, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { s -> AssistChip(onClick = { onPick(s) }, label = { Text(s) }) }
    }
}
