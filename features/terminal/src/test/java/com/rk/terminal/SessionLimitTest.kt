package com.rk.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the bounds on how many sessions the app will create at once.
 *
 * `restoreSessions` did `saved.map { async { createSession(...) } }.awaitAll()` — one proot tracer
 * plus bash plus a private tmp dir per saved session, all spawned simultaneously. ptrace is
 * CPU-expensive and each pair is tens of MB, so a user who left a dozen sessions around hit the
 * watchdog or the low-memory killer on cold start.
 *
 * The input is attacker-influenced too: `Terminal` is `exported="true"` with a `BROWSABLE` intent
 * filter, so `xyzterm://session/<anything>` minted entries into that map for free, with no cap
 * anywhere.
 *
 * The production change these tests catch: raising [MAX_RESTORE] or [MAX_CONCURRENT_RESTORE],
 * removing the cap, or letting `chunked` reorder the list.
 */
class SessionLimitTest {

    @Test
    fun `no saved sessions means nothing to restore`() {
        assertThat(restoreChunks(emptyList())).isEmpty()
    }

    @Test
    fun `one saved session is a single chunk`() {
        assertThat(restoreChunks(listOf("a"))).containsExactly(listOf("a"))
    }

    @Test
    fun `three saved sessions fit in one chunk at the concurrency limit`() {
        assertThat(restoreChunks(listOf("a", "b", "c"))).containsExactly(listOf("a", "b", "c"))
    }

    @Test
    fun `more than the limit spawns nothing extra`() {
        val plan = restoreChunks((1..50).map { "session-$it" })

        assertThat(plan.flatten()).hasSize(MAX_RESTORE)
    }

    @Test
    fun `a large set is chunked so no more than the concurrency limit spawn at once`() {
        val plan = restoreChunks((1..50).map { "session-$it" })

        assertThat(plan.map { it.size }).containsExactly(2, 1).inOrder()
        plan.forEach { assertThat(it.size).isAtMost(MAX_CONCURRENT_RESTORE) }
    }

    @Test
    fun `restore order follows the saved order`() {
        val plan = restoreChunks(listOf("z", "y", "x", "w"))

        assertThat(plan.flatten()).containsExactly("z", "y", "x").inOrder()
    }

    @Test
    fun `the cap keeps the first saved sessions, not an arbitrary subset`() {
        val plan = restoreChunks(listOf("first", "second", "third", "fourth"))

        assertThat(plan.flatten()).containsExactly("first", "second", "third").inOrder()
    }

    @Test
    fun `a deep link can open a session below the cap`() {
        assertThat(canCreateSession(currentCount = 0)).isTrue()
        assertThat(canCreateSession(currentCount = MAX_SESSIONS - 1)).isTrue()
    }

    @Test
    fun `a deep link is refused at the cap`() {
        assertThat(canCreateSession(currentCount = MAX_SESSIONS)).isFalse()
    }

    @Test
    fun `a deep link is refused past the cap`() {
        assertThat(canCreateSession(currentCount = MAX_SESSIONS + 10)).isFalse()
    }

    @Test
    fun `a normal absolute guest cwd is accepted`() {
        assertThat(isSafeGuestCwd("/home")).isTrue()
    }

    @Test
    fun `a relative cwd is refused`() {
        assertThat(isSafeGuestCwd("home/user")).isFalse()
    }

    @Test
    fun `a cwd escaping the rootfs is refused`() {
        assertThat(isSafeGuestCwd("/home/../../etc")).isFalse()
    }

    @Test
    fun `a cwd containing a parent hop anywhere is refused`() {
        assertThat(isSafeGuestCwd("/home/user/..")).isFalse()
    }

    @Test
    fun `an empty cwd is refused`() {
        assertThat(isSafeGuestCwd("")).isFalse()
    }
}