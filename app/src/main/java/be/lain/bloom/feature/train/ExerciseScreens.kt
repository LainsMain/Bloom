package be.lain.bloom.feature.train

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.AppMessages
import be.lain.bloom.core.designsystem.BloomCard
import be.lain.bloom.core.designsystem.ExerciseVideo
import be.lain.bloom.core.designsystem.parseYouTubeId
import be.lain.bloom.data.training.TrainingRepository
import be.lain.bloom.domain.training.ExerciseKind
import be.lain.bloom.domain.training.Exercises
import be.lain.bloom.feature.food.FoodRow
import be.lain.bloom.feature.food.SectionLabel
import be.lain.bloom.ui.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

@HiltViewModel
class ExerciseViewModel @Inject constructor(
    private val repo: TrainingRepository,
    private val http: OkHttpClient,
    private val messages: AppMessages,
) : ViewModel() {
    val videos = repo.videos.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Verifies the video exists and allows embedding (YouTube oEmbed), then saves it. */
    fun setVideo(exerciseId: String, input: String, onResult: (String?) -> Unit) = viewModelScope.launch {
        val id = parseYouTubeId(input) ?: return@launch onResult("That doesn't look like a YouTube link")
        val title = withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://www.youtube.com/oembed".toHttpUrl().newBuilder()
                    .addQueryParameter("format", "json").addQueryParameter("url", "https://www.youtube.com/watch?v=$id").build()
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) null
                    else Json.parseToJsonElement(r.body!!.string()).jsonObject["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                }
            }.getOrNull()
        }
        if (title == null) return@launch onResult("That video can't be embedded (or you're offline)")
        repo.setVideo(exerciseId, id, title)
        messages.show("Video updated")
        onResult(null)
    }

    fun resetVideo(exerciseId: String) = viewModelScope.launch { repo.resetVideo(exerciseId); messages.show("Back to the default video") }
}

@Composable
fun ExerciseLibraryScreen(onBack: () -> Unit, navigate: (Route) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Exercise library") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(8.dp, 0.dp, 8.dp, 32.dp)) {
            ExerciseKind.entries.forEach { kind ->
                item(key = kind.name) {
                    SectionLabel(when (kind) { ExerciseKind.WARMUP -> "Warm-up"; ExerciseKind.STRENGTH -> "Strength"; ExerciseKind.CORE -> "Core"; ExerciseKind.STRETCH -> "Stretches" })
                }
                items(Exercises.all.filter { it.kind == kind }, key = { it.id }) { e ->
                    FoodRow(e.name, e.muscles + (e.equipment?.let { " · $it" } ?: ""), onClick = { navigate(Route.ExerciseDetail(e.id)) })
                }
            }
        }
    }
}

@Composable
fun ExerciseDetailScreen(key: Route.ExerciseDetail, onBack: () -> Unit, navigate: (Route) -> Unit, vm: ExerciseViewModel = hiltViewModel()) {
    val ex = Exercises[key.id]
    val videos by vm.videos.collectAsStateWithLifecycle()
    val video = videos[ex.id]
    var changing by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current
    Scaffold(
        topBar = { TopAppBar(title = { Text(ex.name) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExerciseVideo(video?.id, muted = false)
            Row {
                Text(
                    video?.let { (if (it.custom) "Your video" else "Demo") + (it.title.takeIf { t -> t.isNotBlank() }?.let { t -> ": $t" } ?: "") + (it.channel.takeIf { c -> c.isNotBlank() }?.let { c -> " — $c" } ?: "") } ?: "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { changing = true }, label = { Text("Change video") })
                if (video?.custom == true) AssistChip(onClick = { vm.resetVideo(ex.id) }, label = { Text("Use default") })
                AssistChip(onClick = { uri.openUri("https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode("${ex.name} exercise how to", "UTF-8")) },
                    label = { Text("Find on YouTube") })
            }
            BloomCard(Modifier.fillMaxWidth()) {
                Text("Works", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ex.muscles, style = MaterialTheme.typography.titleMedium)
                ex.equipment?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("You'll need", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.titleMedium)
                }
            }
            Text("How to", style = MaterialTheme.typography.titleLarge)
            ex.cues.forEachIndexed { i, c -> Text("${i + 1}. $c", style = MaterialTheme.typography.bodyLarge) }
            if (ex.mistakes.isNotEmpty()) {
                Text("Watch out for", style = MaterialTheme.typography.titleLarge)
                ex.mistakes.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
            }
            val easier = Exercises.easier(ex.id)
            val harder = Exercises.harder(ex.id)
            if (easier != null || harder != null) {
                Text("Variations", style = MaterialTheme.typography.titleLarge)
                easier?.let { FoodRow("Easier: ${Exercises[it].name}", Exercises[it].muscles, onClick = { navigate(Route.ExerciseDetail(it)) }) }
                harder?.let { FoodRow("Harder: ${Exercises[it].name}", Exercises[it].muscles, onClick = { navigate(Route.ExerciseDetail(it)) }) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (changing) {
        var link by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { changing = false },
            title = { Text("Use a different video") },
            text = {
                Column {
                    Text("Paste a YouTube link (Share → Copy link in the YouTube app).", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(link, { link = it; error = null }, singleLine = true, label = { Text("YouTube link") }, isError = error != null,
                        supportingText = error?.let { { Text(it) } })
                }
            },
            confirmButton = { TextButton(onClick = { vm.setVideo(ex.id, link) { e -> if (e == null) changing = false else error = e } }, enabled = link.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { changing = false }) { Text("Cancel") } },
        )
    }
}
