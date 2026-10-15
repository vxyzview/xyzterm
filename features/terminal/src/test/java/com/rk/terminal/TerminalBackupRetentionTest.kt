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
 * deleting a `.part` file that is still being written.
 */
class TerminalBackupRetentionTest {

    /** Fixed clock so staleness is arithmetic, not wall-clock dependent. */
    private val now = 1_700_000_000_000L

    /** Each entry is `fileName to ageMs`; a null age means "just created". */
    private fun dirWith(vararg entries: Pair<String, Long?>): File =
        Files.createTempDirectory("backups").toFile().also { d ->
            entries.forEach { (name, ageMs) ->
                File(d, name).apply {
                    writeText("x")
                    if (ageMs != null) {
                        check(setLastModified(now - ageMs)) { "could not backdate $name" }
                    }
                }
            }
        }

    @Test
    fun `part files are never offered for pruning`() {
        val dir = dirWith("terminal-backup-1000.tar.gz" to null, "terminal-backup-2000.tar.gz.part" to null)

        val prunable = TerminalBackup.completedBackups(dir).map { it.name }

        assertThat(prunable).containsExactly("terminal-backup-1000.tar.gz")
    }

    @Test
    fun `completed backups are listed newest first`() {
        val dir =
            dirWith(
                "terminal-backup-1000.tar.gz" to null,
                "terminal-backup-3000.tar.gz" to null,
                "terminal-backup-2000.tar.gz" to null,
            )

        assertThat(TerminalBackup.completedBackups(dir).map { it.name })
            .containsExactly("terminal-backup-3000.tar.gz", "terminal-backup-2000.tar.gz", "terminal-backup-1000.tar.gz")
            .inOrder()
    }

    @Test
    fun `unrelated files in the backup dir are ignored`() {
        val dir =
            dirWith(
                "terminal-backup-1000.tar.gz" to null,
                "notes.txt" to null,
                "exported-elsewhere.tar.gz" to null,
            )

        assertThat(TerminalBackup.completedBackups(dir).map { it.name })
            .containsExactly("terminal-backup-1000.tar.gz")
    }

    @Test
    fun `a partial from two days ago is stale`() {
        val dir = dirWith("terminal-backup-1000.tar.gz.part" to 2 * 24 * 60 * 60 * 1000L)

        assertThat(TerminalBackup.stalePartials(dir, now).map { it.name })
            .containsExactly("terminal-backup-1000.tar.gz.part")
    }

    @Test
    fun `a partial written a minute ago is still in flight and must survive`() {
        val dir = dirWith("terminal-backup-1000.tar.gz.part" to 60_000L)

        assertThat(TerminalBackup.stalePartials(dir, now)).isEmpty()
    }

    @Test
    fun `a partial just inside the staleness window is still in flight`() {
        val dir = dirWith("terminal-backup-1000.tar.gz.part" to TerminalBackup.STALE_PARTIAL_MS - 1_000L)

        assertThat(TerminalBackup.stalePartials(dir, now)).isEmpty()
    }

    @Test
    fun `a completed archive is never treated as a stale partial`() {
        val dir =
            dirWith(
                "terminal-backup-1000.tar.gz" to 2 * 24 * 60 * 60 * 1000L,
                "terminal-backup-2000.tar.gz.part" to 2 * 24 * 60 * 60 * 1000L,
            )

        assertThat(TerminalBackup.stalePartials(dir, now).map { it.name })
            .containsExactly("terminal-backup-2000.tar.gz.part")
    }
}