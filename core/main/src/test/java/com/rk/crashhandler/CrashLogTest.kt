package com.rk.crashhandler

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

/**
 * Pins the on-disk crash report's retention and shape.
 *
 * `crashHandler` only wrote `crash.log` when it could *not* show `CrashActivity` — so on an
 * ordinary crash a release build produced no file at all, and someone filing an issue had
 * nothing to attach. It is now written unconditionally, which makes its size the thing to bound:
 * `appendText` on an unbounded file means the second crash in a session costs more I/O than the
 * first, and a crash-looping device fills its own storage with its own diagnostics.
 *
 * The production change these tests catch: dropping the rotation, or reverting to
 * `appendText` with no cap.
 */
class CrashLogTest {

    private fun dir(): File = Files.createTempDirectory("crashlog").toFile()

    @Test
    fun `nothing is written before a crash`() {
        val d = dir()

        assertThat(CrashLog.files(d)).isEmpty()
        assertThat(CrashLog.read(d)).isEmpty()
    }

    @Test
    fun `a report is written and read back`() {
        val d = dir()

        assertThat(CrashLog.append(d, "java.lang.NullPointerException: boom\n")).isTrue()

        assertThat(CrashLog.read(d)).contains("NullPointerException")
    }

    @Test
    fun `successive crashes accumulate in one file while it fits`() {
        val d = dir()

        CrashLog.append(d, "first\n")
        CrashLog.append(d, "second\n")

        val text = CrashLog.read(d)
        assertThat(text).contains("first")
        assertThat(text).contains("second")
    }

    @Test
    fun `an oversized report rotates instead of growing without bound`() {
        val d = dir()
        val max = 64L

        repeat(20) { CrashLog.append(d, "x".repeat(32) + "\n", maxBytes = max) }

        val total = CrashLog.files(d).sumOf { it.length() }
        assertThat(total).isAtMost(max * 3)
    }

    @Test
    fun `rotation keeps a bounded number of generations`() {
        val d = dir()

        repeat(50) { CrashLog.append(d, "x".repeat(64) + "\n", maxBytes = 48) }

        assertThat(CrashLog.files(d).size).isAtMost(2)
    }

    @Test
    fun `the newest report always uses the primary file name`() {
        val d = dir()

        repeat(50) { CrashLog.append(d, "entry $it\n", maxBytes = 40) }

        assertThat(CrashLog.newest(d).exists()).isTrue()
        assertThat(CrashLog.files(d)).contains(CrashLog.newest(d))
    }

    @Test
    fun `the newest report contains the most recent entry`() {
        val d = dir()

        repeat(50) { CrashLog.append(d, "entry $it\n", maxBytes = 40) }

        assertThat(CrashLog.read(d)).contains("entry 49")
    }

    @Test
    fun `the read is bounded so a large report cannot be loaded whole`() {
        val d = dir()
        CrashLog.append(d, "y".repeat(5000))

        assertThat(CrashLog.read(d, maxBytes = 100).length).isAtMost(100)
    }

    @Test
    fun `a share header names the build and the device`() {
        val header = CrashLog.shareHeader("xyzterm", "1.0.0-beta1", "release")

        assertThat(header).contains("xyzterm 1.0.0-beta1 (release)")
        assertThat(header).contains("Android:")
        assertThat(header).contains("Abi:")
    }
}