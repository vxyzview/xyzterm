package com.rk.utils

import kotlin.math.pow

/** Pure WCAG 2.x relative-luminance contrast math; JVM-safe, no Android dependencies. */
object ContrastUtils {

    fun ratio(fgColor: Int, bgColor: Int): Double {
        val fgLuminance = luminance(fgColor)
        val bgLuminance = luminance(bgColor)
        val lighter = maxOf(fgLuminance, bgLuminance)
        val darker = minOf(fgLuminance, bgLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun luminance(color: Int): Double {
        val r = linearize(((color shr 16) and 0xFF) / 255.0)
        val g = linearize(((color shr 8) and 0xFF) / 255.0)
        val b = linearize((color and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun linearize(channel: Double): Double =
        if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
}
