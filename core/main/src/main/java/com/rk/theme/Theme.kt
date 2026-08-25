package com.rk.theme

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.material.color.MaterialColors
import com.rk.App.Companion.themeManager
import com.rk.settings.Settings
import com.rk.theme.rememberAppTypography
import com.rk.utils.isDarkTheme

val currentTheme = derivedStateOf {
     themeManager.loadedThemes.find { it.id == Settings.theme } ?: blueberry
}

val LocalThemeHolder = staticCompositionLocalOf<ThemeHolder> { error("No ThemeHolder state provided") }

@Composable
fun XedTheme(
    darkTheme: Boolean = isDarkTheme(LocalContext.current),
    highContrastDarkTheme: Boolean = Settings.amoled,
    dynamicColor: Boolean = Settings.monet,
    content: @Composable () -> Unit,
) {
    var themeHolder: ThemeHolder
    val colorScheme =
        if (dynamicColor && supportsDynamicTheming()) {
            val context = LocalContext.current
            val baseColorScheme =
                when {
                    darkTheme && highContrastDarkTheme -> dynamicDarkColorScheme(context).amoledScheme()
                    darkTheme -> dynamicDarkColorScheme(context)
                    else -> dynamicLightColorScheme(context)
                }

            // Use default theme
            themeHolder = blueberry

            baseColorScheme
        } else {
            themeHolder = currentTheme.value

            if (darkTheme) {
                if (highContrastDarkTheme) {
                    themeHolder.darkScheme.amoledScheme()
                } else {
                    themeHolder.darkScheme
                }
            } else {
                themeHolder.lightScheme
            }
        }

    CompositionLocalProvider(LocalThemeHolder provides themeHolder) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = rememberAppTypography(LocalContext.current),
            shapes =
                MaterialTheme.shapes.copy(
                    extraSmall = RoundedCornerShape(12.dp),
                    small = RoundedCornerShape(16.dp),
                    medium = RoundedCornerShape(20.dp),
                    large = RoundedCornerShape(28.dp),
                    extraLarge = RoundedCornerShape(40.dp),
                ),
        ) {
            Surface(color = MaterialTheme.colorScheme.background) { content() }
        }
    }
}

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
fun supportsDynamicTheming() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun harmonize(color: Long): Int {
    val context = LocalContext.current
    return MaterialColors.harmonizeWithPrimary(context, color.toInt())
}

/**
 * AMOLED scheme: collapse the background/surface family to true black and derive the
 * container ramp by darkening each of the BASE scheme's own containers toward black.
 * Each level scales from its ORIGINAL counterpart (copy() args evaluate against the
 * receiver, not sibling params), which preserves the tone spacing and hue so cards
 * stay distinguishable from the background while keeping the pure-black look.
 */
private fun ColorScheme.amoledScheme(): ColorScheme =
    copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceBright = surfaceContainerHighest.towardBlack(0.45f),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = surfaceContainerLow.towardBlack(0.70f),
        surfaceContainer = surfaceContainer.towardBlack(0.65f),
        surfaceContainerHigh = surfaceContainerHigh.towardBlack(0.60f),
        surfaceContainerHighest = surfaceContainerHighest.towardBlack(0.55f),
    )

/** Blend [this] color toward black by [amount] (0f = unchanged, 1f = black). */
private fun Color.towardBlack(amount: Float): Color {
    if (amount <= 0f) return this
    if (amount >= 1f) return Color.Black
    return Color(
        red = red * (1f - amount),
        green = green * (1f - amount),
        blue = blue * (1f - amount),
        alpha = alpha,
    )
}

// Custom warning colors
val ColorScheme.warningSurface: Color
    @Composable get() = if (isDarkTheme(LocalContext.current)) Color(harmonize(0xFF633F00)) else Color(harmonize(0xFFFFDDB4))

val ColorScheme.onWarningSurface: Color
    @Composable get() = if (isDarkTheme(LocalContext.current)) Color(harmonize(0xFFFFDDB4)) else Color(harmonize(0xFF633F00))

// Status colors
val ColorScheme.greenStatus: Color
    @Composable get() = if (isDarkTheme(LocalContext.current)) Color(harmonize(0xFFA6DA95)) else Color(harmonize(0xFF44842E))

val ColorScheme.yellowStatus: Color
    @Composable get() = if (isDarkTheme(LocalContext.current)) Color(harmonize(0xFFFFE082)) else Color(harmonize(0xFFE6AC00))
