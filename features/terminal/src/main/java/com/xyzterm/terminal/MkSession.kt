package com.xyzterm.terminal

import android.app.Activity
import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.xyzterm.exec.SandboxEnv
import com.xyzterm.exec.pendingCommand
import com.xyzterm.file.child
import com.xyzterm.file.childSafe
import com.xyzterm.file.localBinDir
import com.xyzterm.file.localDir
import com.xyzterm.file.sandboxHomeDir
import com.xyzterm.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MkSession {

    /** Sanitize a session id for use as a directory name; fallback instead of throwing. */
    internal fun nameSafe(id: String): String {
        val clean = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val base =
            when {
                clean.isEmpty() || clean == "." || clean == ".." -> "session"
                else -> clean
            }
        // Suffix by the raw id so distinct ids that sanitize identically
        // (e.g. "a/b" vs "a_b") never share — and delete — each other's tmp dir.
        return "${base}_${id.hashCode().toUInt().toString(36)}"
    }

    /** Everything the [TerminalSession] constructor needs, assembled off the main thread. */
    private class Prepared(
        val workingDir: String,
        val processCwd: String,
        val shell: String,
        val args: Array<String>,
        val env: List<String>,
    )

    suspend fun createSession(
        context: Context,
        sessionClient: TerminalSessionClient,
        sessionId: String,
        isExtraction: Boolean = false,
        cwd: String? = null,
    ): Pair<TerminalSession, SessionPwd> {
        val prepared = withContext(Dispatchers.IO) { prepareEnvironment(context, sessionId, isExtraction, cwd) }

        return withContext(Dispatchers.Main.immediate) {
            val session =
                TerminalSession(
                    prepared.shell,
                    prepared.processCwd,
                    prepared.args,
                    prepared.env.toTypedArray(),
                    Settings.terminal_scrollback_buffer,
                    sessionClient,
                )
            session to prepared.workingDir
        }
    }

    /** All disk I/O and environment assembly; must not run on the main thread. */
    private suspend fun prepareEnvironment(
        context: Context,
        sessionId: String,
        isExtraction: Boolean,
        cwd: String?,
    ): Prepared {
        val workingDir = cwd ?: getPwd(context)

        val tmpDir = localDir().child("tmp").childSafe(nameSafeViaRust(sessionId))

        if (tmpDir.exists()) {
            tmpDir.deleteRecursively()
        }

        tmpDir.mkdirs()

        val envMap = SandboxEnv.build(context, tmpDir.absolutePath)
        // setup.sh rewrites resolv.conf after extraction; hand it the same DNS the app uses.
        if (isExtraction) {
            envMap["DNS_SERVERS"] = systemDns().ifEmpty { listOf("8.8.8.8", "8.8.4.4") }.joinToString(" ")
        }
        envMap["WKDIR"] = workingDir
        envMap["PATH"] = "${System.getenv("PATH")}:${localBinDir(context).absolutePath}"

        val env = envMap.map { "${it.key}=${it.value}" }.toMutableList()

        pendingCommand?.env?.let { env.addAll(it) }

        setupTerminalFiles()

        val sandboxSH = localBinDir(context).child("sandbox")
        val setupSH = localBinDir(context).child("setup")

        val args: Array<String>

        val shell =
            if (pendingCommand == null) {
                args =
                    if (Settings.sandbox) {
                        arrayOf(sandboxSH.absolutePath)
                    } else {
                        arrayOf()
                    }
                "/system/bin/sh"
            } else if (pendingCommand!!.sandbox.not()) {
                args = pendingCommand!!.args
                pendingCommand!!.exe
            } else {
                args =
                    mutableListOf(sandboxSH.absolutePath, pendingCommand!!.exe, *pendingCommand!!.args)
                        .toTypedArray<String>()

                "/system/bin/sh"
            }

        val actualShell: String
        val actualArgs: Array<String> =
            if (isExtraction) {
                actualShell = "/system/bin/sh"
                mutableListOf("-c", setupSH.absolutePath, *args).toTypedArray()
            } else {
                actualShell = shell
                arrayOf("-c", *args)
            }

        pendingCommand = null

        return Prepared(
            workingDir = workingDir,
            processCwd = localDir(context).absolutePath,
            shell = actualShell,
            args = actualArgs,
            env = env,
        )
    }
}

fun getPwd(context: Context): String {
    val pendingWorkingDir = pendingCommand?.workingDir
    if (pendingWorkingDir != null) {
        return pendingWorkingDir
    }

    if (context is Activity && context.intent.hasExtra("cwd")) {
        return context.intent.getStringExtra("cwd").toString()
    }

    return if (Settings.sandbox) {
        "/home"
    } else {
        sandboxHomeDir(context).absolutePath
    }
}
