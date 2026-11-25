package com.xyzterm.app.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures the "rootfs_install" trace section emitted by RootfsInstaller on a
 * genuinely fresh install. Each iteration clears app data first, otherwise the
 * installer sees an existing rootfs and short-circuits.
 *
 * Run via the macrobenchmark workflow (emulator) — not locally.
 */
@RunWith(AndroidJUnit4::class)
class FirstLaunchInstallBenchmark {
    @get:Rule val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun rootfsInstall() =
        benchmarkRule.measureRepeated(
            packageName = "com.xyzterm.app",
            metrics = listOf(TraceSectionMetric("rootfs_install")),
            compilationMode = CompilationMode.None(),
            iterations = 5,
            setupBlock = {
                InstrumentationRegistry.getInstrumentation()
                    .uiAutomation
                    .executeShellCommand("pm clear com.xyzterm.app")
            },
        ) {
            startActivityAndWait()
        }
}
