package com.xyzterm.terminal

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ScrollbackTextTest {
    @Test
    fun no_transcript_starts_at_zero() {
        assertArrayEquals(intArrayOf(0, 0, 79, 23), fullScrollbackBounds(0, 80, 24))
    }

    @Test
    fun transcript_starts_at_oldest_row() {
        assertArrayEquals(intArrayOf(0, -5, 79, 23), fullScrollbackBounds(5, 80, 24))
    }

    @Test
    fun single_column_ends_at_zero() {
        assertArrayEquals(intArrayOf(0, -3, 0, 0), fullScrollbackBounds(3, 1, 1))
    }
}
