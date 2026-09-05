package com.xyzterm.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold-start baseline profile generator.
 * Run via: ./gradlew :app:generateBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val baselineRule = BaselineProfileRule()

    @Test
    fun startup() =
        baselineRule.collect(
            packageName = "com.xyzterm.app",
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
        }
}
