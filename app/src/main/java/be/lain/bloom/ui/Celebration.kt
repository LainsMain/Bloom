package be.lain.bloom.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.toPath
import be.lain.bloom.core.designsystem.dataColors
import be.lain.bloom.domain.Milestone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

private class Particle(
    val shape: Int, val color: Color, val size: Float,
    val angle: Float, val speed: Float, val spin: Float, val rot0: Float, val delay: Float, val wobble: Float,
)

/** Full-screen celebration for a weight-loss milestone: confetti burst + a springy card. */
@Composable
fun Celebration(m: Milestone, onDone: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val data = MaterialTheme.dataColors
    val palette = listOf(scheme.primary, scheme.tertiary, data.protein, data.carbs, data.fat, data.fiber, data.water)
    val polygons = remember {
        listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Flower, MaterialShapes.Sunny, MaterialShapes.Heart, MaterialShapes.Clover4Leaf, MaterialShapes.Circle)
            .map { it.toPath().asComposePath() }
    }
    val count = if (m.big) 110 else 55
    val particles = remember(m.id) {
        val r = Random(m.id.hashCode())
        List(count) {
            Particle(
                shape = r.nextInt(polygons.size), color = palette[r.nextInt(palette.size)], size = 9f + r.nextFloat() * (if (m.big) 16f else 11f),
                angle = (-PI / 2 + (r.nextFloat() - 0.5f) * 1.9f).toFloat(), speed = 0.9f + r.nextFloat() * 0.9f,
                spin = (r.nextFloat() - 0.5f) * 720f, rot0 = r.nextFloat() * 360f, delay = r.nextFloat() * 0.25f, wobble = r.nextFloat() * 6f,
            )
        }
    }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(m.id) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        val start = withFrameNanos { it }
        while (t < 5.5f) withFrameNanos { now -> t = (now - start) / 1e9f }
    }
    val pop = remember(m.id) { Animatable(0.4f) }
    LaunchedEffect(m.id) { pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow)) }
    val scrim = remember(m.id) { Animatable(0f) }
    LaunchedEffect(m.id) { scrim.animateTo(1f, tween(250)) }
    val spinShape by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "spin")

    BackHandler(onBack = onDone)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f * scrim.value))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDone),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = scheme.surfaceContainerHigh,
            modifier = Modifier.padding(32.dp).widthIn(max = 360.dp).scale(pop.value).alpha(scrim.value),
        ) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (m.big) 150.dp else 120.dp).rotate(spinShape).background(scheme.primaryContainer, MaterialShapes.Cookie12Sided.toShape()))
                    Text(m.hero, style = if (m.big) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium,
                        color = scheme.onPrimaryContainer, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(20.dp))
                Text(m.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(m.message, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(if (m.big) "Yes! 🎉" else "Keep going") }
            }
        }
        // Confetti, drawn over the card (it takes no touches): a burst up and out from just above the card, slowed by air drag, then a slow fluttering fall.
        // Linear drag keeps every piece on screen: it travels at most v/k, then sinks at the terminal speed.
        Canvas(Modifier.fillMaxSize()) {
            val origin = Offset(size.width / 2, size.height * 0.42f)
            val k = 2.4f
            val fall = size.height * 0.2f
            particles.forEach { p ->
                val tt = (t - p.delay).coerceAtLeast(0f)
                if (tt <= 0f) return@forEach
                val v = size.height * 0.62f * p.speed
                val decay = (1f - exp(-k * tt)) / k
                val flutter = sin(tt * 4f + p.wobble) * 10.dp.toPx() * tt.coerceAtMost(1f)
                val x = origin.x + cos(p.angle) * v * decay + flutter
                val y = origin.y + fall * tt + (sin(p.angle) * v - fall) * decay
                val fade = (1f - ((tt - 3.8f) / 1.4f).coerceIn(0f, 1f))
                if (fade <= 0f || y > size.height + 40f) return@forEach
                val path = polygons[p.shape]
                val px = p.size.dp.toPx()
                translate(x, y) {
                    rotate(p.rot0 + p.spin * tt, pivot = Offset.Zero) {
                        scale(px, px, pivot = Offset.Zero) {
                            translate(-0.5f, -0.5f) { drawPath(path, p.color.copy(alpha = fade)) }
                        }
                    }
                }
            }
        }
    }
}
