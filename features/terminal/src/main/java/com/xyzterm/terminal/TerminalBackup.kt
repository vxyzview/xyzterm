package com.xyzterm.terminal

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.xyzterm.commands.KeybindingsManager
import com.xyzterm.file.child
import com.xyzterm.file.createFileIfNot
import com.xyzterm.file.localDir
import com.xyzterm.file.sandboxHomeDir
import com.xyzterm.settings.Preference
import com.xyzterm.settings.Settings
import com.xyzterm.utils.application
import com.xyzterm.utils.getTempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    if (
        entries.any { it == "sandbox" || it.startsWith("sandbox/") } &&
            entries.any { it == "home" || it.startsWith("home/") }
    ) {
        BackupLayout.V2_SANDBOX_HOME
    } else {
        BackupLayout.V1_ROOT
    }

/** True only for a real directory — a symlink to one must never be swapped in as a rootfs. */
internal fun isRealDirectory(file: File): Boolean = Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)

/** Puts [old] back at [target]; a plain rename fails when target is a non-empty directory. */
internal fun restoreRollback(
    target: File,
    old: File,
): Boolean = runCatching {
    if (target.exists() && !target.deleteRecursively()) return false
    old.renameTo(target)
}
    .getOrDefault(false)

// Two restores at once would delete each other's staging tree.
private val restoreMutex = Mutex()

private data class SwapResult(
    val error: String?,
    val rollback: File?,
)

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
            // Stage settings and keybindings inside the tar's working directory so
            // the archive is self-contained and concurrent backups cannot collide.
            val metaDir = localDir().child("backup-meta-${UUID.randomUUID()}")
            metaDir.mkdirs()
            try {
                File(metaDir, "settings.json")
                    .writeText(
                        BackupSettings.snapshot(BackupSettings.withoutDeviceBoundKeys(Preference.getAll())),
                    )
                val keybindings = application!!.filesDir.resolve("keybindings.json")
                if (keybindings.exists()) {
                    keybindings.copyTo(File(metaDir, "keybindings.json"), overwrite = true)
                }
                val process =
                    ProcessBuilder(
                            "tar",
                            "-czf",
                            targetFile.absolutePath,
                            *EXCLUDES,
                            "sandbox",
                            "home",
                            metaDir.name,
                        )
                        .directory(localDir())
                        .redirectErrorStream(true)
                        .start()
                // Drain merged output on a side thread while tar runs; an undrained
                // pipe deadlocks once tar writes more than the 64KB buffer, and a
                // blocking drain here would make the timeout unreachable.
                var createOk = false
                val drain = Thread {
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
            } finally {
                metaDir.deleteRecursively()
            }
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
        // tar writes a .part and encryption writes a second full copy, so peak
        // usage is roughly three archives. Refuse before creating a truncated one.
        val typicalArchive =
            dir.listFiles { f ->
                    f.name.startsWith("terminal-backup-") &&
                        (f.name.endsWith(".tar.gz") || f.name.endsWith(".tar.gz.enc"))
                }
                ?.maxOfOrNull { it.length() } ?: (100L * 1024 * 1024)
        if (dir.usableSpace < typicalArchive * 3) return false
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
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
        copyToSafFolder(target)
        return true
    }

    /** Mirrors auto-backups into a user-picked SAF folder, which survives uninstall and clear-data. */
    private fun copyToSafFolder(archive: File) {
        val uriString = Settings.backup_tree_uri
        if (uriString.isBlank()) return
        val context = application ?: return
        val tree = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uriString)) }.getOrNull() ?: return
        runCatching {
            tree.findFile(archive.name)?.delete()
            val document = tree.createFile("application/gzip", archive.name) ?: return
            context.contentResolver.openOutputStream(document.uri)?.use { output ->
                archive.inputStream().use { it.copyTo(output) }
            }
            tree
                .listFiles()
                .filter { it.name?.startsWith("terminal-backup-") == true }
                .sortedByDescending { it.lastModified() }
                .drop(KEEP)
                .forEach { it.delete() }
        }
    }

    /**
     * Restores [archive] into the sandbox and, for current archives, the user home. Everything is extracted into a
     * staging directory first; the live directories are only replaced once tar exited 0, so a corrupt backup never
     * destroys the current install.
     *
     * Returns null on success, otherwise a human-readable error string.
     */
    suspend fun restore(archive: File): String? = restoreMutex.withLock {
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

                // User-supplied archives are a trust boundary: reject symlinks that
                // escape the extracted root. The real rootfs legitimately contains
                // many `..` links and guest-absolute links, so validate the target
                // text the same way the installer does instead of resolving it on
                // the host filesystem.
                val stagingPath = staging.toPath()
                val unsafeSymlink = runCatching {
                    Files.walk(stagingPath).use { paths ->
                        paths.iterator().asSequence().any { path ->
                            if (!Files.isSymbolicLink(path)) {
                                false
                            } else {
                                val guestPath = stagingPath.relativize(path).toString()
                                !isSafeLinkTarget(guestPath, Files.readSymbolicLink(path).toString())
                            }
                        }
                    }
                }
                    .getOrDefault(true)
                if (unsafeSymlink) {
                    return@withContext "backup contains unsafe symlinks"
                }

                // A symlink inside the tree was already rejected above; a link *named*
                // sandbox or home can still pass that walk because it resolves inside
                // staging. Reject it at the swap boundary for both V1 and V2 archives.
                val topLevelLinks =
                    listOf("sandbox", "home").any { name ->
                        val entry = File(staging, name)
                        Files.exists(entry.toPath(), LinkOption.NOFOLLOW_LINKS) && !isRealDirectory(entry)
                    }
                if (topLevelLinks) {
                    return@withContext "backup contains unsafe symlinks"
                }

                val sandboxTarget = localDir().child("sandbox")
                val stagedSandbox = File(staging, "sandbox")
                val stagedHome = File(staging, "home")
                // A V1 archive is itself a rootfs, so it can legitimately contain both
                // /sandbox and /home. `bin/bash` at the archive root settles the case.
                val isRootfsAtRoot = File(staging, "bin/bash").isFile
                val layout =
                    if (
                        !isRootfsAtRoot &&
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
                        swapDirectory(staging, sandboxTarget).error?.let {
                            return@withContext it
                        }
                    }
                    BackupLayout.V2_SANDBOX_HOME -> {
                        val sandboxSwap = swapDirectory(stagedSandbox, sandboxTarget)
                        sandboxSwap.error?.let {
                            return@withContext it
                        }
                        val homeSwap = swapDirectory(stagedHome, sandboxHomeDir())
                        homeSwap.error?.let { error ->
                            // The sandbox was already replaced; put the exact old tree back.
                            val rollback = sandboxSwap.rollback
                            if (rollback != null && !restoreRollback(sandboxTarget, rollback)) {
                                return@withContext "restore failed; previous sandbox left at ${rollback.name}: $error"
                            }
                            return@withContext error
                        }
                    }
                }

                // Every swap succeeded, so the restored tree is authoritative. Old
                // copies stay in place on any error to keep a rollback possible.
                oldBackups(sandboxTarget).forEach { it.deleteRecursively() }
                oldBackups(sandboxHomeDir()).forEach { it.deleteRecursively() }

                // Newer archives carry a settings/keybindings snapshot. Older ones simply
                // skip this step, so restoring them stays backwards compatible.
                staging
                    .listFiles { file -> file.isDirectory && file.name.startsWith("backup-meta-") }
                    ?.firstOrNull()
                    ?.let { meta ->
                        File(meta, "settings.json")
                            .takeIf { it.isFile }
                            ?.let { file ->
                                BackupSettings.restore(
                                    file.readText(),
                                    BackupSettings.schemaFromClasses(Preference.preferenceTypes),
                                ) { key, value ->
                                    Preference.put(key, value)
                                }
                            }
                        File(meta, "keybindings.json")
                            .takeIf { it.isFile }
                            ?.let { file ->
                                file.copyTo(application!!.filesDir.resolve("keybindings.json"), overwrite = true)
                                KeybindingsManager.loadKeybindings()
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
    }

    private fun oldBackups(target: File): List<File> =
        target.parentFile
            ?.listFiles { file -> file.name == "${target.name}.old" || file.name.startsWith("${target.name}.old-") }
            ?.toList()
            .orEmpty()

    /** Moves [source] into [target], keeping the old target as `<name>.old` until every swap succeeds. */
    private fun swapDirectory(source: File, target: File): SwapResult {
        // An interrupted restore can leave an old tree without a live target;
        // recover the newest one first so there is always something to roll back to.
        if (!target.exists()) {
            val backup = oldBackups(target).maxByOrNull { it.lastModified() }
            if (backup != null && !backup.renameTo(target)) {
                return SwapResult("could not recover the previous ${target.name}", null)
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
            return SwapResult("could not move the current ${target.name} aside", null)
        }
        if (!source.renameTo(target)) {
            if (hadTarget) previous.renameTo(target)
            return SwapResult("could not move the restored ${target.name} into place", null)
        }
        // Intentionally left for restore() to delete once all swaps succeeded.
        return SwapResult(null, previous.takeIf { hadTarget })
    }
}
