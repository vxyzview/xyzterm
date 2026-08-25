package com.rk.terminal

import com.rk.file.child
import com.rk.file.createFileIfNot
import com.rk.file.localDir
import com.rk.file.sandboxDir
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.utils.getTempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Distinct swap failure modes, so callers can show a specific message. */
internal enum class SwapFailure {
    /** The pre-swap backup of the current sandbox could not be prepared. */
    OLD_FAILED,

    /** Staging could not replace the live sandbox; the original was restored. */
    MOVE_FAILED,

    /** Staging failed to replace the live sandbox and restoring the original also failed. */
    ROLLBACK_FAILED,
}

/**
 * Atomically replaces [liveDir] with [stagingDir]: clears a stale [oldDir],
 * moves the live directory aside, renames staging into place and removes the
 * old copy. On any failure the previous live directory is restored untouched.
 */
internal fun swapSandbox(liveDir: File, stagingDir: File, oldDir: File): Boolean =
    swapSandboxChecked(liveDir, stagingDir, oldDir) == null

internal fun swapSandboxChecked(liveDir: File, stagingDir: File, oldDir: File): SwapFailure? {
    if (oldDir.exists() && !oldDir.deleteRecursively()) return SwapFailure.OLD_FAILED
    if (liveDir.exists() && !liveDir.renameTo(oldDir)) return SwapFailure.OLD_FAILED
    if (!stagingDir.renameTo(liveDir)) {
        if (oldDir.renameTo(liveDir)) {
            return SwapFailure.MOVE_FAILED
        }
        return SwapFailure.ROLLBACK_FAILED
    }
    oldDir.deleteRecursively()
    return null
}

/** Creates and prunes terminal environment backups (sandbox tar.gz archives). */
object TerminalBackup {
    private const val KEEP = 3
    private const val TAR_TIMEOUT_MINUTES = 10L

    // NOTE: /home and /root are deliberately NOT excluded — they hold the user's
    // shell history, dotfiles and installed tool configs. Excluding them made
    // backups silently lossless-looking but useless for real restores.
    private val EXCLUDES =
        arrayOf(
            "--exclude=dev",
            "--exclude=sys",
            "--exclude=proc",
            "--exclude=system",
            "--exclude=apex",
            "--exclude=vendor",
            "--exclude=data",
            "--exclude=var/cache",
            "--exclude=var/tmp",
            "--exclude=lost+found",
            "--exclude=storage",
            "--exclude=system_ext",
            "--exclude=tmp",
            "--exclude=sdcard",
        )

    fun backupDir(): File = localDir().child("backups")

    /** Tars the sandbox into [targetFile]. Returns true on success. */
    suspend fun create(targetFile: File): Boolean =
        withContext(Dispatchers.IO) {
            val process =
                ProcessBuilder("tar", "-czf", targetFile.absolutePath, ".", *EXCLUDES)
                    .directory(sandboxDir())
                    .redirectErrorStream(true)
                    .start()
            // Drain merged output on a side thread while tar runs; an undrained
            // pipe deadlocks once tar writes more than the 64KB buffer, and a
            // blocking drain here would make the timeout unreachable.
            var createOk = false
            val drain =
                Thread {
                    runCatching {
                        process.inputStream.bufferedReader().useLines { lines -> lines.forEach { /* drain */ } }
                    }
                }
            drain.start()
            val timedOut = !process.waitFor(TAR_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            if (timedOut) {
                process.destroyForcibly()
            }
            drain.join(5_000)
            if (!timedOut) {
                createOk = process.exitValue() == 0
            }
            createOk
        }

    /** Backs up the sandbox to a timestamped file in [backupDir], keeping the newest [KEEP]. */
    suspend fun autoBackup(onPhase: (String) -> Unit = {}): Boolean {
        val dir = backupDir()
        dir.mkdirs()
        val target = File(dir, "terminal-backup-${System.currentTimeMillis()}.tar.gz")
        // Write to a temp name first so an interrupted run never leaves a
        // truncated archive that a later restore would choke on.
        val partial = File(dir, target.name + ".part")
        onPhase(strings.reading_files.getString())
        if (!create(partial)) {
            partial.delete()
            return false
        }
        onPhase(strings.compressing.getString())
        if (!partial.renameTo(target)) {
            partial.delete()
            return false
        }
        dir.listFiles { f -> f.name.startsWith("terminal-backup-") }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
        return true
    }

    /**
     * Restores [archive] into the sandbox. The archive is extracted into a
     * staging directory first; the live sandbox is only replaced once tar
     * exited 0, so a corrupt backup never destroys the current install.
     *
     * Returns null on success, otherwise a human-readable error string.
     */
    suspend fun restore(archive: File, onPhase: (String) -> Unit = {}): String? =
        withContext(Dispatchers.IO) {
            val staging = getTempDir().child("terminal-restore-staging")
            try {
                staging.deleteRecursively()
                staging.mkdirs()

                // -z is explicit: some toybox builds don't sniff gzip.
                onPhase(strings.extracting.getString())
                val process =
                    ProcessBuilder("tar", "-xzf", archive.absolutePath, "-C", staging.absolutePath)
                        .start()
                // Drain stderr concurrently; reading after waitFor() deadlocks if
                // tar fills the pipe before exiting.
                var stderr = ""
                val stderrDrain =
                    Thread {
                        stderr =
                            runCatching { process.errorStream.bufferedReader().use { it.readText() } }
                                .getOrDefault("")
                    }
                stderrDrain.start()
                val timedOut = !process.waitFor(TAR_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                if (timedOut) {
                    process.destroyForcibly()
                }
                stderrDrain.join(5_000)

                if (timedOut) {
                    return@withContext "tar timed out"
                }

                val code = process.exitValue()
                if (code != 0) {
                    return@withContext (
                        stderr.lineSequence().firstOrNull { it.isNotBlank() }
                            ?: "tar exited with $code"
                        )
                }

                // Raw child path: the sandboxDir() getter auto-creates the
                // directory, which would block the rename below.
                onPhase(strings.moving_into_place.getString())
                val local = localDir()
                val sandboxPath = local.child("sandbox")
                val oldSandbox = local.child("sandbox.old")
                when (swapSandboxChecked(sandboxPath, staging, oldSandbox)) {
                    null -> {}
                    SwapFailure.OLD_FAILED ->
                        return@withContext strings.backup_swap_old_failed.getString()
                    SwapFailure.MOVE_FAILED ->
                        return@withContext strings.backup_swap_failed.getString()
                    SwapFailure.ROLLBACK_FAILED ->
                        return@withContext strings.backup_swap_rollback_failed.getString()
                }

                localDir().child(".terminal_setup_ok_DO_NOT_REMOVE").createFileIfNot()
                null
            } catch (e: Exception) {
                e.message ?: "restore failed"
            } finally {
                staging.deleteRecursively()
            }
        }
}
