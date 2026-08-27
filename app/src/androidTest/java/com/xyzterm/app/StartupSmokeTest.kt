package com.xyzterm.app

import android.app.ActivityManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup smoke test: launches the app the way a user would (launcher intent) and verifies
 * it reaches the foreground without crashing. Catches regressions like the lateinit
 * `SettingsCommand` startup crash — if Application.onCreate throws, the process for our
 * package never appears in the running-app list and this fails.
 *
 * Uses ActivityManager (in-process) to detect the foreground package, NOT UiDevice /
 * adb shell, because the emulator's shell is frequently unresponsive on CI and would
 * produce a flaky false-negative.
 */
@RunWith(AndroidJUnit4::class)
class StartupSmokeTest {

    @Test
    fun appLaunchesWithoutCrash() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pm = context.packageManager

        val launchIntent = pm.getLaunchIntentForPackage(context.packageName)
        checkNotNull(launchIntent) { "app has no launcher intent" }

        context.startActivity(launchIntent)

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val launched = (1..40).any {
            am.getRunningAppProcesses().orEmpty().any { p ->
                p.processName == context.packageName ||
                    p.processName.startsWith("${context.packageName}:")
            }.also { if (!it) Thread.sleep(500) }
        }
        assertTrue("app process for ${context.packageName} did not appear after launch (possible startup crash)", launched)
    }
}
