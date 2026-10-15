package com.rk.terminal

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins what the restore cap does and does not discard.
 *
 * Capping the restore is what stops a `BROWSABLE` deep-link flood from spawning an unbounded
 * number of proot tracers on one cold start. The first version silently took `take(MAX_RESTORE)`
 * and said nothing, so a user with six saved sessions opened three and had no way to know the
 * other three still existed on disk.
 *
 * These tests pin both halves: what runs, and the fact that the rest are *deferred* rather than
 * deleted.
 *
 * The production change these tests catch: dropping from `saved` instead of deferring, or
 * touching the stored preference.
 */
class DeferredSessionsTest {

    @Test
    fun `nothing is deferred when everything fits`() {
        val saved = listOf("a", "b", "c")

        assertThat(deferredSessions(saved)).isEmpty()
    }

    @Test
    fun `more sessions than the cap are deferred, not lost`() {
        val saved = (1..6).map { "session-$it" }

        val restored = restoreChunks(saved).flatten()
        val deferred = deferredSessions(saved)

        // The two halves must partition the input exactly: nothing dropped on the floor.
        assertThat(restored).containsExactlyElementsIn(saved.take(MAX_RESTORE))
        assertThat(deferred).containsExactlyElementsIn(saved.drop(MAX_RESTORE))
        assertThat(restored).containsNoneIn(deferred)
        assertThat(restored.size + deferred.size).isEqualTo(saved.size)
    }

    @Test
    fun `restored sessions keep the saved order, so the active one is reachable`() {
        val saved = listOf("z", "y", "x", "w")

        assertThat(restoreChunks(saved).flatten()).containsExactly("z", "y", "x").inOrder()
    }

    @Test
    fun `a large saved list defers everything past the cap`() {
        val saved = (1..50).map { "session-$it" }

        assertThat(deferredSessions(saved)).hasSize(50 - MAX_RESTORE)
    }

    @Test
    fun `an empty saved list defers nothing`() {
        assertThat(deferredSessions(emptyList())).isEmpty()
    }
}