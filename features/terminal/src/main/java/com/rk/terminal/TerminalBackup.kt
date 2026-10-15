package com.rk.terminal

import com.rk.file.child
import com.rk.file.createFileIfNot
import com.rk.file.localDir
import com.rk.utils.getTempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Creates and prunes terminal environment backups (sandbox tar.gz archives). */
object TerminalBackup {
    private const val KEEP = 3
    private const val TAR_TIMEOUT_MINUTES = 10L
    private const val MAX_TAR_OUTPUT = 4 * 1024
    private const val BACKUP_PREFIX = "terminal-backup-"
    private const val ARCHIVE_SUFFIX = ".tar.gz"
    private const val PARTIAL_SUFFIX = ".part"

    /** A `.part` older than this belongs to an interrupted run, not one still in flight. */
    internal const val STALE_PARTIAL_MS = 24L * 60 * 60 * 1000

    // The guest's /home and /root are binds of local/home (see sandbox.sh), which is a SIBLING
    // of local/sandbox — so the archive has to be rooted one level up, at local/, to reach it.
    // Every pattern below is anchored with a leading "./" because an unanchored --exclude is
    // matched against the final path component at any depth: the old "--exclude=data" also ate
    // ~/projects/data, and "--exclude=tmp" also ate guest /var/lib/dpkg/tmp.
    private val EXCLUDES =
        arrayOf(
            "--exclude=./sandbox/dev",
            "--exclude=./sandbox/sys",
            "--exclude=./sandbox/proc",
            "--exclude=./sandbox/system",
            "--exclude=./sandbox/apex",
            "--exclude=./sandbox/vendor",
            "--exclude=./sandbox/system_ext",
            "--exclude=./sandbox/data",
            "--exclude=./sandbox/storage",
            "--exclude=./sandbox/sdcard",
            "--exclude=./sandbox/lost+found",
            "--exclude=./sandbox/var/cache",
            "--exclude=./sandbox/var/tmp",
            // Live tmpfs bind for guest /dev/shm; never worth archiving.
            "--exclude=./sandbox/tmp",
            "--exclude=./home/tmp",
        )

    /** The argv that produces an archive holding both roots. Rooted at [localDir]. */
    internal fun backupCommand(target: File): List<String> =
        listOf("tar", "-czf", target.absolutePath, *EXCLUDES, "./sandbox", "./home")

    /** The directories a restore must replace, in the order [backupCommand] stores them. */
    internal fun restoreRoots(base: File): List<File> = listOf(File(base, "sandbox"), File(base, "home"))

    /** Completed archives, newest first. `.part` files are deliberately excluded. */
    internal fun completedBackups(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.startsWith(BACKUP_PREFIX) && f.name.endsWith(ARCHIVE_SUFFIX) }
            ?.sortedByDescending { it.name }
            .orEmpty()

    /** Interrupted runs older than [STALE_PARTIAL_MS]. A `.part` still being written survives. */
    internal fun stalePartials(dir: File, nowMs: Long): List<File> =
        dir.listFiles { f ->
            f.isFile &&
                f.name.startsWith(BACKUP_PREFIX) &&
                f.name.endsWith(PARTIAL_SUFFIX) &&
                nowMs - f.lastModified() > STALE_PARTIAL_MS
        }
            ?.sortedByDescending { it.name }
            .orEmpty()

    fun backupDir(): File = localDir().child("backups")

    /** Tars the sandbox and home directory into [targetFile]. Returns null on success, else the reason. */
    suspend fun create(targetFile: File): Boolean =
        withContext(Dispatchers.IO) {
            val process =
                ProcessBuilder(backupCommand(targetFile))
                    .directory(localDir())
                    .redirectErrorStream(true)
                    .start()
            // Drain merged output on a side thread while tar runs; an undrained
            // pipe deadlocks once tar writes more than the 64KB buffer, and a
            // blocking drain here would make the timeout unreachable. The lines are
            // kept: "file changed as we read it" is the single most common failure and
            // the user deserves to be told it rather than a bare "failed".
            var tarOutput = ""
            val drain = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (tarOutput.length < MAX_TAR_OUTPUT) tarOutput += line + "\n"
                        }
                    }
                }
            }
            drain.start()
            val timedOut = !process.waitFor(TAR_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            if (timedOut) {
                process.destroyForcibly()
            }
            drain.join(5_000)
            when {
                timedOut -> "tar timed out after $TAR_TIMEOUT_MINUTES minutes"
                process.exitValue() != 0 -> tarOutput.lineSequence().firstOrNull { it.isNotBlank() }
                    ?: "tar exited with ${process.exitValue()}"
                else -> null
            }
        }

    /**
     * Backups up the sandbox and home directory to a timestamped file in [backupDir], keeping the
     * newest [KEEP]. Returns null on success, otherwise a human-readable error string.
     */
    suspend fun autoBackup(): String? {
        val dir = backupDir()
        dir.mkdirs()
        // An interrupted run can leave a multi-GB `.part` behind. They sort newest by
        // millisecond, so they used to win every prune round and evict the real backups.
        stalePartials(dir, System.currentTimeMillis()).forEach { it.delete() }

        val target = File(dir, "terminal-backup-${System.currentTimeMillis()}.tar.gz")
        // Write to a temp name first so an interrupted run never leaves a
        // truncated archive that a later restore would choke on.
        val partial = File(dir, target.name + ".part")
        create(partial)?.let { reason ->
            partial.delete()
            return reason
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            return "could not finalise the archive"
        }
        completedBackups(dir).drop(KEEP).forEach { it.delete() }
        return null
    }

    /**
     * Restores [archive] into the sandbox. The archive is extracted into a staging directory first; the live sandbox is
     * only replaced once tar exited 0, so a corrupt backup never destroys the current install.
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

                // Raw child paths: the localDir()/sandboxDir() getters auto-create
                // the directory, which would block the renames below.
                val base = localDir()
                // Archives written before the home root existed stored the sandbox
                // contents at the archive root; archives written since nest them
                // under ./sandbox and ./home. Detect by shape rather than version.
                val legacyFlat = !File(staging, "sandbox").exists()

                if (legacyFlat) {
                    val sandbox = base.child("sandbox")
                    sandbox.deleteRecursively()
                    if (!staging.renameTo(sandbox)) {
                        return@withContext "could not move extracted files into place"
                    }
                } else {
                    for (target in restoreRoots(base)) {
                        val extracted = File(staging, target.name)
                        if (!extracted.exists()) continue
                        target.deleteRecursively()
                        if (!extracted.renameTo(target)) {
                            return@withContext "could not move ${extracted.name} into place"
                        }
                    }
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
