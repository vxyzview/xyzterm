package com.rk.exec

import android.app.Activity
import android.content.Intent
import com.rk.utils.application
import com.rk.utils.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun isTerminalInstalled(): Boolean = ProotSandboxPaths(application!!).isInstalled()

suspend fun isTerminalWorking(): Boolean =
    withContext(Dispatchers.IO) {
        // Route through ShellUtils.runUbuntu so stdout/stderr drain concurrently
        // (the 64KB pipe wedge the old code had to hand-fix) with a bounded wait.
        val result = ShellUtils.runUbuntu("true", timeoutSeconds = 15)
        result.exitCode == 0 && !result.timedOut
    }

fun launchTerminal(activity: Activity, terminalCommand: TerminalCommand) {
    pendingCommand = terminalCommand
    try {
        val intent = Intent().setClassName(activity, "com.rk.activities.terminal.Terminal")
        activity.startActivity(intent)
    } catch (_: Exception) {
        toast("Terminal feature is not available in this build")
    }
}
