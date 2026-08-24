package com.rk.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerminalRestoreSwapTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun atomicSwap(live: File, staging: File, old: File): Boolean {
        if (old.exists() && !old.deleteRecursively()) return false
        if (live.exists() && !live.renameTo(old)) return false
        if (!staging.renameTo(live)) {
            old.renameTo(live)
            return false
        }
        old.deleteRecursively()
        return true
    }

    private fun newLive(): File =
        tmp.newFolder("sandbox").apply { File(this, "user-data.txt").writeText("precious") }

    private fun newStaging(name: String = "staging"): File =
        tmp.newFolder(name).apply { File(this, "restored.conf").writeText("restored") }

    @Test
    fun happyPathInstallsStagingAndClearsStaleOld() {
        val live = newLive()
        val staging = newStaging()
        val old = File(tmp.root, "sandbox.old")
        old.mkdirs()
        File(old, "stale-junk").writeText("stale")

        assertTrue(atomicSwap(live, staging, old))

        assertEquals("restored", File(live, "restored.conf").readText())
        assertFalse(File(live, "user-data.txt").exists())
        assertFalse(File(live, "stale-junk").exists())
        assertFalse(old.exists())
    }

    @Test
    fun stagingMissingLeavesOriginalUntouched() {
        val live = newLive()
        val missingStaging = File(tmp.root, "does-not-exist")
        val old = File(tmp.root, "sandbox.old")

        assertFalse(atomicSwap(live, missingStaging, old))

        assertEquals("precious", File(live, "user-data.txt").readText())
        assertFalse(File(live, "restored.conf").exists())
        assertFalse(old.exists())
    }

    @Test
    fun failedSecondRenameRollsBackOldToLive() {
        val live = newLive()
        val old = File(tmp.root, "sandbox.old")
        old.mkdirs()
        File(old, "previous-rootfs").writeText("old")

        assertFalse(atomicSwap(live, File(tmp.root, "missing-staging"), old))

        assertEquals("precious", File(live, "user-data.txt").readText())
        assertFalse(File(live, "previous-rootfs").exists())
        assertFalse(File(live, "restored.conf").exists())
        assertFalse(old.exists())
    }
}
