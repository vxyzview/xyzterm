package com.rk.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins how the app classifies an install.
 *
 * `getNextStage` decided "is the rootfs installed?" by asking whether `sandboxDir()` had any
 * entries. Extraction wrote straight into the live `sandbox/`, so a tar killed by an OOM-kill, a
 * battery pull or ENOSPC left a *partially extracted* tree — and "not empty" is exactly what a
 * partial tree looks like. The app then reported "not installed" while `sandbox.tar.gz` was
 * present and hash-verified, so `getNextStage` kept answering NONE and the install screen looped
 * forever with nothing left to do.
 *
 * The decision now comes from an explicit completion marker, written only after a successful,
 * fully staged extraction.
 *
 * The production change these tests catch: going back to inferring state from directory contents.
 */
class RootfsStageTest {

    @Test
    fun `a verified tarball with no rootfs yet needs extraction`() {
        assertThat(stageState(tarballPresent = true, markerPresent = false))
            .isEqualTo(StageState.NEEDS_EXTRACTION)
    }

    @Test
    fun `a complete rootfs needs nothing`() {
        assertThat(stageState(tarballPresent = true, markerPresent = true))
            .isEqualTo(StageState.INSTALLED)
    }

    @Test
    fun `a half-extracted tree with no marker is never mistaken for installed`() {
        assertThat(stageState(tarballPresent = true, markerPresent = false))
            .isNotEqualTo(StageState.INSTALLED)
    }

    @Test
    fun `no tarball and no rootfs means nothing to install`() {
        assertThat(stageState(tarballPresent = false, markerPresent = false))
            .isEqualTo(StageState.NOWHERE_TO_START)
    }

    @Test
    fun `no tarball but a complete rootfs is still installed`() {
        assertThat(stageState(tarballPresent = false, markerPresent = true))
            .isEqualTo(StageState.INSTALLED)
    }

    @Test
    fun `a complete rootfs wins over a leftover tarball`() {
        // The tarball is deleted after a successful install; if a stale copy survives,
        // the marker is still authoritative and we must not re-extract 1.5 GB over it.
        assertThat(stageState(tarballPresent = true, markerPresent = true))
            .isEqualTo(StageState.INSTALLED)
    }
}