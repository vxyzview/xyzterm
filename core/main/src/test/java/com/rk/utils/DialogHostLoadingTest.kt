package com.rk.utils

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/**
 * Pins the loading-dialog dedupe, and where it lives.
 *
 * It used to sit at the call site in `LoadingPopup.show()`, as
 * `if (DialogHost.loadings.none { it.id == id }) pushLoading(...)`. That is a
 * check-then-act: two callers can both observe "absent" and both push, so two
 * identical spinners end up in the list under the same id.
 *
 * The failure mode is not just a duplicate. `DialogHost.remove(id)` does
 * `loadings.removeAll { it.id == id }` — it takes out *every* match — so the
 * moment the first caller finished, the second caller's spinner disappeared
 * too, with its work still in flight.
 *
 * These tests catch moving the guard back out to the call site, and a future
 * `pushLoading` that quietly stops deduping.
 */
class DialogHostLoadingTest {

    private fun request(id: Long) = DialogRequest(id = id, createdAt = 0L, cancelable = false) {}

    @After
    fun tearDown() {
        DialogHost.loadings.clear()
    }

    @Test
    fun `a first push shows`() {
        DialogHost.pushLoading(request(1))

        assertThat(DialogHost.loadings).hasSize(1)
    }

    @Test
    fun `pushing the same id twice shows one spinner`() {
        DialogHost.pushLoading(request(1))
        DialogHost.pushLoading(request(1))

        assertThat(DialogHost.loadings).hasSize(1)
    }

    @Test
    fun `distinct ids each get their own spinner`() {
        DialogHost.pushLoading(request(1))
        DialogHost.pushLoading(request(2))

        assertThat(DialogHost.loadings).hasSize(2)
    }

    @Test
    fun `removing one id does not take out a duplicate that was never added`() {
        DialogHost.pushLoading(request(1))
        DialogHost.pushLoading(request(1))
        DialogHost.pushLoading(request(2))

        DialogHost.remove(1)

        // The regression: two entries under id 1 would mean remove(1) also
        // silently killed spinner 2.
        assertThat(DialogHost.loadings.map { it.id }).containsExactly(2)
    }

    @Test
    fun `an id can be pushed again after being removed`() {
        DialogHost.pushLoading(request(1))
        DialogHost.remove(1)
        DialogHost.pushLoading(request(1))

        assertThat(DialogHost.loadings.map { it.id }).containsExactly(1)
    }
}