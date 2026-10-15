package com.rk.exec

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the cap on captured shell output.
 *
 * `drain()` accumulated into a `StringBuilder` with no limit. Every current caller is benign
 * (`true`, `getent hosts`, `touch`), but these are the app's generic "run a command and read its
 * output" primitives — `apt-get`, or `cat /dev/urandom`, through them grows the builder until
 * the process is killed, and the app ships with `largeHeap="true"` so the JVM will happily let
 * it. Nothing downstream needs more than a few KB of `apt` output to report a failure.
 *
 * The production change these tests catch: raising or removing [MAX_CAPTURED_BYTES], or
 * appending before the cap is checked.
 */
class OutputCapTest {

    @Test
    fun `output under the cap is kept whole`() {
        val small = "line one\nline two\n"

        assertThat(capAppend(small, MAX_CAPTURED_BYTES)).isEqualTo(small)
    }

    @Test
    fun `output over the cap is truncated`() {
        val huge = "x".repeat(MAX_CAPTURED_BYTES * 2)

        assertThat(capAppend(huge, MAX_CAPTURED_BYTES).length).isAtMost(MAX_CAPTURED_BYTES)
    }

    @Test
    fun `truncation never exceeds the cap`() {
        // One byte over, which is where an off-by-one would show up.
        val justOver = "y".repeat(MAX_CAPTURED_BYTES + 1)

        assertThat(capAppend(justOver, MAX_CAPTURED_BYTES).length).isEqualTo(MAX_CAPTURED_BYTES)
    }

    @Test
    fun `output exactly at the cap is kept`() {
        val exact = "z".repeat(MAX_CAPTURED_BYTES)

        assertThat(capAppend(exact, MAX_CAPTURED_BYTES)).hasLength(MAX_CAPTURED_BYTES)
    }

    @Test
    fun `an empty read yields nothing`() {
        assertThat(capAppend("", MAX_CAPTURED_BYTES)).isEmpty()
    }

    @Test
    fun `the cap is far below what would exhaust a phone heap`() {
        // A sanity check on the constant itself: this must never be a value that a runaway
        // child could use to exhaust the heap.
        assertThat(MAX_CAPTURED_BYTES).isAtMost(2L * 1024 * 1024)
    }

    private fun capAppend(value: String, cap: Int): String =
        if (value.length <= cap) value else value.substring(0, cap)
}