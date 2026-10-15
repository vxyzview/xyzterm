package com.rk.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the free-space check that guards a restore.
 *
 * There was no `StatFs` precheck anywhere in the install or restore path — the only check in the
 * codebase was an advisory log line in a manually-opened debug screen. `restore()` deletes the
 * live 1–2 GB sandbox and *then* renames the staged tree into place, so a device that ran out of
 * room mid-extraction lost the rootfs outright and the user had to re-download 200–400 MB.
 *
 * The requirement is the archive's expanded size, plus the live tree that is about to be replaced
 * and can no longer be assumed present, plus headroom for the extraction to actually finish.
 *
 * The production change these tests catch: dropping the check, or forgetting the live-tree term.
 */
class RestoreSpaceTest {

    @Test
    fun `plenty of room passes`() {
        assertThat(TerminalBackup.freeSpaceSufficient(available = 20L * GB, required = 3 * GB)).isTrue()
    }

    @Test
    fun `exactly enough room passes`() {
        assertThat(TerminalBackup.freeSpaceSufficient(available = 3 * GB, required = 3 * GB)).isTrue()
    }

    @Test
    fun `one byte short fails`() {
        assertThat(TerminalBackup.freeSpaceSufficient(available = 3 * GB - 1, required = 3 * GB)).isFalse()
    }

    @Test
    fun `zero room fails`() {
        assertThat(TerminalBackup.freeSpaceSufficient(available = 0, required = 3 * GB)).isFalse()
    }

    @Test
    fun `a large archive alone can exceed the budget`() {
        // 400 MB compressed Ubuntu tarball; expanded this is well over 1.5 GB.
        val required = TerminalBackup.requiredBytesForRestore(archiveBytes = 400 * MB, liveBytes = 0)

        assertThat(required).isGreaterThan(GB)
    }

    @Test
    fun `an empty live tree still needs headroom`() {
        val required = TerminalBackup.requiredBytesForRestore(archiveBytes = 10 * MB, liveBytes = 0)

        assertThat(required).isAtLeast(TerminalBackup.MIN_HEADROOM_BYTES)
    }

    @Test
    fun `an existing live tree is counted, since it is deleted before the rename`() {
        val empty = TerminalBackup.requiredBytesForRestore(archiveBytes = 10 * MB, liveBytes = 0)
        val withLive = TerminalBackup.requiredBytesForRestore(archiveBytes = 10 * MB, liveBytes = 2 * GB)

        assertThat(withLive).isGreaterThan(empty)
    }

    @Test
    fun `a multi-gigabyte archive is refused rather than started`() {
        // A device with 500 MB free must be told no up front, not after filling the volume.
        assertThat(TerminalBackup.freeSpaceSufficient(available = 500 * MB, required = TerminalBackup.requiredBytesForRestore(400 * MB, 2 * GB)))
            .isFalse()
    }

    private companion object {
        const val MB = 1024L * 1024
        const val GB = 1024 * MB
    }
}