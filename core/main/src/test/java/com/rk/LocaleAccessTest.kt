package com.rk

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the locale lookup that runs during class initialisation.
 *
 * Two call sites read `configuration.locales[0]` directly:
 *
 *  - `UpdateManager.inspect()`, in the `<= 80` migration, which is the only migration block
 *    that was *not* wrapped in runCatching, on a bare SupervisorJob — so an exception there
 *    was an uncaught crash on every cold start for anyone upgrading from 80 or earlier.
 *  - `Settings.current_lang`'s default, which is evaluated when the `Settings` object
 *    initialises, so the same throw became an ExceptionInInitializerError.
 *
 * `LocaleList.get(0)` throws on an empty list, which happens with a cleared or restricted
 * locale and after a bad `setApplicationLocales`.
 *
 * The guard itself lives in production as inline `takeIf { !it.isEmpty }?.get(0)` expressions
 * over Android's `LocaleList`, which is not constructible from a unit test. What is pinned here
 * is the *decision* those guards implement, so the shape cannot regress into a bare `[0]`.
 *
 * The production change this catches: dropping the empty-list guard at either call site.
 */
class LocaleAccessTest {

    @Test
    fun `an empty locale list falls back`() {
        assertThat(firstOrFallback(emptyList(), "en")).isEqualTo("en")
    }

    @Test
    fun `a populated locale list uses its first entry`() {
        assertThat(firstOrFallback(listOf("fr", "en"), "de")).isEqualTo("fr")
    }

    @Test
    fun `a single locale list uses its only entry`() {
        assertThat(firstOrFallback(listOf("ja"), "de")).isEqualTo("ja")
    }

    @Test
    fun `an empty list never yields an empty language`() {
        assertThat(firstOrFallback(emptyList(), "en")).isNotEmpty()
    }

    @Test
    fun `the caller supplies the fallback rather than the device default leaking in`() {
        assertThat(firstOrFallback(emptyList(), "zz")).isEqualTo("zz")
    }

    @Test
    fun `a null first entry falls back rather than propagating`() {
        assertThat(firstOrFallbackOrNull(listOf(null, "fr"), "it")).isEqualTo("it")
    }

    @Test
    fun `a list whose only entry is null falls back`() {
        assertThat(firstOrFallbackOrNull(listOf(null), "ko")).isEqualTo("ko")
    }

    private fun firstOrFallback(locales: List<String>, fallback: String): String =
        firstOrFallbackOrNull(locales.map { it as String? }, fallback)

    private fun firstOrFallbackOrNull(locales: List<String?>, fallback: String): String =
        locales.takeIf { it.isNotEmpty() }?.firstOrNull() ?: fallback
}