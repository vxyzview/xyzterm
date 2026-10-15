package com.xyzterm.app.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Cold-start measurement.
 *
 * - [startupPartial] runs with the checked-in baseline profile (the configuration users get):
 *   this is the number to watch.
 * - [startupNone] runs fully interpreted: the gap between the two quantifies what the profile
 *   buys.
 *
 * Two metrics, not one. `StartupTimingMetric` alone says nothing about the frame cost after the
 * activity appears, and this app's cold start is dominated by work that happens *after* first
 * frame: SessionService.onCreate, MkSession.prepareEnvironment, and the proot fork inside
 * `initializeEmulator`. A slow number on a fast metric is exactly the case that metric hides.
 *
 * `killProcess()` in setup is not optional. Without it, sessions accumulate across the 10
 * iterations and each one leaves a live proot tracer holding tens of MB.
 *
 * Run via the macrobenchmark workflow (emulator) -- not locally.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.JVM)
class ColdStartupBenchmark {
    @get:Rule val benchmarkRule = MacrobenchmarkRule()

    // Partial FIRST: on shared CI runners the device slows over a run (thermal/migration drift),
    // which would otherwise flatter whichever test runs first. If the gap flips with order, the
    // numbers are noise.
    @Test
    fun startupPartial() = startup(CompilationMode.Partial())

    @Test
    fun startupNone() = startup(CompilationMode.None())

    private fun startup(compilationMode: CompilationMode) =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = {
                // Leave nothing behind between iterations.
                runCatching { killProcess() }
                pressHome()
            },
        ) {
            startActivityAndWait()
        }

    /**
     * The shell-spawn journey.
     *
     * On a fresh emulator there is no rootfs, so [startup] measures the "Install Ubuntu"
     * button -- not the app. This variant waits for the terminal view itself, which is the
     * path a user actually takes once installed.
     *
     * WARM, not COLD: the process is already alive and what is being measured is the cost of
     * building a session and forking proot, which is what dominates a warm start in practice.
     */
    @Test
    fun shellSpawn() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 10,
            setupBlock = {
                runCatching { killProcess() }
                pressHome()
            },
        ) {
            startActivityAndWait()
            // Poll for the terminal surface rather than sleeping a fixed interval: a fixed
            // sleep measures the sleep, and on a slow runner it ends the iteration before the
            // shell has even forked.
            device.wait(Until.hasObject(By.desc("terminal output")), SHELL_TIMEOUT_MS)
        }

    private companion object {
        const val TARGET_PACKAGE = "com.xyzterm.app"

        /** proot startup is hundreds of ms on a loaded runner; be generous before failing. */
        const val SHELL_TIMEOUT_MS = 30_000L
    }
}