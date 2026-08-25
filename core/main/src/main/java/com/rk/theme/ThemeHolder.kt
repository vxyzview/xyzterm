package com.rk.theme

import androidx.annotation.Keep
import androidx.compose.material3.ColorScheme
import java.util.Properties

@Keep
data class ThemeHolder(
    val id: String,
    val name: String,
    val inheritBase: Boolean,
    val lightScheme: ColorScheme,
    val darkScheme: ColorScheme,
    val lightTerminalColors: Properties,
    val darkTerminalColors: Properties,
)
