package com.xyzterm.app.macrobenchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Cold-start measurement.
 *
 * - [startupPartial] runs with the checked-in baseline profile (the
 *   configuration users get): this is the number to watch.
 * - [startupNone] runs fully interpreted: the gap between the two
 *   quantifies what the baseline profile buys.
 *
 * Run via the macrobenchmark workflow (emulator) — not locally.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.JVM)
class ColdStartupBenchmark {
    @get:Rule val benchmarkRule = MacrobenchmarkRule()

    // Partial FIRST: on shared CI runners the device slows over a run
    // (thermal/migration drift), which would otherwise flatter whichever
    // test runs first. If the gap flips with order, the numbers are noise.
    @Test
    fun startupPartial() = startup(CompilationMode.Partial())

    @Test
    fun startupNone() = startup(CompilationMode.None())

    private fun startup(compilationMode: CompilationMode) =
        benchmarkRule.measureRepeated(
            packageName = "com.xyzterm.app",
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
        }
}
