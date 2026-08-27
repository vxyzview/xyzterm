package com.xyzterm.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup smoke test: launches the app the way a user would (launcher intent) and
 * verifies it reaches the foreground without crashing. Catches regressions like the
 * lateinit `SettingsCommand` startup crash — if Application.onCreate throws, the app
 * never becomes the resumed foreground package and this fails.
 */
@RunWith(AndroidJUnit4::class)
class StartupSmokeTest {

    @Test
    fun appLaunchesWithoutCrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(context.packageName)
        assertNotNull("app has no launcher intent", launchIntent)

        context.startActivity(launchIntent)

        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Give the app a moment to start; if onCreate crashed, the foreground package
        // will not be ours.
        var resumed = false
        repeat(20) {
            if (device.currentPackageName == context.packageName) {
                resumed = true
                return@repeat
            }
            Thread.sleep(500)
        }
        assert(resumed) { "app did not become the foreground package after launch (possible startup crash)" }
    }
}
