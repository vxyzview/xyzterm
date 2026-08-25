package com.rk.exec

import com.rk.file.TERMINAL_SETUP_OK_MARKER
import com.rk.file.localDir
import com.rk.file.child
import com.rk.file.rootfsFiles

fun isTerminalInstalled(): Boolean {
    return localDir().child(TERMINAL_SETUP_OK_MARKER).exists() && rootfsFiles().isNotEmpty()
}
