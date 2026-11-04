package be.lain.bloom.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.lain.bloom.BuildConfig
import be.lain.bloom.core.designsystem.MarkdownText
import be.lain.bloom.data.update.UpdateState
import be.lain.bloom.data.update.Updater

/** Offers a newer release from GitHub and shows download / install progress. */
@Composable
fun UpdateDialog(updater: Updater) {
    val state by updater.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Bloom ${s.release.version} is available") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("You have ${BuildConfig.VERSION_NAME}. Your data stays as it is.", style = MaterialTheme.typography.bodyMedium)
                    if (s.release.notes.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        MarkdownText(s.release.notes.take(2000))
                    }
                }
            },
            confirmButton = { Button(onClick = { updater.download(s.release) }) { Text("Update") } },
            dismissButton = {
                Column {
                    TextButton(onClick = updater::dismiss) { Text("Later") }
                    TextButton(onClick = { updater.skip(s.release) }) { Text("Skip this version") }
                }
            },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Downloading ${s.release.version}") },
            text = {
                Column {
                    LinearWavyProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(s.progress * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { },
        )
        is UpdateState.Installing -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Installing ${s.release.version}") },
            text = { Text("Confirm the update in Android's installer. Bloom restarts when it's done.") },
            confirmButton = { TextButton(onClick = updater::dismiss) { Text("Hide") } },
        )
        is UpdateState.Failed -> if (s.release != null) AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Update not installed") },
            text = { Text(s.message) },
            confirmButton = { Button(onClick = { updater.download(s.release) }) { Text("Try again") } },
            dismissButton = { TextButton(onClick = updater::dismiss) { Text("Close") } },
        )
        else -> Unit
    }
}
