package com.xyzterm.terminal

import com.xyzterm.activities.terminal.InstallStage
import com.xyzterm.activities.terminal.nextInstallStage
import org.junit.Assert.assertEquals
import org.junit.Test

class InstallStateTest {
    @Test
    fun already_installed_is_ready() {
        assertEquals(
            InstallStage.Ready,
            nextInstallStage(
                installed = true,
                supported = true,
                copyDone = false,
                extractionDone = false,
                failed = false,
            ),
        )
    }

    @Test
    fun unsupported_cpu_wins_over_copying() {
        assertEquals(
            InstallStage.UnsupportedCpu,
            nextInstallStage(false, false, false, false, false),
        )
    }

    @Test
    fun failure_is_terminal_until_retry_resets_it() {
        assertEquals(
            InstallStage.Failed,
            nextInstallStage(false, true, true, false, true),
        )
    }

    @Test
    fun staged_tarball_needs_extraction() {
        assertEquals(
            InstallStage.Extracting,
            nextInstallStage(false, true, true, false, false),
        )
    }

    @Test
    fun consumed_extraction_is_ready() {
        assertEquals(
            InstallStage.Ready,
            nextInstallStage(false, true, true, true, false),
        )
    }

    @Test
    fun retry_returns_to_copying() {
        assertEquals(
            InstallStage.Copying,
            nextInstallStage(false, true, false, false, false),
        )
    }
}
