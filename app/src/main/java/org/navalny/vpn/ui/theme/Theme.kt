package org.navalny.vpn.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.DynamicScheme
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import org.navalny.vpn.core.Palette
import org.navalny.vpn.core.ThemeMode

/**
 * Builds a complete MD3 color scheme from a single seed color using the official Material
 * color algorithms (HCT + dynamic schemes) — the same math Android uses for wallpaper colors.
 */
fun schemeFromSeed(seed: Long, dark: Boolean, vibrant: Boolean): ColorScheme {
    val hct = Hct.fromInt(seed.toInt())
    val scheme: DynamicScheme =
        if (vibrant) SchemeVibrant(listOf(hct), dark, 0.0) else SchemeTonalSpot(listOf(hct), dark, 0.0)
    val c = MaterialDynamicColors()
    fun DynamicColor.color() = Color(getArgb(scheme))
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.primary.color(),
        onPrimary = c.onPrimary.color(),
        primaryContainer = c.primaryContainer.color(),
        onPrimaryContainer = c.onPrimaryContainer.color(),
        inversePrimary = c.inversePrimary.color(),
        secondary = c.secondary.color(),
        onSecondary = c.onSecondary.color(),
        secondaryContainer = c.secondaryContainer.color(),
        onSecondaryContainer = c.onSecondaryContainer.color(),
        tertiary = c.tertiary.color(),
        onTertiary = c.onTertiary.color(),
        tertiaryContainer = c.tertiaryContainer.color(),
        onTertiaryContainer = c.onTertiaryContainer.color(),
        background = c.background.color(),
        onBackground = c.onBackground.color(),
        surface = c.surface.color(),
        onSurface = c.onSurface.color(),
        surfaceVariant = c.surfaceVariant.color(),
        onSurfaceVariant = c.onSurfaceVariant.color(),
        surfaceTint = c.surfaceTint.color(),
        inverseSurface = c.inverseSurface.color(),
        inverseOnSurface = c.inverseOnSurface.color(),
        error = c.error.color(),
        onError = c.onError.color(),
        errorContainer = c.errorContainer.color(),
        onErrorContainer = c.onErrorContainer.color(),
        outline = c.outline.color(),
        outlineVariant = c.outlineVariant.color(),
        scrim = c.scrim.color(),
        surfaceBright = c.surfaceBright.color(),
        surfaceDim = c.surfaceDim.color(),
        surfaceContainer = c.surfaceContainer.color(),
        surfaceContainerHigh = c.surfaceContainerHigh.color(),
        surfaceContainerHighest = c.surfaceContainerHighest.color(),
        surfaceContainerLow = c.surfaceContainerLow.color(),
        surfaceContainerLowest = c.surfaceContainerLowest.color(),
    )
}

/** AMOLED: pure black background (pixels off), very dark containers. */
private fun ColorScheme.amoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0D),
    surfaceContainer = Color(0xFF121214),
    surfaceContainerHigh = Color(0xFF1A1A1D),
    surfaceContainerHighest = Color(0xFF222226),
    surfaceBright = Color(0xFF2A2A2E),
)

@Composable
fun NavalnyTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    palette: Palette = Palette.DYNAMIC,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val context = LocalContext.current
    val target = remember(palette, dark, themeMode, context) {
        val scheme = when {
            palette == Palette.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            palette == Palette.DYNAMIC -> schemeFromSeed(palette.seed, dark, vibrant = false)
            else -> schemeFromSeed(palette.seed, dark, vibrant = true)
        }
        if (themeMode == ThemeMode.AMOLED) scheme.amoled() else scheme
    }
    MaterialExpressiveTheme(
        colorScheme = animatedScheme(target),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

/** Smooth cross-fade of the main colors when the palette / theme is switched. */
@Composable
private fun animatedScheme(target: ColorScheme): ColorScheme {
    val spec: AnimationSpec<Color> = MotionScheme.expressive().slowEffectsSpec()
    @Composable
    fun a(c: Color) = animateColorAsState(c, spec, label = "scheme").value
    return target.copy(
        primary = a(target.primary),
        onPrimary = a(target.onPrimary),
        primaryContainer = a(target.primaryContainer),
        onPrimaryContainer = a(target.onPrimaryContainer),
        secondary = a(target.secondary),
        secondaryContainer = a(target.secondaryContainer),
        onSecondaryContainer = a(target.onSecondaryContainer),
        tertiary = a(target.tertiary),
        background = a(target.background),
        surface = a(target.surface),
        onSurface = a(target.onSurface),
        onSurfaceVariant = a(target.onSurfaceVariant),
        surfaceContainerLow = a(target.surfaceContainerLow),
        surfaceContainer = a(target.surfaceContainer),
        surfaceContainerHigh = a(target.surfaceContainerHigh),
        surfaceContainerHighest = a(target.surfaceContainerHighest),
    )
}
