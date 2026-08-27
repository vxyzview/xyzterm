package com.rk.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.google.gson.JsonArray
import com.rk.color.toHex
import com.rk.resources.getString
import com.rk.resources.strings
import java.util.Properties

val blueberry =
    ThemeHolder(
        id = "blueberry-default",
        name = "BlueBerry (${strings.default_option.getString()})",
        inheritBase = true,
        lightScheme =
            lightColorScheme(
                primary = Color(0xFF445E91),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFD8E2FF),
                onPrimaryContainer = Color(0xFF001A41),
                secondary = Color(0xFF575E71),
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Color(0xFFDBE2F9),
                onSecondaryContainer = Color(0xFF141B2C),
                tertiary = Color(0xFF715573),
                onTertiary = Color(0xFFFFFFFF),
                tertiaryContainer = Color(0xFFFBD7FC),
                onTertiaryContainer = Color(0xFF29132D),
                error = Color(0xFFBA1A1A),
                errorContainer = Color(0xFFFFDAD6),
                onError = Color(0xFFFFFFFF),
                onErrorContainer = Color(0xFF410002),
                background = Color(0xFFF9F9FF),
                onBackground = Color(0xFF1A1B20),
                surface = Color(0xFFF9F9FF),
                onSurface = Color(0xFF1A1B20),
                surfaceVariant = Color(0xFFE1E2EC),
                onSurfaceVariant = Color(0xFF44474F),
                outline = Color(0xFF74777F),
                inverseOnSurface = Color(0xFFF0F0F7),
                inverseSurface = Color(0xFF2F3036),
                inversePrimary = Color(0xFFADC6FF),
                surfaceTint = Color(0xFF445E91),
                outlineVariant = Color(0xFFC4C6D0),
                scrim = Color(0xFF000000),
            ),
        darkScheme =
            darkColorScheme(
                primary = Color(0xFFADC6FF),
                onPrimary = Color(0xFF102F60),
                primaryContainer = Color(0xFF2B4678),
                onPrimaryContainer = Color(0xFFD8E2FF),
                secondary = Color(0xFFBFC6DC),
                onSecondary = Color(0xFF293041),
                secondaryContainer = Color(0xFF3F4759),
                onSecondaryContainer = Color(0xFFDBE2F9),
                tertiary = Color(0xFFDEBCDF),
                onTertiary = Color(0xFF402843),
                tertiaryContainer = Color(0xFF583E5B),
                onTertiaryContainer = Color(0xFFFBD7FC),
                error = Color(0xFFFFB4AB),
                errorContainer = Color(0xFF93000A),
                onError = Color(0xFF690005),
                onErrorContainer = Color(0xFFFFDAD6),
                background = Color(0xFF111318),
                onBackground = Color(0xFFE2E2E9),
                surface = Color(0xFF111318),
                onSurface = Color(0xFFE2E2E9),
                surfaceVariant = Color(0xFF44474F),
                onSurfaceVariant = Color(0xFFC4C6D0),
                outline = Color(0xFF8E9099),
                inverseOnSurface = Color(0xFF2F3036),
                inverseSurface = Color(0xFFE2E2E9),
                inversePrimary = Color(0xFF445E91),
                surfaceTint = Color(0xFFD8E2FF),
                outlineVariant = Color(0xFF44474F),
                scrim = Color(0xFF000000),
            ),
        lightTokenColors = JsonArray(),
        darkTokenColors = JsonArray(),
        lightTerminalColors =
            Properties().also {
                it["foreground"] = Color(0xFF1A1B20).toHex()
                it["background"] = Color(0xFFF9F9FF).toHex()
                it["cursor"] = "#373b41"

                it["color0"] = "#1d1f21"
                it["color1"] = "#CC342B"
                it["color2"] = "#198844"
                it["color3"] = "#FBA922"
                it["color4"] = "#3971ED"
                it["color5"] = "#A36AC7"
                it["color6"] = "#3971ED"
                it["color7"] = "#c5c8c6"
                it["color8"] = "#969896"
                it["color9"] = "#CC342B"
                it["color10"] = "#198844"
                it["color11"] = "#FBA922"
                it["color12"] = "#3971ED"
                it["color13"] = "#A36AC7"
                it["color14"] = "#3971ED"
                it["color15"] = "#ffffff"

                it["color16"] = "#F96A38"
                it["color17"] = "#3971ED"
                it["color18"] = "#282a2e"
                it["color19"] = "#373b41"
                it["color20"] = "#b4b7b4"
                it["color21"] = "#e0e0e0"
            },
        darkTerminalColors =
            Properties().also {
                it["background"] = Color(0xFF111318).toHex()
                it["foreground"] = Color(0xFFE2E2E9).toHex()
                it["cursor"] = "#6e6a86"

                // black
                it["color0"] = "#393552"
                it["color8"] = "#6e6a86"

                // red
                it["color1"] = "#eb6f92"
                it["color9"] = "#eb6f92"

                // green
                it["color2"] = "#3e8fb0"
                it["color10"] = "#3e8fb0"

                // yellow
                it["color3"] = "#f6c177"
                it["color11"] = "#f6c177"

                // blue
                it["color4"] = "#9ccfd8"
                it["color12"] = "#9ccfd8"

                // magenta
                it["color5"] = "#c4a7e7"
                it["color13"] = "#c4a7e7"

                // cyan
                it["color6"] = "#ea9a97"
                it["color14"] = "#ea9a97"

                // white
                it["color7"] = "#e0def4"
                it["color15"] = "#e0def4"
            },
    )

val lime =
    ThemeHolder(
        id = "lime",
        name = "Lime",
        inheritBase = true,
        lightScheme =
            lightColorScheme(
                primary = Color(0xFF4C662B),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFCDEDA3),
                onPrimaryContainer = Color(0xFF354E16),
                secondary = Color(0xFF586249),
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Color(0xFFDCE7C8),
                onSecondaryContainer = Color(0xFF404A33),
                tertiary = Color(0xFF386663),
                onTertiary = Color(0xFFFFFFFF),
                tertiaryContainer = Color(0xFFBCECE7),
                onTertiaryContainer = Color(0xFF1F4E4B),
                error = Color(0xFFBA1A1A),
                onError = Color(0xFFFFFFFF),
                errorContainer = Color(0xFFFFDAD6),
                onErrorContainer = Color(0xFF93000A),
                background = Color(0xFFF9FAEF),
                onBackground = Color(0xFF1A1C16),
                surface = Color(0xFFF9FAEF),
                onSurface = Color(0xFF1A1C16),
                surfaceVariant = Color(0xFFE1E4D5),
                onSurfaceVariant = Color(0xFF44483D),
                outline = Color(0xFF75796C),
                outlineVariant = Color(0xFFC5C8BA),
                scrim = Color(0xFF000000),
                inverseSurface = Color(0xFF2F312A),
                inverseOnSurface = Color(0xFFF1F2E6),
                inversePrimary = Color(0xFFB1D18A),
                surfaceTint = Color(0xFF4C662B),
                surfaceDim = Color(0xFFDADBD0),
                surfaceBright = Color(0xFFF9FAEF),
                surfaceContainerLowest = Color(0xFFFFFFFF),
                surfaceContainerLow = Color(0xFFF3F4E9),
                surfaceContainer = Color(0xFFEEEFE3),
                surfaceContainerHigh = Color(0xFFE8E9DE),
                surfaceContainerHighest = Color(0xFFE2E3D8),
            ),
        darkScheme =
            darkColorScheme(
                primary = Color(0xFFB1D18A),
                onPrimary = Color(0xFF1F3701),
                primaryContainer = Color(0xFF354E16),
                onPrimaryContainer = Color(0xFFCDEDA3),
                secondary = Color(0xFFBFCBAD),
                onSecondary = Color(0xFF2A331E),
                secondaryContainer = Color(0xFF404A33),
                onSecondaryContainer = Color(0xFFDCE7C8),
                tertiary = Color(0xFFA0D0CB),
                onTertiary = Color(0xFF003735),
                tertiaryContainer = Color(0xFF1F4E4B),
                onTertiaryContainer = Color(0xFFBCECE7),
                error = Color(0xFFFFB4AB),
                onError = Color(0xFF690005),
                errorContainer = Color(0xFF93000A),
                onErrorContainer = Color(0xFFFFDAD6),
                background = Color(0xFF12140E),
                onBackground = Color(0xFFE2E3D8),
                surface = Color(0xFF12140E),
                onSurface = Color(0xFFE2E3D8),
                surfaceVariant = Color(0xFF44483D),
                onSurfaceVariant = Color(0xFFC5C8BA),
                outline = Color(0xFF8F9285),
                outlineVariant = Color(0xFF44483D),
                scrim = Color(0xFF000000),
                inverseSurface = Color(0xFFE2E3D8),
                inverseOnSurface = Color(0xFF2F312A),
                inversePrimary = Color(0xFF4C662B),
                surfaceTint = Color(0xFFB1D18A),
                surfaceDim = Color(0xFF12140E),
                surfaceBright = Color(0xFF383A32),
                surfaceContainerLowest = Color(0xFF0C0F09),
                surfaceContainerLow = Color(0xFF1A1C16),
                surfaceContainer = Color(0xFF1E201A),
                surfaceContainerHigh = Color(0xFF282B24),
                surfaceContainerHighest = Color(0xFF33362E),
            ),
        lightTokenColors = JsonArray(),
        darkTokenColors = JsonArray(),
        lightTerminalColors =
            Properties().also {
                it["foreground"] = Color(0xFF1A1C16).toHex()
                it["background"] = Color(0xFFF9FAEF).toHex()
                it["cursor"] = "#373b41"

                it["color0"] = "#1d1f21"
                it["color1"] = "#CC342B"
                it["color2"] = "#198844"
                it["color3"] = "#FBA922"
                it["color4"] = "#3971ED"
                it["color5"] = "#A36AC7"
                it["color6"] = "#3971ED"
                it["color7"] = "#c5c8c6"
                it["color8"] = "#969896"
                it["color9"] = "#CC342B"
                it["color10"] = "#198844"
                it["color11"] = "#FBA922"
                it["color12"] = "#3971ED"
                it["color13"] = "#A36AC7"
                it["color14"] = "#3971ED"
                it["color15"] = "#ffffff"

                it["color16"] = "#F96A38"
                it["color17"] = "#3971ED"
                it["color18"] = "#282a2e"
                it["color19"] = "#373b41"
                it["color20"] = "#b4b7b4"
                it["color21"] = "#e0e0e0"
            },
        darkTerminalColors =
            Properties().also {
                it["background"] = Color(0xFF12140E).toHex()
                it["foreground"] = Color(0xFFE2E3D8).toHex()
                it["cursor"] = "#6e6a86"

                // black
                it["color0"] = "#393552"
                it["color8"] = "#6e6a86"

                // red
                it["color1"] = "#eb6f92"
                it["color9"] = "#eb6f92"

                // green
                it["color2"] = "#3e8fb0"
                it["color10"] = "#3e8fb0"

                // yellow
                it["color3"] = "#f6c177"
                it["color11"] = "#f6c177"

                // blue
                it["color4"] = "#9ccfd8"
                it["color12"] = "#9ccfd8"

                // magenta
                it["color5"] = "#c4a7e7"
                it["color13"] = "#c4a7e7"

                // cyan
                it["color6"] = "#ea9a97"
                it["color14"] = "#ea9a97"

                // white
                it["color7"] = "#e0def4"
                it["color15"] = "#e0def4"
            },
    )

// Canonical 16-color ANSI ramps (plus bg/fg/cursor) reused for the terminal surface.
// Dark themes feed darkTerminalColors, light themes feed lightTerminalColors.
private fun Properties.applyBase16(
    background: String,
    foreground: String,
    cursor: String,
    colors: List<String>,
) {
    this["background"] = background
    this["foreground"] = foreground
    this["cursor"] = cursor
    // colors: [0,8] [1,9] ... [7,15] then 16..21
    val slots = listOf("color0","color1","color2","color3","color4","color5","color6","color7",
        "color8","color9","color10","color11","color12","color13","color14","color15",
        "color16","color17","color18","color19","color20","color21")
    colors.forEachIndexed { i, v -> this[slots[i]] = v }
}

val tokyonight =
    ThemeHolder(
        id = "tokyonight",
        name = "Tokyo Night",
        inheritBase = true,
        lightScheme =
            lightColorScheme(
                primary = Color(0xFF34548A),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFD4E3FF),
                onPrimaryContainer = Color(0xFF214A7A),
                secondary = Color(0xFF6B7394),
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Color(0xFFE1E5F5),
                onSecondaryContainer = Color(0xFF252B40),
                tertiary = Color(0xFF8C5E9E),
                onTertiary = Color(0xFFFFFFFF),
                tertiaryContainer = Color(0xFFF5D9FF),
                onTertiaryContainer = Color(0xFF3A2348),
                error = Color(0xFFBA1A1A),
                onError = Color(0xFFFFFFFF),
                errorContainer = Color(0xFFFFDAD6),
                onErrorContainer = Color(0xFF410002),
                background = Color(0xFFEFF1F7),
                onBackground = Color(0xFF1F2333),
                surface = Color(0xFFEFF1F7),
                onSurface = Color(0xFF1F2333),
                surfaceVariant = Color(0xFFE1E4F0),
                onSurfaceVariant = Color(0xFF44485B),
                outline = Color(0xFF75798C),
                outlineVariant = Color(0xFFC6CADE),
                scrim = Color(0xFF000000),
                inverseSurface = Color(0xFF343850),
                inverseOnSurface = Color(0xFFEFF1F7),
                inversePrimary = Color(0xFFA6C5FF),
                surfaceTint = Color(0xFF34548A),
                surfaceDim = Color(0xFFD0D3DF),
                surfaceBright = Color(0xFFEFF1F7),
                surfaceContainerLowest = Color(0xFFFFFFFF),
                surfaceContainerLow = Color(0xFFE9EBF2),
                surfaceContainer = Color(0xFFE3E6EE),
                surfaceContainerHigh = Color(0xFFDDE0E8),
                surfaceContainerHighest = Color(0xFFD8DAE2),
            ),
        darkScheme =
            darkColorScheme(
                primary = Color(0xFF7AA2F7),
                onPrimary = Color(0xFF152A4E),
                primaryContainer = Color(0xFF274572),
                onPrimaryContainer = Color(0xFFCDDBFF),
                secondary = Color(0xFF949ECE),
                onSecondary = Color(0xFF283353),
                secondaryContainer = Color(0xFF3E496B),
                onSecondaryContainer = Color(0xFFDDE3FF),
                tertiary = Color(0xFFBB9AF7),
                onTertiary = Color(0xFF36215B),
                tertiaryContainer = Color(0xFF4D3A72),
                onTertiaryContainer = Color(0xFFE9DEFF),
                error = Color(0xFFFF9393),
                onError = Color(0xFF68000A),
                errorContainer = Color(0xFF93000A),
                onErrorContainer = Color(0xFFFFDAD6),
                background = Color(0xFF1A1B26),
                onBackground = Color(0xFFC0C7E5),
                surface = Color(0xFF1A1B26),
                onSurface = Color(0xFFC0C7E5),
                surfaceVariant = Color(0xFF44486B),
                onSurfaceVariant = Color(0xFFC0C7E5),
                outline = Color(0xFF8B90B5),
                outlineVariant = Color(0xFF44486B),
                scrim = Color(0xFF000000),
                inverseSurface = Color(0xFFC0C7E5),
                inverseOnSurface = Color(0xFF2A2C3B),
                inversePrimary = Color(0xFF34548A),
                surfaceTint = Color(0xFFCDDBFF),
                surfaceDim = Color(0xFF1A1B26),
                surfaceBright = Color(0xFF40435B),
                surfaceContainerLowest = Color(0xFF15161F),
                surfaceContainerLow = Color(0xFF1F2030),
                surfaceContainer = Color(0xFF23253A),
                surfaceContainerHigh = Color(0xFF2D2F47),
                surfaceContainerHighest = Color(0xFF383B57),
            ),
        lightTokenColors = JsonArray(),
        darkTokenColors = JsonArray(),
        lightTerminalColors =
            Properties().also {
                it.applyBase16(
                    background = "#e1e2ec",
                    foreground = "#343b5c",
                    cursor = "#343b5c",
                    colors = listOf(
                        "#343b5c","#8c4351","#485e30","#8f5e15","#34548a","#533b78","#205478","#c4c9e0",
                        "#565f89","#8c4351","#485e30","#8f5e15","#34548a","#533b78","#205478","#c4c9e0",
                        "#f7a072","#34548a","#565f89","#a9b0d6","#e6e9f7",
                    ),
                )
            },
        darkTerminalColors =
            Properties().also {
                it.applyBase16(
                    background = "#1a1b26",
                    foreground = "#c0c7e5",
                    cursor = "#c0c7e5",
                    colors = listOf(
                        "#15161e","#f7768e","#9ece6a","#e0af68","#7aa2f7","#bb9af7","#7dcfff","#a9b1d6",
                        "#565f89","#f7768e","#9ece6a","#e0af68","#7aa2f7","#bb9af7","#7dcfff","#a9b1d6",
                        "#ff9e64","#7aa2f7","#565f89","#c0c7e5","#1f2333",
                    ),
                )
            },
    )

val gruvbox =
    ThemeHolder(
        id = "gruvbox",
        name = "Gruvbox",
        inheritBase = true,
        lightScheme =
            lightColorScheme(
                primary = Color(0xFF8F3F1D),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFF3D9C7),
                onPrimaryContainer = Color(0xFF6B2C0E),
                secondary = Color(0xFF6F5B43),
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Color(0xFFFAECD8),
                onSecondaryContainer = Color(0xFF554227),
                tertiary = Color(0xFF5E6A36),
                onTertiary = Color(0xFFFFFFFF),
                tertiaryContainer = Color(0xFFE6EFC5),
                onTertiaryContainer = Color(0xFF454F20),
                error = Color(0xFFBA1A1A),
                onError = Color(0xFFFFFFFF),
                errorContainer = Color(0xFFFFDAD6),
                onErrorContainer = Color(0xFF410002),
                background = Color(0xFFFBF1D6),
                onBackground = Color(0xFF271E16),
                surface = Color(0xFFFBF1D6),
                onSurface = Color(0xFF271E16),
                surfaceVariant = Color(0xFFE9DCC0),
                onSurfaceVariant = Color(0xFF4D4434),
                outline = Color(0xFF7F745F),
                outlineVariant = Color(0xFFD2C4A8),
                scrim = Color(0xFF000000),
                inverseSurface = Color(0xFF3C3228),
                inverseOnSurface = Color(0xFFFBEFD4),
                inversePrimary = Color(0xFFF2BC95),
                surfaceTint = Color(0xFF8F3F1D),
                surfaceDim = Color(0xFFE1D6BD),
                surfaceBright = Color(0xFFFBF1D6),
                surfaceContainerLowest = Color(0xFFFFFFFF),
                surfaceContainerLow = Color(0xFFF5EBD1),
                surfaceContainer = Color(0xFFEFE4CA),
                surfaceContainerHigh = Color(0xFFE9DFC4),
                surfaceContainerHighest = Color(0xFFE3D9BF),
            ),
        darkScheme =
            darkColorScheme(
                primary = Color(0xFFE78A4E),
                onPrimary = Color(0xFF4A1E00),
                primaryContainer = Color(0xFF6B3613),
                onPrimaryContainer = Color(0xFFFFDCC4),
                secondary = Color(0xFFD3B790),
                onSecondary = Color(0xFF3D2F17),
                secondaryContainer = Color(0xFF544225),
                onSecondaryContainer = Color(0xFFF0DCBE),
                tertiary = Color(0xFFB8C97E),
                onTertiary = Color(0xFF2C380C),
                tertiaryContainer = Color(0xFF414E1F),
                onTertiaryContainer = Color(0xFFDDE6B5),
                error = Color(0xFFFC8B7C),
                onError = Color(0xFF5A1006),
                errorContainer = Color(0xFF93000A),
                onErrorContainer = Color(0xFFFFDAD6),
                background = Color(0xFF282828),
                onBackground = Color(0xFFEBDDBB),
                surface = Color(0xFF282828),
                onSurface = Color(0xFFEBDDBB),
                surfaceVariant = Color(0xFF4D4434),
                onSurfaceVariant = Color(0xFFD3B790),
                outline = Color(0xFF9C8E72),
                outlineVariant = Color(0xFF4D4434),
                scrim = Color(0xFF000000),
                inverseSurface = Color(0xFFEBDDBB),
                inverseOnSurface = Color(0xFF3C3228),
                inversePrimary = Color(0xFF8F3F1D),
                surfaceTint = Color(0xFFFFDCC4),
                surfaceDim = Color(0xFF282828),
                surfaceBright = Color(0xFF4F4536),
                surfaceContainerLowest = Color(0xFF1F1F1F),
                surfaceContainerLow = Color(0xFF2F2D2B),
                surfaceContainer = Color(0xFF3A352A),
                surfaceContainerHigh = Color(0xFF453F33),
                surfaceContainerHighest = Color(0xFF514A3C),
            ),
        lightTokenColors = JsonArray(),
        darkTokenColors = JsonArray(),
        lightTerminalColors =
            Properties().also {
                it.applyBase16(
                    background = "#fbf1d6",
                    foreground = "#282828",
                    cursor = "#282828",
                    colors = listOf(
                        "#282828","#9d0006","#79740e","#b57614","#076678","#8f3f71","#427b58","#bdae93",
                        "#928374","#cc241d","#98971a","#d79921","#458588","#b16286","#689d6a","#ebdbb2",
                        "#d65d0e","#076678","#928374","#ebdbb2","#fbf1d6",
                    ),
                )
            },
        darkTerminalColors =
            Properties().also {
                it.applyBase16(
                    background = "#282828",
                    foreground = "#ebdbb2",
                    cursor = "#ebdbb2",
                    colors = listOf(
                        "#282828","#cc241d","#98971a","#d79921","#458588","#b16286","#689d6a","#a89984",
                        "#928374","#cc241d","#98971a","#d79921","#458588","#b16286","#689d6a","#ebdbb2",
                        "#d65d0e","#458588","#928374","#ebdbb2","#fbf1d6",
                    ),
                )
            },
    )

val ayu =
    ThemeHolder(
        id = "ayu",
        name = "Ayu",
        inheritBase = true,
        lightScheme =
            lightColorScheme(
                primary = Color(0xFF20526B),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFC5E7F4),
                onPrimaryContainer = Color(0xFF073B52),
                secondary = Color(0xFF5F6F7F),
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Color(0xFFE3ECF6),
                onSecondaryContainer = Color(0xFF475662),
                tertiary = Color(0xFF6B5B95),
                onTertiary = Color(0xFFFFFFFF),
                tertiaryContainer = Color(0xFFEFE6FF),
                onTertiaryContainer = Color(0xFF241A4A),
                error = Color(0xFFBA1A1A),
                onError = Color(0xFFFFFFFF),
                errorContainer = Color(0xFFFFDAD6),
                onErrorContainer = Color(0xFF410002),
                background = Color(0xFFF7FAFC),
                onBackground = Color(0xFF16191D),
                surface = Color(0xFFF7FAFC),
                onSurface = Color(0xFF16191D),
                surfaceVariant = Color(0xFFDDE5EC),
                onSurfaceVariant = Color(0xFF414851),
                outline = Color(0xFF717880),
                outlineVariant = Color(0xFFC2CAD2),
                scrim = Color(0xFF000000),
                inverseSurface = Color(0xFF2B3036),
                inverseOnSurface = Color(0xFFEEF1F4),
                inversePrimary = Color(0xFF98D2EC),
                surfaceTint = Color(0xFF20526B),
                surfaceDim = Color(0xFFD8DEE3),
                surfaceBright = Color(0xFFF7FAFC),
                surfaceContainerLowest = Color(0xFFFFFFFF),
                surfaceContainerLow = Color(0xFFF1F4F8),
                surfaceContainer = Color(0xFFEBEEF2),
                surfaceContainerHigh = Color(0xFFE5E9ED),
                surfaceContainerHighest = Color(0xFFE0E4E8),
            ),
        darkScheme =
            darkColorScheme(
                primary = Color(0xFF86C6E2),
                onPrimary = Color(0xFF063547),
                primaryContainer = Color(0xFF0E4B64),
                onPrimaryContainer = Color(0xFFC5E7F4),
                secondary = Color(0xFFB7C6D4),
                onSecondary = Color(0xFF293743),
                secondaryContainer = Color(0xFF3F4D58),
                onSecondaryContainer = Color(0xFFD4E2EF),
                tertiary = Color(0xFFC4B4F0),
                onTertiary = Color(0xFF382D5E),
                tertiaryContainer = Color(0xFF4F4576),
                onTertiaryContainer = Color(0xFFEAE3FF),
                error = Color(0xFFFF9E97),
                onError = Color(0xFF690005),
                errorContainer = Color(0xFF93000A),
                onErrorContainer = Color(0xFFFFDAD6),
                background = Color(0xFF0F1419),
                onBackground = Color(0xFFC0CBD5),
                surface = Color(0xFF0F1419),
                onSurface = Color(0xFFC0CBD5),
                surfaceVariant = Color(0xFF414851),
                onSurfaceVariant = Color(0xFFB7C6D4),
                outline = Color(0xFF8B95A0),
                outlineVariant = Color(0xFF414851),
                scrim = Color(0xFF000000),
                inverseSurface = Color(0xFFC0CBD5),
                inverseOnSurface = Color(0xFF263037),
                inversePrimary = Color(0xFF20526B),
                surfaceTint = Color(0xFFC5E7F4),
                surfaceDim = Color(0xFF0F1419),
                surfaceBright = Color(0xFF363D45),
                surfaceContainerLowest = Color(0xFF0A0F13),
                surfaceContainerLow = Color(0xFF171D22),
                surfaceContainer = Color(0xFF1B2127),
                surfaceContainerHigh = Color(0xFF26303A),
                surfaceContainerHighest = Color(0xFF313B45),
            ),
        lightTokenColors = JsonArray(),
        darkTokenColors = JsonArray(),
        lightTerminalColors =
            Properties().also {
                it.applyBase16(
                    background = "#f7fafc",
                    foreground = "#5c6773",
                    cursor = "#5c6773",
                    colors = listOf(
                        "#5c6773","#e6536a","#86b300","#f2ae49","#4aa6f0","#a37acc","#4cbf99","#c7c7c7",
                        "#687a8a","#e6536a","#acc395","#f2ae49","#4aa6f0","#a37acc","#4cbf99","#c7c7c7",
                        "#f2974b","#4aa6f0","#687a8a","#c7c7c7","#f7fafc",
                    ),
                )
            },
        darkTerminalColors =
            Properties().also {
                it.applyBase16(
                    background = "#0f1419",
                    foreground = "#bfbdb6",
                    cursor = "#bfbdb6",
                    colors = listOf(
                        "#0f1419","#ea1062","#a8ce93","#f1c40f","#56b6c2","#7a5ff0","#28c9b1","#bfbdb6",
                        "#5c6773","#f25d7a","#a8ce93","#ffcc66","#56b6c2","#b084f0","#28c9b1","#bfbdb6",
                        "#ff9e64","#56b6c2","#5c6773","#bfbdb6","#f7fafc",
                    ),
                )
            },
    )

val builtInThemes = listOf(blueberry, lime, tokyonight, gruvbox, ayu)
