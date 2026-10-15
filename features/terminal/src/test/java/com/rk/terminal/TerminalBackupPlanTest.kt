package com.rk.terminal

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Pins the shape of a backup archive.
 *
 * `sandbox.sh` binds the guest's `/home` and `/root` to `$EXT_HOME`, which is `local/home` — a
 * **sibling** of `local/sandbox`, not a child. A `tar` rooted at `local/sandbox` therefore cannot
 * see a single byte of the user's home directory, so every restore produced an empty `$HOME`.
 *
 * The production change these tests catch: reverting `backupCommand` to root the archive at
 * `local/sandbox`, or re-adding unanchored `--exclude` patterns.
 */
class TerminalBackupPlanTest {

    @Test
    fun `backup archives both the sandbox and the home directory`() {
        val argv = TerminalBackup.backupCommand(File("/out.tar.gz"))

        assertThat(argv).contains("./sandbox")
        assertThat(argv).contains("./home")
    }

    @Test
    fun `backup runs tar with the target as the archive argument`() {
        val argv = TerminalBackup.backupCommand(File("/out.tar.gz"))

        assertThat(argv.take(3)).containsExactly("tar", "-czf", "/out.tar.gz").inOrder()
    }

    @Test
    fun `excludes are anchored to the archive root`() {
        val argv = TerminalBackup.backupCommand(File("/out.tar.gz"))

        // An unanchored `--exclude=data` matches the final path component at any
        // depth, so it silently dropped ~/projects/data as well as guest /data.
        assertThat(argv).doesNotContain("--exclude=data")
        assertThat(argv).doesNotContain("--exclude=tmp")
        assertThat(argv).contains("--exclude=./sandbox/data")
    }

    @Test
    fun `the live tmpfs bind is excluded`() {
        val argv = TerminalBackup.backupCommand(File("/out.tar.gz"))

        assertThat(argv).contains("--exclude=./sandbox/tmp")
    }

    @Test
    fun `restore replaces the sandbox and the home directory`() {
        val base = File("/base")

        val roots = TerminalBackup.restoreRoots(base)

        assertThat(roots.map { it.name }).containsExactly("sandbox", "home").inOrder()
        assertThat(roots.map { it.parentFile }).containsExactly(base, base)
    }
}