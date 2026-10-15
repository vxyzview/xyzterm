package com.rk.crashhandler

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins which crashes the handler is allowed to swallow.
 *
 * The old check substring-matched `ex.stackTrace.contentToString()` against
 * `android.view.View$BaseSavedState`. That matched any uncaught exception whose
 * frames merely passed through view-state save/restore — a very common path — so a
 * large class of real crashes was dropped with only a `Log.w`: no crash screen, no
 * crash.log, and the app continuing on corrupted state with nobody knowing.
 *
 * It also built the whole flattened stack trace before deciding, on every
 * uncaught exception.
 *
 * The production change these tests catch: widening [CrashHandler.shouldIgnore] back to matching
 * the stack trace, or dropping the `IllegalStateException` / message-name requirements.
 */
class CrashFilterTest {

    private val viewSavedState = "android.view.View\$BaseSavedState"
    private val scrollSavedState = "android.widget.HorizontalScrollView\$SavedState"

    @Test
    fun `a NullPointerException is never ignored`() {
        assertThat(CrashHandler.shouldIgnore(NullPointerException("boom"))).isFalse()
    }

    @Test
    fun `an IllegalStateException without the savedstate name is not ignored`() {
        assertThat(CrashHandler.shouldIgnore(IllegalStateException("something else broke"))).isFalse()
    }

    @Test
    fun `the framework view savedstate complaint is ignored`() {
        val ex = IllegalStateException("Cannot restore state of $viewSavedState")

        assertThat(CrashHandler.shouldIgnore(ex)).isTrue()
    }

    @Test
    fun `the framework scrollview savedstate complaint is ignored`() {
        val ex = IllegalStateException("Cannot restore state of $scrollSavedState")

        assertThat(CrashHandler.shouldIgnore(ex)).isTrue()
    }

    @Test
    fun `a crash that merely passes through savedstate frames is not ignored`() {
        // The exact shape the old stack-trace match swallowed: a real, different
        // failure that happened to unwind through Android's state-restoration code.
        val ex = IllegalStateException("socket closed")
        ex.stackTrace =
            arrayOf(
                StackTraceElement(viewSavedState, "onRestoreInstanceState", "View.java", 1234),
                StackTraceElement("com.rk.terminal.SessionService", "restore", "SessionService.kt", 98),
            )

        assertThat(CrashHandler.shouldIgnore(ex)).isFalse()
    }

    @Test
    fun `a savedstate frame with no message is not ignored`() {
        val ex = IllegalStateException()
        ex.stackTrace = arrayOf(StackTraceElement(viewSavedState, "onSaveInstanceState", "View.java", 1))

        assertThat(CrashHandler.shouldIgnore(ex)).isFalse()
    }
}