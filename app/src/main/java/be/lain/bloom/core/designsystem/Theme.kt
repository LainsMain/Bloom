package be.lain.bloom.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import be.lain.bloom.R
import be.lain.bloom.data.AppSettings
import be.lain.bloom.data.ThemeMode

/**
 * Data colours (validated for colour-blind separation on light and dark card surfaces).
 * Text never uses these; they only colour marks next to a text label.
 */
@Immutable
data class DataColors(
    val protein: Color,
    val carbs: Color,
    val fat: Color,
    val fiber: Color,
    val water: Color,
    val good: Color,
    val warning: Color,
)

private val LightData = DataColors(
    protein = Color(0xFFD55181), carbs = Color(0xFFEDA100), fat = Color(0xFF1BAF7A), fiber = Color(0xFF6250D6),
    water = Color(0xFF2A78D6), good = Color(0xFF0C8A3E), warning = Color(0xFFB25E00),
)
private val DarkData = DataColors(
    protein = Color(0xFFD55181), carbs = Color(0xFFC98500), fat = Color(0xFF199E70), fiber = Color(0xFF9085E9),
    water = Color(0xFF3987E5), good = Color(0xFF3FB86B), warning = Color(0xFFE08A2C),
)

val LocalDataColors = staticCompositionLocalOf { LightData }

object Bloom {
    val data: DataColors
        @Composable @ReadOnlyComposable get() = LocalDataColors.current
}

private val FlexFamily = FontFamily(
    Font(R.font.robotoflex, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.robotoflex, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.robotoflex, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.robotoflex, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.robotoflex, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
    Font(R.font.robotoflex, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
)

private fun TextStyle.flex(weight: FontWeight) = copy(fontFamily = FlexFamily, fontWeight = weight)

private val BloomTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.flex(FontWeight.ExtraBold),
        displayMedium = t.displayMedium.flex(FontWeight.ExtraBold),
        displaySmall = t.displaySmall.flex(FontWeight.Bold),
        headlineLarge = t.headlineLarge.flex(FontWeight.Bold),
        headlineMedium = t.headlineMedium.flex(FontWeight.Bold),
        headlineSmall = t.headlineSmall.flex(FontWeight.SemiBold),
        titleLarge = t.titleLarge.flex(FontWeight.SemiBold),
        titleMedium = t.titleMedium.flex(FontWeight.SemiBold),
        labelLarge = t.labelLarge.flex(FontWeight.SemiBold),
    )
}

@Composable
fun isDark(settings: AppSettings): Boolean = when (settings.themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun BloomTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = isDark(settings)
    val context = LocalContext.current
    val scheme = if (settings.dynamicColor) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        paletteScheme(settings.palette, dark)
    }
    CompositionLocalProvider(LocalDataColors provides if (dark) DarkData else LightData) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            typography = BloomTypography,
            content = content,
        )
    }
}

/** Convenience for previews and tests. */
@Composable
fun BloomPreviewTheme(dark: Boolean = false, content: @Composable () -> Unit) =
    BloomTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false), content)

val MaterialTheme.dataColors: DataColors
    @Composable @ReadOnlyComposable get() = LocalDataColors.current
