package be.lain.bloom.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import be.lain.bloom.data.Palette
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Builds Material tonal palettes from a CIE LCh seed. Tone == CIELAB L*, which is
 * what Material's HCT tone is based on, so role contrast behaves like the real thing.
 */
internal object Tonal {
    fun color(tone: Double, chroma: Double, hue: Double): Color {
        // Reduce chroma until the colour fits in sRGB.
        var lo = 0.0
        var hi = chroma
        var best = lchToRgb(tone, 0.0, hue)!!
        repeat(18) {
            val mid = (lo + hi) / 2
            val rgb = lchToRgb(tone, mid, hue)
            if (rgb != null) { best = rgb; lo = mid } else hi = mid
        }
        lchToRgb(tone, chroma, hue)?.let { best = it }
        return Color(best[0].toFloat(), best[1].toFloat(), best[2].toFloat())
    }

    private fun lchToRgb(l: Double, c: Double, hDeg: Double): DoubleArray? {
        val h = Math.toRadians(hDeg)
        val a = c * cos(h)
        val b = c * sin(h)
        val fy = (l + 16.0) / 116.0
        val fx = fy + a / 500.0
        val fz = fy - b / 200.0
        fun inv(t: Double) = if (t * t * t > 0.008856) t * t * t else (116.0 * t - 16.0) / 903.3
        val x = 0.95047 * inv(fx)
        val y = if (l > 8.0) fy * fy * fy else l / 903.3
        val z = 1.08883 * inv(fz)
        val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        val eps = 1e-4
        if (listOf(r, g, bl).any { it < -eps || it > 1 + eps }) return null
        fun gamma(v: Double): Double {
            val cv = v.coerceIn(0.0, 1.0)
            return if (cv <= 0.0031308) 12.92 * cv else 1.055 * cv.pow(1 / 2.4) - 0.055
        }
        return doubleArrayOf(gamma(r), gamma(g), gamma(bl))
    }
}

private class Seed(val hue: Double, val primaryChroma: Double, val tertiaryHue: Double)

private fun Palette.seed() = when (this) {
    Palette.ROSE -> Seed(hue = 355.0, primaryChroma = 52.0, tertiaryHue = 45.0)
    Palette.LILAC -> Seed(hue = 305.0, primaryChroma = 48.0, tertiaryHue = 355.0)
    Palette.SAGE -> Seed(hue = 140.0, primaryChroma = 32.0, tertiaryHue = 75.0)
    Palette.PEACH -> Seed(hue = 50.0, primaryChroma = 46.0, tertiaryHue = 340.0)
}

fun paletteScheme(palette: Palette, dark: Boolean): ColorScheme {
    val s = palette.seed()
    fun p(t: Int) = Tonal.color(t.toDouble(), s.primaryChroma, s.hue)
    fun sec(t: Int) = Tonal.color(t.toDouble(), 18.0, s.hue)
    fun ter(t: Int) = Tonal.color(t.toDouble(), 36.0, s.tertiaryHue)
    fun n(t: Int) = Tonal.color(t.toDouble(), 4.0, s.hue)
    fun nv(t: Int) = Tonal.color(t.toDouble(), 9.0, s.hue)
    fun e(t: Int) = Tonal.color(t.toDouble(), 64.0, 35.0)
    return if (!dark) {
        lightColorScheme(
            primary = p(40), onPrimary = p(100), primaryContainer = p(90), onPrimaryContainer = p(30),
            inversePrimary = p(80),
            secondary = sec(40), onSecondary = sec(100), secondaryContainer = sec(90), onSecondaryContainer = sec(30),
            tertiary = ter(40), onTertiary = ter(100), tertiaryContainer = ter(90), onTertiaryContainer = ter(30),
            error = e(40), onError = e(100), errorContainer = e(90), onErrorContainer = e(30),
            background = n(98), onBackground = n(10), surface = n(98), onSurface = n(10),
            surfaceVariant = nv(90), onSurfaceVariant = nv(30), outline = nv(50), outlineVariant = nv(80),
            inverseSurface = n(20), inverseOnSurface = n(95), surfaceTint = p(40), scrim = Color.Black,
            surfaceBright = n(98), surfaceDim = n(87),
            surfaceContainerLowest = n(100), surfaceContainerLow = n(96), surfaceContainer = n(94),
            surfaceContainerHigh = n(92), surfaceContainerHighest = n(90),
        )
    } else {
        darkColorScheme(
            primary = p(80), onPrimary = p(20), primaryContainer = p(30), onPrimaryContainer = p(90),
            inversePrimary = p(40),
            secondary = sec(80), onSecondary = sec(20), secondaryContainer = sec(30), onSecondaryContainer = sec(90),
            tertiary = ter(80), onTertiary = ter(20), tertiaryContainer = ter(30), onTertiaryContainer = ter(90),
            error = e(80), onError = e(20), errorContainer = e(30), onErrorContainer = e(90),
            background = n(6), onBackground = n(90), surface = n(6), onSurface = n(90),
            surfaceVariant = nv(30), onSurfaceVariant = nv(80), outline = nv(60), outlineVariant = nv(30),
            inverseSurface = n(90), inverseOnSurface = n(20), surfaceTint = p(80), scrim = Color.Black,
            surfaceBright = n(24), surfaceDim = n(6),
            surfaceContainerLowest = n(4), surfaceContainerLow = n(10), surfaceContainer = n(12),
            surfaceContainerHigh = n(17), surfaceContainerHighest = n(22),
        )
    }
}

/** Swatch colour for the palette picker. */
fun paletteSwatch(palette: Palette): Color = palette.seed().let { Tonal.color(55.0, it.primaryChroma, it.hue) }
