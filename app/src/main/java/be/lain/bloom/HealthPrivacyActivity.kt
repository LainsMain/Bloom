package be.lain.bloom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import be.lain.bloom.core.designsystem.BloomTheme
import be.lain.bloom.data.AppSettings

/** Shown by Health Connect when the user asks why Bloom wants access. */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BloomTheme(AppSettings()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
                        Text("Bloom and Health Connect", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            """Bloom reads your steps, weigh-ins and workouts recorded by other apps so your weekly review sees the full picture.

It writes the runs and strength workouts you record in Bloom (including the run route), so other health apps can see them too. Optionally it shares your weigh-ins.

Everything stays on your phone. Bloom has no servers and never uploads your health data. Runs recorded by Bloom are never counted twice: overlapping sessions from other apps are hidden, and steps taken during your runs are shown separately.

You can change or revoke access at any time in Health Connect.""",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }
}
