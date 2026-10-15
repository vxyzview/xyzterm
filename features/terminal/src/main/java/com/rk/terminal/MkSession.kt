package com.rk.terminal

import android.app.Activity
import android.content.Context
import android.util.Log
import com.rk.exec.SandboxEnv
import com.rk.exec.TerminalCommand
import com.rk.exec.pendingCommand
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
 * Builds the `(executable, argv)` a session is launched with.
 *
 * The previous shape was `ProcessBuilder(shell, "-c", scriptPath, exe, *args)`, which was wrong
 * twice over:
 *
 *  - When the `-c` operand names an existing file, Android's `sh` (mksh) and dash both *run the
 *    file* instead of treating it as command text and discard the operands after it, so `exe`
 *    never became `$0` and never reached the shell.
 *  - Even under the POSIX reading, `sh -c script name a b` puts `name` in `$0`, and sandbox.sh
 *    ended at `bash -i -c "$*"`, which excludes `$0`. Only `args` ever ran.
 *
 * The unsandboxed path had the mirror bug: `ProcessBuilder(exe, "-c", *args)`, handing the binary
 * a `-c` flag it never asked for.
 *
 * The extraction path is left exactly as it was — setup.sh takes its inputs from the environment,
 * and `sh -c setup.sh` running the file is what it relies on today.
 */
internal fun buildSessionCommand(
    isExtraction: Boolean,
    sandboxEnabled: Boolean,
    sandboxScript: String,
    setupScript: String,
    pending: TerminalCommand?,
): Pair<String, Array<String>> {
    if (isExtraction) {
        return "/system/bin/sh" to arrayOf("-c", setupScript, *if (sandboxEnabled) arrayOf(sandboxScript) else emptyArray())
    }
    if (pending == null) {
        // No -c: the script is executed directly, and with no operands it opens an
        // interactive shell (sandbox.sh branches on $#).
        return "/system/bin/sh" to arrayOf(sandboxScript)
    }
    if (!pending.sandbox) {
        return pending.exe to pending.args
    }
    // sandbox.sh ends at `bash -i -c "$1"`, and bash -c takes exactly one string, so the
    // argv is re-encoded as a single quoted positional parameter.
    return "/system/bin/sh" to arrayOf(sandboxScript, shellCommandLine(arrayOf(pending.exe, *pending.args)))
}

/** Removes the per-session proot tmp dir.
 *
 * `prepareEnvironment` deletes it before creating it, so a terminated session's directory
 * lingered until the next spawn of the *same* id — or forever, if the user never recreated it.
 * proot allocates `PROOT_TMP_DIR` in `path/glue.c` and never frees it on SIGKILL, so the host
 * side has to.
 */
fun releaseTmpDir(sessionId: String) {
    runCatching { localDir().child("tmp").childSafe(nameSafe(sessionId)).deleteRecursively() }
        .onFailure { Log.w("MkSession", "could not release tmp dir for '$sessionId'", it) }
}

object MkSession {

    /** Sanitize a session id for use as a directory name; fallback instead of throwing. */
    private fun nameSafe(id: String): String {
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

        val tmpDir = localDir().child("tmp").childSafe(nameSafe(sessionId))

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

        val (actualShell, actualArgs) =
            buildSessionCommand(
                isExtraction = isExtraction,
                sandboxEnabled = Settings.sandbox,
                sandboxScript = sandboxSH.absolutePath,
                setupScript = setupSH.absolutePath,
                pending = pendingCommand,
            )

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
