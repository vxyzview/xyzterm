package com.rk.exec

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins which custom-bind paths are allowed.
 *
 * `sandbox.sh` accumulates every bind into one string and expands it unquoted:
 *
 *     bind_if_exists() { ... ARGS="$ARGS -b $1"; }
 *     exec $PROOT $ARGS /bin/bash ...
 *
 * `/storage/ABCD-1234/My Drive` — the standard Android SD-card volume label —
 * word-splits into `-b /storage/ABCD-1234/My`, proot's option loop breaks on the
 * leftover argument, and the tracer exits before bash starts. Every session then
 * fails to launch, with the only recovery being Settings -> Custom Binds -> delete
 * the entry by hand.
 *
 * `:` is rejected for a separate reason: proot's `handle_option_b` splits the bind
 * spec on it.
 *
 * The production change these tests catch: relaxing `UNSAFE`, or making
 * `isWellFormedHost`/`isValidGuestPath` permissive again.
 */
@RunWith(RobolectricTestRunner::class)
class UserBindingsValidationTest {

    @Test
    fun `a host path with a space is rejected`() {
        assertThat(UserBindings.isWellFormedHost("/storage/ABCD-1234/My Drive")).isFalse()
    }

    @Test
    fun `a host path with a tab is rejected`() {
        assertThat(UserBindings.isWellFormedHost("/storage/emulated/0\tbackup")).isFalse()
    }

    @Test
    fun `a host path with a colon is rejected`() {
        assertThat(UserBindings.isWellFormedHost("/storage/emulated/0:backup")).isFalse()
    }

    @Test
    fun `a host path with a glob character is rejected`() {
        assertThat(UserBindings.isWellFormedHost("/storage/*/Download")).isFalse()
    }

    @Test
    fun `an ordinary host path is accepted`() {
        assertThat(UserBindings.isWellFormedHost("/storage/emulated/0")).isTrue()
    }

    @Test
    fun `a host path containing spaces mid path is rejected`() {
        assertThat(UserBindings.isWellFormedHost("/storage/emulated/0/My Files")).isFalse()
    }

    @Test
    fun `a guest path with a colon is rejected`() {
        assertThat(UserBindings.isValidGuestPath("/mnt/data:x")).isFalse()
    }

    @Test
    fun `an ordinary guest path is accepted`() {
        assertThat(UserBindings.isValidGuestPath("/mnt/work")).isTrue()
    }

    @Test
    fun `a guest path must be absolute`() {
        assertThat(UserBindings.isValidGuestPath("mnt/work")).isFalse()
    }

    @Test
    fun `an empty guest path is rejected`() {
        assertThat(UserBindings.isValidGuestPath("/")).isFalse()
    }

    @Test
    fun `decode drops only the malformed entry and keeps its valid siblings`() {
        val json =
            UserBindings.encode(
                listOf(
                    Binding("/storage/emulated/0/Download", null),
                    Binding("/storage/ABCD-1234/My Drive", null),
                    Binding("/storage/emulated/0/DCIM", "/mnt/dcim"),
                )
            )

        val decoded = UserBindings.decode(json)

        assertThat(decoded.map { it.outside })
            .containsExactly("/storage/emulated/0/Download", "/storage/emulated/0/DCIM")
            .inOrder()
    }

    @Test
    fun `a valid binding survives an encode decode round trip`() {
        val original = Binding("/storage/emulated/0/Download", "/mnt/dl")

        val decoded = UserBindings.decode(UserBindings.encode(listOf(original)))

        assertThat(decoded).hasSize(1)
        assertThat(decoded.first().outside).isEqualTo("/storage/emulated/0/Download")
        assertThat(decoded.first().inside).isEqualTo("/mnt/dl")
    }
}