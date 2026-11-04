package be.lain.bloom.feature.coach

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import be.lain.bloom.core.designsystem.MarkdownText
import be.lain.bloom.core.designsystem.ShapeIcon
import be.lain.bloom.data.ai.RunState

fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Bloom coach", text))
}

/** "[Bloom attached ...]" messages are written by the app, not the user. */
fun isAppNote(text: String) = text.startsWith("[Bloom attached")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserBubble(text: String, images: List<String>, onOpenImage: (String) -> Unit = {}) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalAlignment = Alignment.End) {
        if (images.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                images.forEach { p -> Thumb(p, if (images.size == 1) 180 else 110, Modifier.clip(MaterialTheme.shapes.large).combinedClickable { onOpenImage(p) }) }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (text.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp)).combinedClickable(
                    onClick = {},
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); copyToClipboard(context, text) },
                    onLongClickLabel = "Copy",
                ),
            ) {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    }
}

/** A note from the app itself (e.g. progress photos it attached for the coach). */
@Composable
fun AppNote(text: String, images: List<String>, onOpenImage: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text.trim('[', ']'), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        if (images.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                images.forEach { p -> Thumb(p, 64, Modifier.clip(MaterialTheme.shapes.medium).combinedClickable { onOpenImage(p) }) }
            }
        }
    }
}

/** Small "Coach" header above the first part of each reply. */
@Composable
fun CoachLabel() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ShapeIcon(MaterialShapes.Sunny, Icons.Rounded.AutoAwesome, size = 26.dp)
        Spacer(Modifier.width(8.dp))
        Text("Coach", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssistantBubble(text: String, showActions: Boolean = false) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth()) {
        MarkdownText(
            text,
            Modifier.fillMaxWidth().padding(end = 8.dp).combinedClickable(
                onClick = {},
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); copyToClipboard(context, text) },
                onLongClickLabel = "Copy",
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            ),
        )
        if (showActions) {
            IconButton(onClick = { copyToClipboard(context, text) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.ContentCopy, "Copy reply", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

@Composable
fun ErrorCard(message: String, onRetry: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Try again") }
        }
    }
}

@Composable
fun PhotoViewer(path: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            val img = rememberThumbnail(path, 1600)
            if (img != null) {
                Image(img, "Photo", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(img.width.toFloat() / img.height).clip(MaterialTheme.shapes.large))
            }
            FilledIconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Icon(Icons.Rounded.Close, "Close") }
        }
    }
}

/**
 * Message box: one attach button (camera or gallery), voice input when the box is empty,
 * and send/stop. Grows up to 6 lines.
 */
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
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val canSpeak = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { said ->
            onText(if (text.isBlank()) said.replaceFirstChar { it.uppercase() } else "$text $said")
        }
    }
    Column(modifier) {
        if (attachments.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) {
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
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(52.dp)) { Icon(Icons.Rounded.Add, "Add a photo") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Take a photo") }, leadingIcon = { Icon(Icons.Rounded.AddAPhoto, null) },
                        onClick = { menu = false; pickers.camera() })
                    DropdownMenuItem(text = { Text("Choose photos") }, leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) },
                        onClick = { menu = false; pickers.gallery() })
                }
            }
            TextField(
                value = text, onValueChange = onText, placeholder = { Text(placeholder) }, maxLines = 6,
                shape = MaterialTheme.shapes.extraLarge,
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                trailingIcon = if (canSpeak && text.isBlank()) {
                    {
                        IconButton(onClick = {
                            runCatching { speech.launch(
                                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Tell your coach…"),
                            ) }
                        }) { Icon(Icons.Rounded.Mic, "Speak") }
                    }
                } else null,
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

/** Starter prompts that show what the coach can do. */
@Composable
fun StarterCard(title: String, examples: List<String>, onPick: (String) -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            examples.forEach { e ->
                FilledTonalButton(onClick = { onPick(e) }, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(e, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start)
                }
            }
        }
    }
}
