package com.rk.exec

import android.app.Activity
import android.content.Intent
import com.rk.file.child
import com.rk.file.createFileIfNot
import com.rk.file.localDir
import com.rk.file.sandboxDir
import com.rk.file.sandboxHomeDir
import com.rk.utils.application
import com.rk.utils.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

fun isTerminalInstalled(): Boolean {
    val rootfs =
        sandboxDir().listFiles()?.filter {
            it.absolutePath != sandboxHomeDir().absolutePath &&
                it.absolutePath != sandboxDir().child("tmp").absolutePath
        } ?: emptyList()

    return localDir().child(".terminal_setup_ok_DO_NOT_REMOVE").exists() && rootfs.isNotEmpty()
}

suspend fun isTerminalWorking(): Boolean =
    withContext(Dispatchers.IO) {
        val process = ubuntuProcess(command = arrayOf("true"))
        // ponytail: drain stderr so proot's startup errors can't wedge waitFor() once
        // the 64KB pipe fills; bound the wait so a hung proot can't hang the coroutine.
        val err = async { process.readStderr() }
        val ok = process.waitFor(15, TimeUnit.SECONDS)
        err.await()
        ok && process.exitValue() == 0
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
