package com.xyzterm.theme

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.xyzterm.R
import com.xyzterm.settings.Settings
import com.xyzterm.utils.DEFAULT_APP_FONT_PATH
import com.xyzterm.utils.FontCache

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
    val family = remember(font) { font?.let { FontFamily(it) } ?: LegacyOutfitFontFamily }
    return remember(family) { generateTypography(family) }
}

/*
 * Overrides the default typo
 */
val Typography = generateTypography(LegacyOutfitFontFamily)

fun generateTypography(fontFamily: FontFamily): Typography =
    Typography(
        displayLarge =
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 57.sp,
                lineHeight = 64.sp,
                letterSpacing = (-0.02).sp,
            ),
        displayMedium =
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 45.sp,
                lineHeight = 52.sp,
                letterSpacing = (-0.02).sp,
            ),
        displaySmall =
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 36.sp,
                lineHeight = 44.sp,
                letterSpacing = (-0.01).sp,
            ),
        headlineLarge =
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 32.sp,
                lineHeight = 40.sp,
                letterSpacing = (-0.01).sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                lineHeight = 36.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 24.sp,
                lineHeight = 32.sp,
            ),
        titleLarge =
            TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium =
            TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
        titleSmall =
            TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
        bodyLarge =
            TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium =
            TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall =
            TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge =
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.01.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.01.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.01.sp,
            ),
    )
