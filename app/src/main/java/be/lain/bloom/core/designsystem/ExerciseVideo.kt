package be.lain.bloom.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * Embedded YouTube demo (official IFrame player, privacy-enhanced mode). Loops, starts muted.
 * Shows a friendly placeholder when offline or the video can't play.
 */
@Composable
fun ExerciseVideo(videoId: String?, modifier: Modifier = Modifier, muted: Boolean = true) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var player by remember { mutableStateOf<YouTubePlayer?>(null) }
    var failed by remember(videoId) { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.extraLarge

    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (videoId != null) {
            AndroidView(
                factory = { ctx ->
                    YouTubePlayerView(ctx).apply {
                        enableAutomaticInitialization = false
                        lifecycle.addObserver(this)
                        val options = IFramePlayerOptions.Builder(ctx)
                            .controls(1).rel(0).ivLoadPolicy(3).ccLoadPolicy(0).autoplay(1).mute(if (muted) 1 else 0)
                            .privacyEnhancedMode(true)
                            .build()
                        initialize(object : AbstractYouTubePlayerListener() {
                            override fun onReady(youTubePlayer: YouTubePlayer) {
                                player = youTubePlayer
                            }

                            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                                if (state == PlayerConstants.PlayerState.ENDED) { youTubePlayer.seekTo(0f); youTubePlayer.play() }
                            }

                            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                                failed = true
                            }
                        }, true, options)
                    }
                },
                onRelease = { it.release() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (videoId == null || failed) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.VideocamOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (videoId == null) "No demo video" else "Video unavailable — check your connection, or pick another video",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
    LaunchedEffect(videoId, player) {
        val p = player ?: return@LaunchedEffect
        if (videoId != null) {
            failed = false
            p.loadVideo(videoId, 0f)
            if (muted) p.mute() else p.unMute()
        }
    }
    LaunchedEffect(muted, player) {
        val p = player ?: return@LaunchedEffect
        if (muted) p.mute() else { p.unMute(); p.setVolume(100) }
    }
}

/** Extracts a YouTube video id from a URL (watch, shorts, youtu.be, embed) or a bare id. */
fun parseYouTubeId(input: String): String? {
    val s = input.trim()
    if (Regex("^[A-Za-z0-9_-]{11}$").matches(s)) return s
    val patterns = listOf(
        Regex("[?&]v=([A-Za-z0-9_-]{11})"),
        Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
        Regex("/shorts/([A-Za-z0-9_-]{11})"),
        Regex("/embed/([A-Za-z0-9_-]{11})"),
        Regex("/live/([A-Za-z0-9_-]{11})"),
    )
    return patterns.firstNotNullOfOrNull { it.find(s)?.groupValues?.get(1) }
}
