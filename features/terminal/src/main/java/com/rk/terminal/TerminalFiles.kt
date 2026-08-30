package com.rk.terminal

import com.rk.exec.ProotSandboxPaths
import com.rk.file.createFileIfNot
import com.rk.utils.application
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ponytail: one-shot per process — scripts only change at app update (which
// restarts the process), so re-reading+comparing 5 assets on every session
// spawn is pure waste. Reset to false if manual file deletion ever matters.
// Mutex (not a plain var) because setupTerminalFiles runs on Dispatchers.IO for
// every session — including N parallel restores — and the old non-atomic guard
// let concurrent first-time spawns all pass and double-write the same files.
private val installMutex = Mutex()
private var terminalFilesInstalled = false

suspend fun setupTerminalFiles() {
    if (terminalFilesInstalled) return
    installMutex.withLock {
        if (terminalFilesInstalled) return
        val paths = ProotSandboxPaths(application!!)
        if (paths.sandboxRoot.exists().not() || paths.sandboxBin.exists().not()) return

        setupAssetFile("termux-x11")

        val internalFiles = listOf("init", "sandbox", "setup", "utils")
        internalFiles.forEach { setupAssetFile(it) }

        terminalFilesInstalled = true
    }
}

/**
 * Installs a bundled script into [ProotSandboxPaths.sandboxBin], rewriting it when the shipped
 * copy changed (app update) so script fixes reach existing installs instead of
 * only fresh ones. Exec bit is set here — the one-time chmod in sandbox.sh
 * predates rewrites and would leave updated scripts non-executable.
 */
fun setupAssetFile(fileName: String) {
    val assetContent = application!!.assets.open("terminal/$fileName.sh").bufferedReader().use { it.readText() }

    with(ProotSandboxPaths(application!!).sandboxBin.resolve(fileName)) {
        parentFile?.mkdir()
        if (!exists() || readText() != assetContent) {
            createFileIfNot()
            writeText(assetContent)
            runCatching { setExecutable(true) }.onFailure { it.printStackTrace() }
        }
    }
}

