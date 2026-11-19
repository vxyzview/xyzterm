package com.xyzterm.exec

import android.app.Activity
import android.content.Intent
import com.xyzterm.file.child
import com.xyzterm.file.localDir
import com.xyzterm.file.sandboxDir
import com.xyzterm.file.sandboxHomeDir
import com.xyzterm.utils.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun isTerminalInstalled(): Boolean {
    val rootfs =
        sandboxDir().listFiles()?.filter {
            it.absolutePath != sandboxHomeDir().absolutePath &&
                it.absolutePath != sandboxDir().child("tmp").absolutePath
        } ?: emptyList()

    return localDir().child(".terminal_setup_ok_DO_NOT_REMOVE").exists() &&
        rootfs.isNotEmpty() &&
        sandboxDir().child("etc/pacman.conf").exists()
}

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
        val intent = Intent().setClassName(activity, "com.xyzterm.activities.terminal.Terminal")
        activity.startActivity(intent)
    } catch (_: Exception) {
        toast("Terminal feature is not available in this build")
    }
}
