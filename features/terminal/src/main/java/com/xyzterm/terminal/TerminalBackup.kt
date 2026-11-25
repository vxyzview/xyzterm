package com.xyzterm.terminal

import com.xyzterm.file.child
import com.xyzterm.file.createFileIfNot
import com.xyzterm.file.localDir
import com.xyzterm.file.sandboxHomeDir
import com.xyzterm.utils.getTempDir
import java.io.File
import java.nio.file.Files
import java.util.UUID
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
    if (entries.any { it == "sandbox" || it.startsWith("sandbox/") } &&
        entries.any { it == "home" || it.startsWith("home/") }
    ) {
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
        val phrase =
            when (val state = AutoBackupPassphrase.getState()) {
                is PassphraseState.Ready -> state.phrase
                PassphraseState.NotSet -> null
                // A sealed-but-unreadable passphrase must silently fail, not
                // fall back to an unencrypted archive.
                PassphraseState.Error -> return false
            }
        val dir = backupDir()
        dir.mkdirs()
        val suffix = if (phrase == null) ".tar.gz" else ".tar.gz.enc"
        // Keep the timestamp so pruning still keeps the newest KEEP archives;
        // the UUID makes concurrent/same-millisecond runs non-colliding.
        val unique = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
        val target = File(dir, "terminal-backup-$unique$suffix")
        // Write to a temp name first so an interrupted run never leaves a
        // truncated archive that a later restore would choke on.
        val partial = File(dir, "${target.name}.part")
        if (!create(partial)) {
            partial.delete()
            return false
        }
        val finished =
            if (phrase == null) {
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
                f.name.startsWith("terminal-backup-") && (f.name.endsWith(".tar.gz") || f.name.endsWith(".tar.gz.enc"))
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
                val stagingRoot =
                    runCatching { staging.toPath().toRealPath() }.getOrNull()
                        ?: return@withContext "backup contains unsafe symlinks"
                val unsafeSymlink = runCatching {
                    Files.walk(staging.toPath()).use { paths ->
                        paths.iterator().asSequence().any { path ->
                            if (!Files.isSymbolicLink(path)) {
                                false
                            } else {
                                val link = Files.readSymbolicLink(path)
                                link.isAbsolute ||
                                    link.toString().split('/').contains("..") ||
                                    !path.toRealPath().startsWith(stagingRoot)
                            }
                        }
                    }
                }
                    .getOrDefault(true)
                if (unsafeSymlink) {
                    return@withContext "backup contains unsafe symlinks"
                }

                val sandboxTarget = localDir().child("sandbox")
                val stagedSandbox = File(staging, "sandbox")
                val stagedHome = File(staging, "home")
                // A V1 archive is itself a rootfs, so it can legitimately contain both
                // /sandbox and /home. `bin/bash` at the archive root settles the case.
                val isRootfsAtRoot = File(staging, "bin/bash").isFile
                val layout =
                    if (!isRootfsAtRoot &&
                        detectBackupLayout(staging.list()?.toList().orEmpty()) == BackupLayout.V2_SANDBOX_HOME &&
                        stagedSandbox.isDirectory &&
                        stagedHome.isDirectory
                    ) {
                        BackupLayout.V2_SANDBOX_HOME
                    } else {
                        BackupLayout.V1_ROOT
                    }
                when (layout) {
                    BackupLayout.V1_ROOT -> {
                        // Old archives never contained home; leave the user's home alone.
                        swapDirectory(staging, sandboxTarget)?.let {
                            return@withContext it
                        }
                    }
                    BackupLayout.V2_SANDBOX_HOME -> {
                        swapDirectory(stagedSandbox, sandboxTarget)?.let {
                            return@withContext it
                        }
                        swapDirectory(stagedHome, sandboxHomeDir())?.let {
                            return@withContext it
                        }
                    }
                }

                // Every swap succeeded, so the restored tree is authoritative. Old
                // copies stay in place on any error to keep a rollback possible.
                oldBackups(sandboxTarget).forEach { it.deleteRecursively() }
                oldBackups(sandboxHomeDir()).forEach { it.deleteRecursively() }

                localDir().child(".terminal_setup_ok_DO_NOT_REMOVE").createFileIfNot()
                null
            } catch (e: Exception) {
                e.message ?: "restore failed"
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        }

    /**
     * Moves [source] into [target], keeping the old target as `<name>.old` for cleanup after every swap succeeds.
     * Restores the old directory if the second rename fails. Returns null on success, otherwise an error message.
     */
    private fun oldBackups(target: File): List<File> =
        target.parentFile
            ?.listFiles { file ->
                file.name == "${target.name}.old" || file.name.startsWith("${target.name}.old-")
            }
            ?.toList()
            .orEmpty()

    private fun swapDirectory(source: File, target: File): String? {
        // An interrupted restore can leave an old tree without a live target;
        // recover the newest one first so there is always something to roll back to.
        if (!target.exists()) {
            val backup = oldBackups(target).maxByOrNull { it.lastModified() }
            if (backup != null && !backup.renameTo(target)) {
                return "could not recover the previous ${target.name}"
            }
        }
        // A previous failed restore may have left a `.old` behind. Use a fresh name
        // instead of deleting it: it may be the only recoverable copy.
        var previous = File(target.parentFile, "${target.name}.old")
        var suffix = 1
        while (previous.exists()) {
            previous = File(target.parentFile, "${target.name}.old-${suffix++}")
        }
        val hadTarget = target.exists()
        if (hadTarget && !target.renameTo(previous)) {
            return "could not move the current ${target.name} aside"
        }
        if (!source.renameTo(target)) {
            if (hadTarget) previous.renameTo(target)
            return "could not move the restored ${target.name} into place"
        }
        // Intentionally left for restore() to delete once all swaps succeeded.
        return null
    }
}
