package com.rk.color

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

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
