package com.rk.terminal

import com.rk.file.child
import com.rk.file.createFileIfNot
import com.rk.file.localBinDir
import com.rk.file.sandboxDir
import com.rk.utils.application

// ponytail: one-shot per process — scripts only change at app update (which
// restarts the process), so re-reading+comparing 5 assets on every session
// spawn is pure waste. Reset to false if manual file deletion ever matters.
private var terminalFilesInstalled = false

fun setupTerminalFiles() {
    if (terminalFilesInstalled) return
    if (sandboxDir().exists().not() || localBinDir().exists().not()) return

    setupAssetFile("termux-x11")

    val internalFiles = listOf("init", "sandbox", "setup", "utils")
    internalFiles.forEach { setupAssetFile(it) }

    terminalFilesInstalled = true
}

/**
 * Installs a bundled script into [localBinDir], rewriting it when the shipped
 * copy changed (app update) so script fixes reach existing installs instead of
 * only fresh ones. Exec bit is set here — the one-time chmod in sandbox.sh
 * predates rewrites and would leave updated scripts non-executable.
 */
fun setupAssetFile(fileName: String) {
    val assetContent = application!!.assets.open("terminal/$fileName.sh").bufferedReader().use { it.readText() }

    with(localBinDir().child(fileName)) {
        parentFile?.mkdir()
        if (!exists() || readText() != assetContent) {
            createFileIfNot()
            writeText(assetContent)
            runCatching { setExecutable(true) }.onFailure { it.printStackTrace() }
        }
    }
}

