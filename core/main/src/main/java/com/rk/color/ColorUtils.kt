package com.rk.color

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

fun String.parseColor(): Color? {
    return runCatching { Color(android.graphics.Color.parseColor(this)) }.getOrNull()
}

fun Color.toHex(): String {
    val a = (alpha * 255).roundToInt()
    val r = (red * 255).roundToInt()
    val g = (green * 255).roundToInt()
    val b = (blue * 255).roundToInt()

    return if (a == 255) {
        String.format("#%02X%02X%02X", r, g, b)
    } else {
        String.format("#%02X%02X%02X%02X", r, g, b, a)
    }
}

fun String.parseRgb(): Color? {
    val match = Regex("rgba?\\(\\s*([\\d.]+)\\s*,\\s*([\\d.]+)\\s*,\\s*([\\d.]+)\\s*(?:,\\s*([\\d.]+)\\s*)?\\)").find(trim())
        ?: return null
    val r = match.groupValues[1].toFloatOrNull() ?: return null
    val g = match.groupValues[2].toFloatOrNull() ?: return null
    val b = match.groupValues[3].toFloatOrNull() ?: return null
    val a = match.groupValues[4].takeIf { it.isNotEmpty() }?.toFloatOrNull() ?: 1f
    return Color(r, g, b, a)
}
