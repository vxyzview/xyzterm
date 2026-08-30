package com.rk.terminal

import android.app.Activity
import android.content.Context
import com.rk.exec.ProotSandboxPaths
import com.rk.exec.SandboxEnv
import com.rk.exec.pendingCommand
import com.rk.file.child
import com.rk.file.childSafe
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
        val paths = ProotSandboxPaths(context)
        val prepared =
            withContext(Dispatchers.IO) { prepareEnvironment(context, paths, sessionId, isExtraction, cwd) }

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
    private suspend fun prepareEnvironment(context: Context, paths: ProotSandboxPaths, sessionId: String, isExtraction: Boolean, cwd: String?): Prepared {
        val workingDir = cwd ?: getPwd(context, paths)

        val tmpDir = paths.sandboxTmp.childSafe(sessionId)

        if (tmpDir.exists()) {
            tmpDir.deleteRecursively()
        }

        tmpDir.mkdirs()

        val envMap = SandboxEnv.build(context, paths, tmpDir.absolutePath)
        envMap["WKDIR"] = workingDir
        envMap["PATH"] = "${System.getenv("PATH")}:${paths.sandboxBin.absolutePath}"

        val env = envMap.map { "${it.key}=${it.value}" }.toMutableList()

        pendingCommand?.env?.let { env.addAll(it) }

        setupTerminalFiles()

        val sandboxSH = paths.sandboxBin.child("sandbox")
        val setupSH = paths.sandboxBin.child("setup")

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
            processCwd = paths.processCwd.absolutePath,
            shell = actualShell,
            args = actualArgs,
            env = env,
        )
    }
}

fun getPwd(context: Context, paths: ProotSandboxPaths = ProotSandboxPaths(context)): String {
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
        paths.sandboxHome.absolutePath
    }
}
