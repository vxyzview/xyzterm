package com.xyzterm.exec

import com.xyzterm.file.child
import com.xyzterm.file.localDir
import com.xyzterm.file.sandboxDir
import com.xyzterm.file.sandboxHomeDir

fun isTerminalInstalled(): Boolean {
    val rootfs =
        sandboxDir().listFiles()?.filter {
            it.absolutePath != sandboxHomeDir().absolutePath &&
                it.absolutePath != sandboxDir().child("tmp").absolutePath
        } ?: emptyList()

    return localDir().child(".terminal_setup_ok_DO_NOT_REMOVE").exists() && rootfs.isNotEmpty()
}
