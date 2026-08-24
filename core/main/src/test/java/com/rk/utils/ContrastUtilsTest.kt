package com.rk.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastUtilsTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun blackVsWhiteIs21() {
        assertEquals(21.0, ContrastUtils.ratio(black, white), 1e-9)
        assertEquals(21.0, ContrastUtils.ratio(white, black), 1e-9)
    }

    @Test
    fun sameColorIsOne() {
        assertEquals(1.0, ContrastUtils.ratio(black, black), 1e-12)
        assertEquals(1.0, ContrastUtils.ratio(white, white), 1e-12)
        assertEquals(1.0, ContrastUtils.ratio(0xFF336699.toInt(), 0xFF336699.toInt()), 1e-12)
    }

    @Test
    fun knownPairSanity() {
        assertEquals(4.0, ContrastUtils.ratio(0xFFFF0000.toInt(), white), 0.01)
        assertEquals(
            8.59,
            ContrastUtils.ratio(0xFF0000FF.toInt(), white),
            0.01,
        )
    }

    @Test
    fun symmetric() {
        val fg = 0xFF123456.toInt()
        val bg = 0xFFFEDCBA.toInt()
        assertEquals(ContrastUtils.ratio(fg, bg), ContrastUtils.ratio(bg, fg), 1e-12)
    }

    @Test
    fun alphaIgnored() {
        val opaque = 0xFFABCDEF.toInt()
        val translucent = 0x00ABCDEF
        val other = 0x80123456.toInt()
        assertEquals(ContrastUtils.ratio(opaque, other), ContrastUtils.ratio(translucent, other), 0.0)
    }

    @Test
    fun ratioNeverBelowOne() {
        val samples =
            intArrayOf(
                0x000000,
                0xFFFFFF,
                0x7F7F7F,
                0x010203,
                0xFEFEFE,
                0xFF8800,
                0x00FF88,
            )
        for (fg in samples) {
            for (bg in samples) {
                assertTrue(ContrastUtils.ratio(fg, bg) >= 1.0)
            }
        }
    }
}
