package com.rk.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the process-tree walk used when tearing a session down.
 *
 * `--kill-on-exit` is registered via `atexit()` in proot's `tracee/event.c`, and `atexit` never
 * runs on SIGKILL — which is exactly how Termux terminates a session
 * (`finishIfRunning()` -> `Os.kill(pid, SIGKILL)`). `mShellPid` is the *tracer*, so when it dies
 * the kernel detaches its tracees and they keep running under the app's UID, along with every
 * `PROOT_TMP_DIR` that talloc would otherwise free. `Process.destroy()` does not help either:
 * proot installs `SIG_IGN` for SIGTERM.
 *
 * Killing leaves-first matters: a parent that is still alive can spawn replacements.
 *
 * The production change these tests catch: removing the recursion, dropping the visited set
 * (infinite loop on a cycle), or yielding root-first.
 */
class SessionProcessTreeTest {

    private fun walk(children: Map<Int, List<Int>>, root: Int): List<Int> =
        descendants(root) { children[it].orEmpty() }

    @Test
    fun `a leaf has no descendants`() {
        assertThat(walk(mapOf(100 to emptyList()), 100)).isEmpty()
    }

    @Test
    fun `an unknown pid has no descendants`() {
        assertThat(walk(emptyMap(), 100)).isEmpty()
    }

    @Test
    fun `direct children are collected`() {
        assertThat(walk(mapOf(100 to listOf(101, 102)), 100)).containsExactly(101, 102).inOrder()
    }

    @Test
    fun `the root itself is never in its own descendant list`() {
        assertThat(walk(mapOf(100 to listOf(100)), 100)).isEmpty()
    }

    @Test
    fun `grandchildren come after their parents breadth first`() {
        val children =
            mapOf(
                100 to listOf(101, 102),
                101 to listOf(201, 202),
                102 to listOf(301),
            )

        assertThat(walk(children, 100)).containsExactly(101, 102, 201, 202, 301).inOrder()
    }

    @Test
    fun `a cycle terminates instead of hanging`() {
        // job control gives each background job its own process group, and /proc children
        // can legitimately appear to loop when a shell re-parents work mid-walk.
        val children = mapOf(100 to listOf(101), 101 to listOf(100, 102), 102 to listOf(101))

        assertThat(walk(children, 100)).containsExactly(101, 102).inOrder()
    }

    @Test
    fun `a duplicate child reached by two parents is visited once`() {
        val children = mapOf(100 to listOf(101, 102), 101 to listOf(200), 102 to listOf(200))

        assertThat(walk(children, 100)).containsExactly(101, 102, 200).inOrder()
    }

    @Test
    fun `a child that already exited is still reported so the caller can ignore the error`() {
        // The sweep runCatch'es each kill, so a pid that died between read and signal is fine.
        assertThat(walk(mapOf(100 to listOf(101, 102)), 100)).containsExactly(101, 102)
    }
}