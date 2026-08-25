package com.rk.terminal

import android.app.Activity
import android.content.Context
import com.rk.exec.consumePendingCommand
import com.rk.exec.SandboxEnv
import com.rk.file.child
import com.rk.file.localBinDir
import com.rk.file.localDir
import com.rk.file.sandboxHomeDir
import com.rk.settings.Settings
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MkSession {

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
        val prepared =
            withContext(Dispatchers.IO) { prepareEnvironment(context, sessionId, isExtraction, cwd) }

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
    private fun prepareEnvironment(context: Context, sessionId: String, isExtraction: Boolean, cwd: String?): Prepared {
        // Single atomic snapshot: parallel session creation (restore) or a
        // racing consumer must never observe a half-consumed command.
        val pending = consumePendingCommand()

        val workingDir = cwd ?: pending?.workingDir ?: getPwd(context)

        val tmpDir = localDir().child("tmp").child(sessionId)

        if (tmpDir.exists()) {
            tmpDir.deleteRecursively()
        }

        tmpDir.mkdirs()

        val envMap = SandboxEnv.build(context, tmpDir.absolutePath)
        envMap["WKDIR"] = workingDir
        envMap["PATH"] = "${System.getenv("PATH")}:${localBinDir(context).absolutePath}"

        val env = envMap.map { "${it.key}=${it.value}" }.toMutableList()

        pending?.env?.let { env.addAll(it) }

        setupTerminalFiles()

        val sandboxSH = localBinDir(context).child("sandbox")
        val setupSH = localBinDir(context).child("setup")

        val args: Array<String>

        val shell =
            if (pending == null) {
                args =
                    if (Settings.sandbox) {
                        arrayOf(sandboxSH.absolutePath)
                    } else {
                        arrayOf()
                    }
                "/system/bin/sh"
            } else if (pending.sandbox.not()) {
                args = pending.args
                pending.exe
            } else {
                args = mutableListOf(sandboxSH.absolutePath, pending.exe, *pending.args).toTypedArray<String>()

                "/system/bin/sh"
            }

        val actualShell: String
        val actualArgs: Array<String> =
            if (isExtraction) {
                actualShell = "/system/bin/sh"
                mutableListOf("-c", setupSH.absolutePath, *args).toTypedArray()
            } else {
                actualShell = shell
                if (args.isEmpty()) arrayOf() else arrayOf("-c", *args)
            }

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
    if (context is Activity && context.intent.hasExtra("cwd")) {
        return context.intent.getStringExtra("cwd").toString()
    }

    return if (Settings.sandbox) {
        "/home"
    } else {
        sandboxHomeDir(context).absolutePath
    }
}
