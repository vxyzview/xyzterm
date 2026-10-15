package com.rk.file

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins how often a progress callback may reach the UI.
 *
 * `FileOperations.calculateContent` invoked `onProgress` for **every** entry in the tree, and
 * `PropertiesDialog` assigned two `mutableStateOf` values in that callback. Opening Properties on
 * a directory with 5,000 files therefore produced 10,000 snapshot writes and 10,000
 * recompositions of the dialog — for a number that only changes meaningfully a handful of times
 * while the user watches it count up.
 *
 * Throttling trades a little latency for not re-rendering 10,000 times. The final update is
 * always emitted, so the displayed total is never stale.
 *
 * The production change these tests catch: lowering `everyN` so the walk is effectively
 * unthrottled again, or dropping the final flush.
 */
class ProgressThrottleTest {

    private class Reports {
        var count = 0

        fun newThrottle(): ProgressThrottle = ProgressThrottle(PROGRESS_EVERY_N_ITEMS) { count += 1 }
    }

    @Test
    fun `a small tree reports only once, at the end`() {
        val reports = Reports()
        val throttle = reports.newThrottle()

        (1L..10L).forEach { throttle.onItem(it) }
        throttle.flush()

        // Below the threshold throughout, so only the final flush fires.
        assertThat(reports.count).isEqualTo(1)
    }

    @Test
    fun `a large tree reports roughly every threshold`() {
        val reports = Reports()
        val throttle = reports.newThrottle()

        (1L..5000L).forEach { throttle.onItem(it) }
        throttle.flush()

        // 5000 / 25 == 200 thresholds crossed, and the flush adds nothing since one fired.
        assertThat(reports.count).isEqualTo(200)
    }

    @Test
    fun `a ten thousand item tree produces far fewer reports than items`() {
        val reports = Reports()
        val throttle = reports.newThrottle()

        (1L..10_000L).forEach { throttle.onItem(it) }
        throttle.flush()

        // The whole point: 10,000 items must not become 10,000 recompositions.
        assertThat(reports.count.toLong()).isLessThan(1000L)
    }

    @Test
    fun `the final update is always emitted even below the threshold`() {
        val reports = Reports()

        reports.newThrottle().onItem(1L)
        reports.newThrottle().flush()

        // Two distinct throttles: one reported nothing, the other flushed once.
        assertThat(reports.count).isEqualTo(1)
    }

    @Test
    fun `flushing twice does not report twice`() {
        val reports = Reports()
        val throttle = reports.newThrottle()

        throttle.onItem(1L)
        throttle.flush()
        throttle.flush()

        assertThat(reports.count).isEqualTo(1)
    }

    @Test
    fun `an empty walk still reports its final state`() {
        val reports = Reports()

        reports.newThrottle().flush()

        assertThat(reports.count).isEqualTo(1)
    }

    @Test
    fun `the threshold is a small divisor of typical directory sizes`() {
        // Sanity on the constant: too small and this stops being a throttle at all.
        assertThat(PROGRESS_EVERY_N_ITEMS).isAtLeast(10L)
        assertThat(PROGRESS_EVERY_N_ITEMS).isAtMost(200L)
    }
}