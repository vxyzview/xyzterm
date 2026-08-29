package com.rk.terminal

import android.content.Context
import com.rk.exec.SandboxEnv
import com.rk.exec.TerminalCommand
import com.rk.file.child
import com.rk.file.childSafe
import com.rk.file.localBinDir
import com.rk.file.localDir
import com.rk.file.sandboxHomeDir
import com.rk.settings.Settings
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [SessionFactory]: today's MkSession.createSession body. The
 * shell-env assembly (sandbox? / extraction? / [TerminalCommand] injection)
 * is encapsulated here; the controller never sees a top-level var or a
 * pending command.
 *
 * The one-shot [TerminalCommand] pattern (set via [setPendingCommand] and
 * consumed on the next [create] call) used to live as a process-global
 * mutable in [com.rk.exec.TerminalCommand]. It moved here as a private
 * static so the global no longer leaks across the codebase. Callers that
 * previously wrote `pendingCommand = cmd` now go through
 * [setPendingCommand]; the controller calls [consumePendingCommand] when
 * it actually creates a session.
 */
class MkSessionFactory(
    private val context: Context,
) : SessionFactory {
    override suspend fun create(
        id: SessionId,
        workingDir: SessionPwd,
        client: TerminalSessionClient,
        isExtraction: Boolean,
    ): Pair<TerminalSession, SessionPwd> {
        val command = consumePendingCommand()
        val effectiveCwd = workingDir.takeIf { it.isNotBlank() } ?: resolvePwd(command, context)

        val prepared =
            withContext(Dispatchers.IO) {
                prepareEnvironment(id, isExtraction, effectiveCwd, command)
            }

        return withContext(Dispatchers.Main.immediate) {
            val session =
                TerminalSession(
                    prepared.shell,
                    prepared.processCwd,
                    prepared.args,
                    prepared.env.toTypedArray(),
                    Settings.terminal_scrollback_buffer,
                    client,
                )
            session to prepared.workingDir
        }
    }

    private suspend fun prepareEnvironment(
        sessionId: String,
        isExtraction: Boolean,
        cwd: SessionPwd,
        command: TerminalCommand?,
    ): Prepared {
        val workingDir = cwd

        val tmpDir = localDir(context).child("tmp").childSafe(sessionId)
        if (tmpDir.exists()) tmpDir.deleteRecursively()
        tmpDir.mkdirs()

        val envMap = SandboxEnv.build(context, tmpDir.absolutePath)
        envMap["WKDIR"] = workingDir
        envMap["PATH"] = "${System.getenv("PATH")}:${localBinDir(context).absolutePath}"

        val env = envMap.map { "${it.key}=${it.value}" }.toMutableList()
        command?.env?.toList()?.let { env.addAll(it) }

        setupTerminalFiles()

        val sandboxSH = localBinDir(context).child("sandbox")
        val setupSH = localBinDir(context).child("setup")

        val args: Array<String>
        val shell =
            if (command == null) {
                args =
                    if (Settings.sandbox) {
                        arrayOf(sandboxSH.absolutePath)
                    } else {
                        arrayOf()
                    }
                "/system/bin/sh"
            } else if (!command.sandbox) {
                args = command.args
                command.exe
            } else {
                args =
                    mutableListOf(sandboxSH.absolutePath, command.exe, *command.args).toTypedArray()
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

        return Prepared(
            workingDir = workingDir,
            processCwd = localDir(context).absolutePath,
            shell = actualShell,
            args = actualArgs,
            env = env,
        )
    }

    private data class Prepared(
        val workingDir: String,
        val processCwd: String,
        val shell: String,
        val args: Array<String>,
        val env: List<String>,
    )
}

/**
 * Sets the one-shot command for the next session created by
 * [MkSessionFactory.create]. Replaces the old top-level `pendingCommand`
 * var with the same last-write-wins semantics.
 */
fun setPendingCommand(command: TerminalCommand?) {
    pendingCommand = command
}

/** Reads and nulls [pendingCommand] in one shot. */
internal fun consumePendingCommand(): TerminalCommand? {
    val cmd = pendingCommand
    pendingCommand = null
    return cmd
}

// ponytail: process-global mutable, but the only writers are setPendingCommand
// (called once per external launcher invocation) and consumePendingCommand
// (called once per session create). Move to DI-injected PendingCommand if
// multiple controllers ever need it.
private var pendingCommand: TerminalCommand? = null

private fun resolvePwd(command: TerminalCommand?, context: Context): String {
    command?.workingDir?.takeIf { it.isNotBlank() }?.let { return it }

    if (context is android.app.Activity && context.intent.hasExtra("cwd")) {
        return context.intent.getStringExtra("cwd").toString()
    }

    return if (Settings.sandbox) {
        "/home"
    } else {
        sandboxHomeDir(context).absolutePath
    }
}