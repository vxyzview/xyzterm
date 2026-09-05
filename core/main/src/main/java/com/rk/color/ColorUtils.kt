package com.rk.color

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
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

fun String.parseHex(): Color? {
    val hex = trim().removePrefix("#")
    if (hex.isEmpty()) return null
    val argb =
        when (hex.length) {
            3 -> {
                val r = hex[0].toString().repeat(2).toInt(16)
                val g = hex[1].toString().repeat(2).toInt(16)
                val b = hex[2].toString().repeat(2).toInt(16)
                (0xFF000000.toInt() or (r shl 16) or (g shl 8) or b)
            }
            6 -> (0xFF000000.toInt() or hex.toInt(16))
            8 -> hex.toLong(16).toInt()
            else -> return null
        }
    return Color(argb)
}

fun Color.toRgb(): String {
    val r = (red * 255).roundToInt()
    val g = (green * 255).roundToInt()
    val b = (blue * 255).roundToInt()

    return if (alpha == 1f) {
        "rgb($r, $g, $b)"
    } else {
        "rgba($r, $g, $b, $alpha)"
    }
}

fun String.parseRgb(): Color? {
    val match =
        Regex("rgba?\\(\\s*([\\d.]+)\\s*,\\s*([\\d.]+)\\s*,\\s*([\\d.]+)\\s*(?:,\\s*([\\d.]+)\\s*)?\\)").find(trim())
            ?: return null
    val r = match.groupValues[1].toFloatOrNull() ?: return null
    val g = match.groupValues[2].toFloatOrNull() ?: return null
    val b = match.groupValues[3].toFloatOrNull() ?: return null
    val a = match.groupValues[4].takeIf { it.isNotEmpty() }?.toFloatOrNull() ?: 1f
    return Color(r, g, b, a)
}

private fun rgbToHsl(rf: Float, gf: Float, bf: Float): FloatArray {
    val max = maxOf(rf, gf, bf)
    val min = minOf(rf, gf, bf)
    val delta = max - min

    var h = 0f
    val l = (max + min) / 2f
    val s = if (delta == 0f) 0f else delta / (1f - abs(2f * l - 1f))

    if (delta != 0f) {
        h =
            when (max) {
                rf -> ((gf - bf) / delta) % 6f
                gf -> ((bf - rf) / delta) + 2f
                else -> ((rf - gf) / delta) + 4f
            }
        h /= 6f
        if (h < 0f) h += 1f
    }

    return floatArrayOf(h, s, l)
}

fun Color.toHsl(): String {
    val hsl = rgbToHsl(red, green, blue)
    val h = (hsl[0] * 360f).roundToInt()
    val s = (hsl[1] * 100f).roundToInt()
    val l = (hsl[2] * 100f).roundToInt()

    return if (alpha == 1f) {
        "hsl($h, $s%, $l%)"
    } else {
        "hsla($h, $s%, $l%, $alpha)"
    }
}

fun String.parseHsl(): Color? {
    val match =
        Regex("hsla?\\(\\s*([\\d.]+)\\s*,\\s*([\\d.]+)%\\s*,\\s*([\\d.]+)%\\s*(?:,\\s*([\\d.]+)\\s*)?\\)").find(trim())
            ?: return null
    val h = match.groupValues[1].toFloatOrNull() ?: return null
    val s = (match.groupValues[2].toFloatOrNull() ?: return null) / 100f
    val l = (match.groupValues[3].toFloatOrNull() ?: return null) / 100f
    val a = match.groupValues[4].takeIf { it.isNotEmpty() }?.toFloatOrNull() ?: 1f

    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs(((h / 60f) % 2f) - 1f))
    val m = l - c / 2f

    val (r, g, b) =
        when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
    return Color(r + m, g + m, b + m, a)
}
