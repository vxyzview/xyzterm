package com.rk.utils

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.rk.extension.ActivityProvider
import com.rk.resources.Res
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/**
 * Pins the behaviour that made every error message in the app invisible.
 *
 * `toast()` resolved its context from `ActivityProvider.currentActivity`, but nothing in the
 * codebase ever called `registerActivityLifecycleCallbacks(ActivityProvider)` — so the reference
 * stayed null forever and the else-branch only wrote to logcat. Errors raised from a service, a
 * background job, or any screen that had already paused produced no user-visible output.
 */
@RunWith(RobolectricTestRunner::class)
class ToastWithoutActivityTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        application = app
        // Utils.kt's top-level `binaryExtensions` val touches BuiltinFileType, whose
        // enum entries resolve their titles through Res.getString() -> Res.application!!.
        // App.onCreate assigns both globals in the same breath; mirroring that here
        // is what keeps the first call into any UtilsKt function from NPE-ing.
        Res.application = app
    }

    @Test
    fun `toast still reaches the user when no activity is resumed`() {
        assertThat(ActivityProvider.currentActivity).isNull()

        toast("session ended")
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo("session ended")
    }

    @Test
    fun `a blank message shows no toast`() {
        toast("   ")
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(ShadowToast.getTextOfLatestToast()).isNull()
    }

    @Test
    fun `a cancelled job message shows no toast`() {
        toast("Job was cancelled")
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(ShadowToast.getTextOfLatestToast()).isNull()
    }
}