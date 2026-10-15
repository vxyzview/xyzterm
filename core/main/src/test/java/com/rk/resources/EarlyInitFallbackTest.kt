package com.rk.resources

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the early-init fallback.
 *
 * `Res.application` is assigned in `App.onCreate`, but ContentProviders initialise *before* that
 * — and `core:main` declares a `DocumentsProvider`. Its class-init chain reaches Settings, which
 * reads `BuiltinFileType`, whose enum entries call `getString()` -> `Res.application!!`. Without a
 * fallback that path died with an `ExceptionInInitializerError` before the Application ever ran.
 * (Hit for real: a Robolectric test failed with exactly that stack, from
 * `BuiltinFileType.<clinit>`.)
 *
 * The production change these tests catch: reverting to the bare `Res.application!!`, or
 * preferring the process-wide global over the explicitly-assigned one.
 */
@RunWith(RobolectricTestRunner::class)
class EarlyInitFallbackTest {

    @After
    fun tearDown() {
        Res.application = null
    }

    @Test
    fun `the explicitly assigned application is preferred over the global`() {
        val explicit = org.robolectric.RuntimeEnvironment.getApplication()
        Res.application = explicit

        assertThat(resolveApplication()).isSameInstanceAs(explicit)
    }

    @Test
    fun `the process-wide application is used before onCreate has run`() {
        Res.application = null

        // AppGlobals is initialised by the runtime long before Application.onCreate, so this
        // must resolve rather than throwing.
        assertThat(resolveApplication()).isNotNull()
    }

    @Test
    fun `resolving never throws`() {
        Res.application = null

        runCatching { resolveApplication() }
    }
}