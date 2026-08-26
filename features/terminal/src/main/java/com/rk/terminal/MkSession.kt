package com.rk.terminal

import android.app.Activity
import android.content.Context
import com.rk.exec.TerminalCommand
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
        command: TerminalCommand? = null,
    ): Pair<TerminalSession, SessionPwd> {
        val prepared =
            withContext(Dispatchers.IO) { prepareEnvironment(context, sessionId, isExtraction, cwd, command) }

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

    /**
     * All disk I/O and environment assembly; must not run on the main thread.
     *
     * [pending] must be owned by the caller: consume [com.rk.exec.consumePendingCommand]
     * once at a single entry point and pass the result here. Session restore
     * passes null so parallel restored shells can never steal or re-run a
     * launch command meant for the foreground session.
     */
    private fun prepareEnvironment(
        context: Context,
        sessionId: String,
        isExtraction: Boolean,
        cwd: String?,
        pending: TerminalCommand?,
    ): Prepared {
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
                // sandbox.sh runs `bash -c "$*"`: with `sh -c script exe args…`
                // the shell assigns exe to $0 and $* excludes $0, so the program
                // name vanished and only the raw args executed. The empty
                // placeholder keeps exe (and everything after) inside "$*".
                args = mutableListOf(sandboxSH.absolutePath, "", pending.exe, *pending.args).toTypedArray<String>()

                "/system/bin/sh"
            }

        val actualShell: String
        val actualArgs: Array<String> =
            if (isExtraction) {
                actualShell = "/system/bin/sh"
                // Same $0 rule: without the placeholder, args[0] would be eaten
                // as setup.sh's $0 and never reach its `sh "$@"` tail.
                mutableListOf("-c", setupSH.absolutePath, "", *args).toTypedArray()
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
    // getStringExtra can legitimately return null even when the extra is
    // present; toString() on it produced the literal string "null" as cwd.
    if (context is Activity) {
        context.intent.getStringExtra("cwd")?.let { return it }
    }

    return if (Settings.sandbox) {
        "/home"
    } else {
        sandboxHomeDir(context).absolutePath
    }
}
