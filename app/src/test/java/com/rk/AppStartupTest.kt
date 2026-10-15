package com.rk

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.rk.activities.settings.SettingsActivity
import com.rk.extension.ActivityProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Pins the wiring that makes dialogs and toasts work at all.
 *
 * `ActivityProvider` holds the resumed Activity, and every `errorDialog` / `dialogRes` / `toast`
 * default argument resolves to it. It only ever gets populated from
 * `onActivityResumed`, which requires `registerActivityLifecycleCallbacks` to have been called.
 * That call was missing, so `currentActivity` was permanently null and ~17 call sites — including
 * the terminal's own setup-failure and timeout dialogs — silently showed nothing.
 *
 * This lives in the `app` module rather than `core:main` because only the app module's merged
 * manifest names `XyzTermApplication` as the Application class, and the registration happens in
 * `App.onCreate`.
 *
 * The production change this catches: removing or moving the
 * `registerActivityLifecycleCallbacks(ActivityProvider)` call out of `App.onCreate`.
 */
@RunWith(RobolectricTestRunner::class)
class AppStartupTest {
    @Test
    fun `app registers the activity lifecycle callback so dialogs have a host`() {
        ApplicationProvider.getApplicationContext<Application>()
        assertThat(ActivityProvider.currentActivity).isNull()

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()

        assertThat(ActivityProvider.currentActivity).isNotNull()

        controller.pause().stop().destroy()
    }

    @Test
    fun `pausing the only activity clears the current activity again`() {
        ApplicationProvider.getApplicationContext<Application>()

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        assertThat(ActivityProvider.currentActivity).isNotNull()

        controller.pause()

        assertThat(ActivityProvider.currentActivity).isNull()

        controller.stop().destroy()
    }
}