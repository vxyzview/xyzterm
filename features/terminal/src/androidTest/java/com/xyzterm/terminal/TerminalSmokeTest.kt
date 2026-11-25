package com.xyzterm.terminal

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xyzterm.activities.terminal.Terminal
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke test. Unit tests cannot prove the real Activity starts; this catches crash-on-launch regressions in
 * setup/session wiring.
 */
@RunWith(AndroidJUnit4::class)
class TerminalSmokeTest {
    @Test
    fun launchesWithoutCrash() {
        ActivityScenario.launch(Terminal::class.java).use { scenario ->
            scenario.onActivity { activity -> check(!activity.isFinishing) }
        }
    }
}
