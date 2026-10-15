package com.rk.terminal

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

/**
 * Pins the retention rules for backup archives.
 *
 * `autoBackup()` writes to `<name>.tar.gz.part` and then prunes with
 * `startsWith("terminal-backup-")`. A `.part` file satisfies that prefix, sorts newest by
 * millisecond timestamp, and therefore survives pruning — so an interrupted multi-GB tar stayed
 * on the user's storage forever while every real backup was deleted in its place.
 *
 * The production change these tests catch: letting `.part` files back into the prune list, or
 * deleting `.part` files that are still being written.
 */
class TerminalBackupRetentionTest {

    private fun dirWith(vararg names: String): File =
        Files.createTempDirectory("backups").toFile().also { d ->
            names.forEach { File(d, it).writeText("x") }
        }

    @Test
    fun `part files are never offered for pruning`() {
        val dir = dirWith("terminal-backup-1000.tar.gz", "terminal-backup-2000.tar.gz.part")

        val prunable = TerminalBackup.completedBackups(dir).map { it.name }

        assertThat(prunable).containsExactly("terminal-backup-2000.tar.gz")
    }

    @Test
    fun `completed backups are listed newest first`() {
        val dir = dirWith("terminal-backup-1000.tar.gz", "terminal-backup-3000.tar.gz", "terminal-backup-2000.tar.gz")

        assertThat(TerminalBackup.completedBackups(dir).map { it.name })
            .containsExactly("terminal-backup-3000.tar.gz", "terminal-backup-2000.tar.gz", "terminal-backup-1000.tar.gz")
            .inOrder()
    }

    @Test
    fun `unrelated files in the backup dir are ignored`() {
        val dir = dirWith("terminal-backup-1000.tar.gz", "notes.txt", "exported-elsewhere.tar.gz")

        assertThat(TerminalBackup.completedBackups(dir).map { it.name }).containsExactly("terminal-backup-1000.tar.gz")
    }

    @Test
    fun `a partial from hours ago is stale`() {
        val dir = dirWith("terminal-backup-1000.tar.gz.part")
        val now = 1_700_000_000_000L

        assertThat(TerminalBackup.stalePartials(dir, now).map { it.name }).containsExactly("terminal-backup-1000.tar.gz.part")
    }

    @Test
    fun `a partial written moments ago is still in flight and must survive`() {
        val dir = dirWith("terminal-backup-1000.tar.gz.part")
        val now = 1_700_000_000_000L

        assertThat(TerminalBackup.stalePartials(dir, now)).isEmpty()
    }

    @Test
    fun `exactly at the staleness boundary the partial is still in flight`() {
        val dir = dirWith("terminal-backup-1000.tar.gz.part")
        val now = 1_700_000_000_000L

        assertThat(TerminalBackup.stalePartials(dir, now - TerminalBackup.STALE_PARTIAL_MS)).isEmpty()
    }
}