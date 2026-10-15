package com.xyzterm.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline profile generator.
 *
 * The profile this replaces was hand-written and read `HSPLcom/rk/settings/**->**(**)**`,
 * `HSPLcom/termux/terminal/**->**(**)**`, `HSPLcom/rk/exec/**->**(**)**`. A profile that says
 * "all of these packages are hot" gets all of it AOT-compiled into the dex, which enlarges the
 * install and the amount ART must verify at launch for a marginal gain. Profiles pay off when
 * they are tight around an actual journey.
 *
 * So this collects a real journey: open the app and wait for the terminal surface, which
 * exercises SessionService.onCreate, MkSession.prepareEnvironment and the proot fork inside
 * initializeEmulator. `startActivityAndWait()` alone returns at first frame and covers none of
 * that.
 *
 * Run via: ./gradlew :app:generateBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val baselineRule = BaselineProfileRule()

    @Test
    fun startup() {
        baselineRule.collect(
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            // Collect through the shell-spawn path too, which is where this app spends its
            // cold-start budget. Proceed even if the node is absent -- a fresh emulator may
            // be showing the install prompt -- so the profile still covers the cold path.
            runCatching { device.wait(Until.hasObject(By.desc("terminal output")), SHELL_TIMEOUT_MS) }
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.xyzterm.app"
        const val SHELL_TIMEOUT_MS = 30_000L
    }
}