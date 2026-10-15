package com.rk.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the guard that stops [SessionService] from signalling its own process group.
 *
 * Termux's `TerminalSession.isRunning()` is `mShellPid != -1`, and `mShellPid` is a plain
 * `int` field that stays `0` until `TerminalView.attachSession()` calls `initializeEmulator()`
 * and forks the shell. So a session built by `MkSession.createSession()` and never attached
 * reports itself as running, and `finishIfRunning()` calls `Os.kill(0, SIGKILL)` — and POSIX
 * pid 0 means *every process in the caller's process group*, which includes this app.
 *
 * The production change these tests catch: widening or removing the `pid > 0` check in
 * [shouldKill].
 */
class SessionKillGuardTest {

    @Test
    fun `pid 0 means never spawned and must not be killed`() {
        assertThat(shouldKill(0)).isFalse()
    }

    @Test
    fun `negative pid means already exited and must not be killed`() {
        assertThat(shouldKill(-1)).isFalse()
    }

    @Test
    fun `large negative pid must not be killed`() {
        assertThat(shouldKill(-4096)).isFalse()
    }

    @Test
    fun `positive pid is a live process and must be killed`() {
        assertThat(shouldKill(4242)).isTrue()
    }

    @Test
    fun `pid 1 is a live process and must be killed`() {
        assertThat(shouldKill(1)).isTrue()
    }
}