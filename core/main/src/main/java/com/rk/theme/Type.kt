package com.rk.theme

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rk.settings.Settings
import com.rk.utils.DEFAULT_APP_FONT_PATH
import com.rk.utils.FontCache
import com.xyzterm.R

/*
 * Fallback font family if dynamic loading fails
 */
val LegacyOutfitFontFamily = FontFamily(Font(R.font.outfit_regular, FontWeight.Normal, FontStyle.Normal))

private var appFontRefreshKey by mutableIntStateOf(0)

@Composable
fun rememberAppTypography(context: Context): Typography {
    val fontPath = key(appFontRefreshKey) { Settings.app_font_path }
    val font =
        if (fontPath.isNotEmpty()) {
            FontCache.getFont(context, fontPath, Settings.is_app_font_asset)
                ?: FontCache.getFont(context, DEFAULT_APP_FONT_PATH, true)
        } else {
            FontCache.getFont(context, DEFAULT_APP_FONT_PATH, true)
        }
    val family = font?.let { FontFamily(it) } ?: LegacyOutfitFontFamily
    return generateTypography(family)
}

/*
 * Overrides the default typo
 */
val Typography = generateTypography(LegacyOutfitFontFamily)

fun generateTypography(fontFamily: FontFamily): Typography =
    Typography(
        displayLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Bold, fontSize = 57.sp),
        displayMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Bold, fontSize = 45.sp),
        displaySmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 36.sp),
        headlineLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 32.sp),
        headlineMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
        headlineSmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
        titleLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
        titleMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        titleSmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
        bodyLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp),
        bodyMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp),
        bodySmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp),
        labelLarge = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp),
        labelMedium = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp),
        labelSmall = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp),
    )
