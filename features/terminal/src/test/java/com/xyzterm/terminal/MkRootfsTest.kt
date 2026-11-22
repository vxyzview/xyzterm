package com.xyzterm.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class MkRootfsTest {
    @Test
    fun staged_tarball_without_marker_needs_extraction() {
        assertEquals(NEXT_STAGE.EXTRACTION, nextStageFor(tarballExists = true, markerExists = false))
    }

    @Test
    fun no_tarball_means_nothing_to_extract() {
        assertEquals(NEXT_STAGE.NONE, nextStageFor(tarballExists = false, markerExists = false))
    }

    @Test
    fun install_marker_means_already_extracted() {
        assertEquals(NEXT_STAGE.NONE, nextStageFor(tarballExists = true, markerExists = true))
    }
}
