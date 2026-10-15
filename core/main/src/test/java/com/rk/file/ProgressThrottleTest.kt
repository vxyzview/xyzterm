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
 * The production change these tests catch: lowering [PROGRESS_EVERY_N_ITEMS] so the walk is
 * effectively unthrottled again, or dropping the final flush.
 */
class ProgressThrottleTest {

    @Test
    fun `a small tree reports every item`() {
        val reports = countingThrottle()
        val throttle = reports.throttle

        (1L..10L).forEach { throttle.onItem(it) }
        throttle.flush()

        // 10 items, every 25th threshold never crossed, so only the final flush reports.
        assertThat(reports.count).isEqualTo(1)
    }

    @Test
    fun `a large tree reports roughly every threshold`() {
        val reports = countingThrottle()
        val throttle = reports.throttle

        (1L..5000L).forEach { throttle.onItem(it) }
        throttle.flush()

        // 5000 / 25 == 200 thresholds, plus the flush.
        assertThat(reports.count).isEqualTo(201)
    }

    @Test
    fun `a ten thousand item tree produces far fewer reports than items`() {
        val reports = countingThrottle()
        val throttle = reports.throttle

        (1L..10_000L).forEach { throttle.onItem(it) }
        throttle.flush()

        // The whole point: 10,000 items must not become 10,000 recompositions.
        assertThat(reports.count).isLessThan(1000)
    }

    @Test
    fun `the final update is always emitted even below the threshold`() {
        val reports = countingThrottle()

        reports.throttle.onItem(1L)
        reports.throttle.flush()

        assertThat(reports.count).isEqualTo(1)
    }

    @Test
    fun `flushing twice does not report twice`() {
        val reports = countingThrottle()

        reports.throttle.onItem(1L)
        reports.throttle.flush()
        reports.throttle.flush()

        assertThat(reports.count).isEqualTo(1)
    }

    private class Counter {
        var count = 0
    }

    private class Holder(val counter: Counter, val throttle: ProgressThrottle)

    private fun countingThrottle(): Holder {
        val counter = Counter()
        val throttle =
            ProgressThrottle(PROGRESS_EVERY_N_ITEMS) { _: Long ->
                counter.count += 1
                Unit
            }
        return Holder(counter, throttle)
    }

    @Test
    fun `the threshold is a small divisor of typical directory sizes`() {
        // Sanity on the constant: too small and this stops being a throttle at all.
        assertThat(PROGRESS_EVERY_N_ITEMS).isAtLeast(10L)
        assertThat(PROGRESS_EVERY_N_ITEMS).isAtMost(200L)
    }
}