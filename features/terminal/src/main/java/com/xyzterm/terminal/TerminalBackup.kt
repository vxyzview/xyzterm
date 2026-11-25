package com.xyzterm.terminal

import com.xyzterm.file.child
import com.xyzterm.file.createFileIfNot
import com.xyzterm.file.localDir
import com.xyzterm.file.sandboxHomeDir
import com.xyzterm.utils.getTempDir
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Archive layouts understood by [TerminalBackup.restore]. */
internal enum class BackupLayout {
    /** Old format: the archive root is the sandbox; there is no user home. */
    V1_ROOT,
    /** Current format: top-level `sandbox/` and `home/` directories. */
    V2_SANDBOX_HOME,
}

/**
 * Detect the archive layout from its top-level entry names. Kept pure so it can be unit-tested without touching the
 * filesystem.
 */
internal fun detectBackupLayout(entries: List<String>): BackupLayout =
    if (entries.any { it == "sandbox" || it.startsWith("sandbox/") }) {
        BackupLayout.V2_SANDBOX_HOME
    } else {
        BackupLayout.V1_ROOT
    }

/** Creates and prunes terminal environment backups (sandbox + user home). */
object TerminalBackup {
    private const val KEEP = 3
    private const val TAR_TIMEOUT_MINUTES = 10L

    // Paths are relative to localDir(): `sandbox` is the rootfs, `home` is the
    // guest /home and /root (bound from sandboxHomeDir by SandboxEnv). Both are
    // archived together so a restore on a new device keeps the user's files.
    private val EXCLUDES =
        arrayOf(
            "--exclude=sandbox/dev",
            "--exclude=sandbox/sys",
            "--exclude=sandbox/proc",
            "--exclude=sandbox/system",
            "--exclude=sandbox/apex",
            "--exclude=sandbox/vendor",
            "--exclude=sandbox/data",
            "--exclude=sandbox/var/cache",
            "--exclude=sandbox/var/tmp",
            "--exclude=sandbox/lost+found",
            "--exclude=sandbox/storage",
            "--exclude=sandbox/system_ext",
            "--exclude=sandbox/tmp",
            "--exclude=sandbox/sdcard",
        )

    fun backupDir(): File = localDir().child("backups")

    /** Tars the sandbox and user home into [targetFile]. Returns true on success. */
    suspend fun create(targetFile: File): Boolean =
        withContext(Dispatchers.IO) {
            sandboxHomeDir().mkdirs()
            val process =
                ProcessBuilder("tar", "-czf", targetFile.absolutePath, *EXCLUDES, "sandbox", "home")
                    .directory(localDir())
                    .redirectErrorStream(true)
                    .start()
            // Drain merged output on a side thread while tar runs; an undrained
            // pipe deadlocks once tar writes more than the 64KB buffer, and a
            // blocking drain here would make the timeout unreachable.
            var createOk = false
            val drain = Thread {
                runCatching { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { /* drain */ } } }
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
    suspend fun autoBackup(): Boolean {
        val dir = backupDir()
        dir.mkdirs()
        val phrase = AutoBackupPassphrase.get()
        val suffix = if (phrase.isNullOrEmpty()) ".tar.gz" else ".tar.gz.enc"
        val target = File(dir, "terminal-backup-${System.currentTimeMillis()}$suffix")
        // Write to a temp name first so an interrupted run never leaves a
        // truncated archive that a later restore would choke on.
        val partial = File(dir, target.name + ".part")
        if (!create(partial)) {
            partial.delete()
            return false
        }
        // Stale .part files must not count towards KEEP, or one evicts a good archive.
        dir.listFiles { it.name.startsWith("terminal-backup-") && it.name.endsWith(".part") }
            ?.forEach { it.delete() }
        val finished =
            if (phrase.isNullOrEmpty()) {
                partial.renameTo(target)
            } else {
                // encrypt() writes the named target itself; no rename needed.
                runCatching { BackupCrypto.encrypt(partial, target, phrase) }.isSuccess
            }
        if (!finished) {
            partial.delete()
            target.delete()
            return false
        }
        partial.delete()
        dir.listFiles { f ->
            f.name.startsWith("terminal-backup-") &&
                (f.name.endsWith(".tar.gz") || f.name.endsWith(".tar.gz.enc"))
        }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
        return true
    }

    /**
     * Restores [archive] into the sandbox and, for current archives, the user home. Everything is extracted into a
     * staging directory first; the live directories are only replaced once tar exited 0, so a corrupt backup never
     * destroys the current install.
     *
     * Returns null on success, otherwise a human-readable error string.
     */
    suspend fun restore(archive: File): String? =
        withContext(Dispatchers.IO) {
            val staging = getTempDir().child("terminal-restore-staging")
            try {
                staging.deleteRecursively()
                staging.mkdirs()

                // -z is explicit: some toybox builds don't sniff gzip.
                val process = ProcessBuilder("tar", "-xzf", archive.absolutePath, "-C", staging.absolutePath).start()
                // Drain stderr concurrently; reading after waitFor() deadlocks if
                // tar fills the pipe before exiting.
                var stderr = ""
                val stderrDrain = Thread {
                    stderr = runCatching { process.errorStream.bufferedReader().use { it.readText() } }.getOrDefault("")
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
                    return@withContext (stderr.lineSequence().firstOrNull { it.isNotBlank() }
                        ?: "tar exited with $code")
                }

                // User-supplied archives are a trust boundary: reject symlinks
                // that point outside the staging tree before swapping directories.
                val unsafeSymlink = runCatching {
                    Files.walk(staging.toPath()).use { paths ->
                        paths.iterator().asSequence().any { path ->
                            if (!Files.isSymbolicLink(path)) {
                                false
                            } else {
                                val target = Files.readSymbolicLink(path).toString()
                                target.split('/').contains("..")
                            }
                        }
                    }
                }
                    .getOrDefault(true)
                if (unsafeSymlink) {
                    return@withContext "backup contains unsafe symlinks"
                }

                val sandboxTarget = localDir().child("sandbox")
                when (detectBackupLayout(staging.list()?.toList().orEmpty())) {
                    BackupLayout.V1_ROOT -> {
                        // Old archives never contained home; leave the user's home alone.
                        swapDirectory(staging, sandboxTarget)?.let {
                            return@withContext it
                        }
                    }
                    BackupLayout.V2_SANDBOX_HOME -> {
                        val stagedSandbox = File(staging, "sandbox")
                        if (!stagedSandbox.isDirectory) {
                            return@withContext "backup is missing its sandbox directory"
                        }
                        swapDirectory(stagedSandbox, sandboxTarget)?.let {
                            return@withContext it
                        }
                        // Only replace home when the archive actually carries it.
                        val stagedHome = File(staging, "home")
                        if (stagedHome.isDirectory) {
                            swapDirectory(stagedHome, sandboxHomeDir())?.let {
                                return@withContext it
                            }
                        }
                    }
                }

                localDir().child(".terminal_setup_ok_DO_NOT_REMOVE").createFileIfNot()
                null
            } catch (e: Exception) {
                e.message ?: "restore failed"
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        }

    /**
     * Moves [source] into [target], keeping the old target as `<name>.old` until the move succeeds. Restores the old
     * directory if the second rename fails. Returns null on success, otherwise an error message.
     */
    private fun swapDirectory(source: File, target: File): String? {
        val previous = File(target.parentFile, "${target.name}.old")
        previous.deleteRecursively()
        val hadTarget = target.exists()
        if (hadTarget && !target.renameTo(previous)) {
            return "could not move the current ${target.name} aside"
        }
        if (!source.renameTo(target)) {
            if (hadTarget) previous.renameTo(target)
            return "could not move the restored ${target.name} into place"
        }
        previous.deleteRecursively()
        return null
    }
}
