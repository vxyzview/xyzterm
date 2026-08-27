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
import androidx.compose.runtime.remember
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

/** Sentinel theme id for the device's wallpaper-derived dynamic (Material You) colors. Always on. */
const val DYNAMIC_THEME_ID = "dynamic"

val LocalThemeHolder = staticCompositionLocalOf<ThemeHolder> { error("No ThemeHolder state provided") }

@Composable
fun XedTheme(
    darkTheme: Boolean = isDarkTheme(LocalContext.current),
    highContrastDarkTheme: Boolean = Settings.amoled,
    dynamicColor: Boolean = Settings.theme == DYNAMIC_THEME_ID,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val darkThemeState = remember(darkTheme) { darkTheme }
    val amoledState = remember(highContrastDarkTheme) { highContrastDarkTheme }
    val dynamicState = remember(dynamicColor) { dynamicColor }

    val (colorScheme, themeHolder) = remember(darkThemeState, amoledState, dynamicState) {
        var holder: ThemeHolder
        val scheme =
            if (dynamicState && supportsDynamicTheming()) {
                holder = blueberry
                when {
                    darkThemeState && amoledState -> dynamicDarkColorScheme(context).amoledScheme()
                    darkThemeState -> dynamicDarkColorScheme(context)
                    else -> dynamicLightColorScheme(context)
                }
            } else {
                holder = currentTheme.value
                if (darkThemeState) {
                    if (amoledState) holder.darkScheme.amoledScheme() else holder.darkScheme
                } else {
                    holder.lightScheme
                }
            }
        scheme to holder
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

// ponytail: harmonize() is a pure color conversion but reads context + runs per
// read; cache by (color, isDark) to avoid N× recompute in lists. Single-threaded
// composition so a plain map is enough; switch to LruCache if reads explode.
private val harmonizeCache = HashMap<Long, Int>()
@Composable
private fun harmonized(color: Long, isDark: Boolean): Int {
    val key = (color shl 1) or (if (isDark) 1L else 0L)
    return harmonizeCache.getOrPut(key) {
        val ctx = LocalContext.current
        MaterialColors.harmonizeWithPrimary(ctx, color.toInt())
    }
}

// Custom warning colors
val ColorScheme.warningSurface: Color
    @Composable get() = if (isDarkTheme(LocalContext.current)) Color(harmonized(0xFF633F00, true)) else Color(harmonized(0xFFFFDDB4, false))

val ColorScheme.onWarningSurface: Color
    @Composable get() = if (isDarkTheme(LocalContext.current)) Color(harmonized(0xFFFFDDB4, true)) else Color(harmonized(0xFF633F00, false))

// Status colors
val ColorScheme.greenStatus: Color
    @Composable get() = if (isDarkTheme(LocalContext.current)) Color(harmonized(0xFFA6DA95, true)) else Color(harmonized(0xFF44842E, false))

val ColorScheme.yellowStatus: Color
    @Composable get() = if (isDarkTheme(LocalContext.current)) Color(harmonized(0xFFFFE082, true)) else Color(harmonized(0xFFE6AC00, false))

// Git change colors
val ColorScheme.gitAdded: Color
    @Composable get() = if (isDarkTheme(LocalContext.current)) Color(harmonized(0xFF81C784, true)) else Color(harmonized(0xFF2E7D32, false))

val ColorScheme.gitModified: Color
    @Composable get() = if (isDarkTheme(LocalContext.current)) Color(harmonized(0xFF64B5F6, true)) else Color(harmonized(0xFF1565C0, false))

val ColorScheme.gitDeleted: Color
    get() = this.onSurface.copy(alpha = 0.6f)

val ColorScheme.gitConflicted: Color
    @Composable get() = if (isDarkTheme(LocalContext.current)) Color(harmonized(0xFFE57373, true)) else Color(harmonized(0xFFC62828, false))
