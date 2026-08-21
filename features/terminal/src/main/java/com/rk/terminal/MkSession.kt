package com.rk.terminal

import android.app.Activity
import android.content.Context
import com.rk.exec.SandboxEnv
import com.rk.exec.pendingCommand
import com.rk.file.child
import com.rk.file.localBinDir
import com.rk.file.localDir
import com.rk.file.sandboxHomeDir
import com.rk.settings.Settings
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

object MkSession {

    fun createSession(
        context: Context,
        sessionClient: TerminalSessionClient,
        sessionId: String,
        isExtraction: Boolean = false,
        cwd: String? = null,
    ): Pair<TerminalSession, SessionPwd> {
        val workingDir = cwd ?: getPwd(context)

        val tmpDir = localDir().child("tmp").child(sessionId)

        if (tmpDir.exists()) {
            tmpDir.deleteRecursively()
        }

        tmpDir.mkdirs()

        val envMap = SandboxEnv.build(context, tmpDir.absolutePath)
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

        return TerminalSession(
            actualShell,
            localDir(context).absolutePath,
            actualArgs,
            env.toTypedArray(),
            Settings.terminal_scrollback_buffer,
            sessionClient,
        ) to workingDir
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
