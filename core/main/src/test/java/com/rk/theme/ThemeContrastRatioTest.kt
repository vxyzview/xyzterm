package com.rk.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeContrastRatioTest {

    private fun manifest(
        light: ThemePaletteNew? = null,
        dark: ThemePaletteNew? = null,
    ): ThemeManifest = ThemeManifest(id = "test-theme", name = "test-theme", light = light, dark = dark)

    @Test
    fun missingColorsYieldNullRatio() {
        assertNull(themeContrastRatio(manifest()))
        assertNull(
            themeContrastRatio(
                manifest(
                    light =
                        ThemePaletteNew(
                            baseColors = BaseColors(),
                            terminalColors = mapOf("cursor" to "#FFFFFF"),
                        ),
                ),
            ),
        )
    }

    @Test
    fun unparseableHexYieldsNullRatio() {
        assertNull(
            themeContrastRatio(
                manifest(
                    light =
                        ThemePaletteNew(
                            baseColors = BaseColors(background = "not-a-color", onBackground = "also-not"),
                        ),
                ),
            ),
        )
    }

    @Test
    fun validHexPairsYieldNullOnJvmWhereHexParsingNeedsAndroidFramework() {
        val explicitTerminalPair =
            ThemePaletteNew(
                baseColors = BaseColors(background = "#000000", onBackground = "#FFFFFF"),
                terminalColors = mapOf("background" to "#101010", "foreground" to "#EEEEEE"),
            )
        assertNull(themeContrastRatio(manifest(light = explicitTerminalPair)))

        val baseColorsFallbackOnly =
            ThemePaletteNew(baseColors = BaseColors(background = "#FFFFFF", onBackground = "#000000"))
        assertNull(themeContrastRatio(manifest(dark = baseColorsFallbackOnly)))
    }

    @Test
    fun thresholdConstantIsThree() {
        assertEquals(3.0, THEME_MIN_CONTRAST_RATIO, 0.0)
    }
}
