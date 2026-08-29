package com.rk.exec

import com.rk.file.child
import com.rk.file.createFileIfNot
import com.rk.file.localDir
import com.rk.file.sandboxDir
import com.rk.file.sandboxHomeDir
import com.rk.utils.application
import com.rk.utils.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        // Route through ShellUtils.runUbuntu so stdout/stderr drain concurrently
        // (the 64KB pipe wedge the old code had to hand-fix) with a bounded wait.
        val result = ShellUtils.runUbuntu("true", timeoutSeconds = 15)
        result.exitCode == 0 && !result.timedOut
    }