package be.lain.bloom.core.figure

import android.os.SystemClock
import android.view.Choreographer
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import be.lain.bloom.domain.motion.GuideFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the figure shows at a moment: an exercise and where in its movement it is. */
data class FigureFrame(val motionId: String, val frame: GuideFrame)

/** Free-running demo of an exercise, looping at its tempo. */
fun demoFrames(motionId: String, mirrored: Boolean = false): (Long) -> FigureFrame {
    val start = SystemClock.elapsedRealtime()
    return { now -> FigureFrame(motionId, GuideFrame((now - start) / 1000.0, mirrored)) }
}

/**
 * The 3-D exercise figure. [frameAt] is asked every display frame (with the elapsedRealtime clock
 * the workout engine uses), so the movement stays in step with the voice. Drag to look around;
 * double-tap to reset the view.
 */
@Composable
fun ExerciseFigure(frameAt: (nowMs: Long) -> FigureFrame?, modifier: Modifier = Modifier, label: String = "Exercise demonstration") {
    val context = LocalContext.current
    val model by produceState<FigureModel?>(null) { value = withContext(Dispatchers.Default) { FigureModel.get(context).also { it.library.all } } }
    val currentFrame by rememberUpdatedState(frameAt)
    var orbit by remember { mutableDoubleStateOf(0.0) }
    var ready by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    Box(
        modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .semantics { contentDescription = label }
            .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); orbit = (orbit + drag.x * 0.35).coerceIn(-170.0, 170.0) } }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { orbit = 0.0 }) },
        contentAlignment = Alignment.Center,
    ) {
        val m = model
        if (m != null) {
            var textureView by remember { mutableStateOf<TextureView?>(null) }
            AndroidView(factory = { TextureView(it).apply { isOpaque = false }.also { v -> textureView = v } }, modifier = Modifier.fillMaxSize())
            val tv = textureView
            if (tv != null) DisposableEffect(tv, m) {
                val scene = FigureScene(context, tv, m)
                val choreographer = Choreographer.getInstance()
                var running = false
                val callback = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        if (!running) return
                        choreographer.postFrameCallback(this)
                        val f = currentFrame(SystemClock.elapsedRealtime()) ?: return
                        val motion = m.motion(f.motionId) ?: return
                        val pose = m.solver.solve(motion.pose(f.frame))
                        scene.render(frameTimeNanos, motion, pose, f.frame.mirrored, orbit)
                        if (!ready) ready = true
                    }
                }
                fun start() { if (!running) { running = true; choreographer.postFrameCallback(callback) } }
                fun stop() { running = false; choreographer.removeFrameCallback(callback) }
                val observer = LifecycleEventObserver { _, e ->
                    when (e) { Lifecycle.Event.ON_RESUME -> start(); Lifecycle.Event.ON_PAUSE -> stop(); else -> Unit }
                }
                lifecycle.addObserver(observer)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
                onDispose {
                    stop()
                    lifecycle.removeObserver(observer)
                    scene.destroy()
                }
            }
        }
        if (!ready) LoadingIndicator()
    }
}
