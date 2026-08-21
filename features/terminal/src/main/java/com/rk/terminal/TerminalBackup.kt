package com.rk.terminal

import com.rk.file.child
import com.rk.file.localDir
import com.rk.file.sandboxDir
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Creates and prunes terminal environment backups (sandbox tar.gz archives). */
object TerminalBackup {
    private const val KEEP = 3

    private val EXCLUDES =
        arrayOf(
            "--exclude=dev",
            "--exclude=sys",
            "--exclude=proc",
            "--exclude=system",
            "--exclude=apex",
            "--exclude=vendor",
            "--exclude=data",
            "--exclude=home",
            "--exclude=root",
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
            process.waitFor() == 0
        }

    /** Backs up the sandbox to a timestamped file in [backupDir], keeping the newest [KEEP]. */
    suspend fun autoBackup(): Boolean {
        val dir = backupDir()
        dir.mkdirs()
        val target = File(dir, "terminal-backup-${System.currentTimeMillis()}.tar.gz")
        if (!create(target)) {
            target.delete()
            return false
        }
        dir.listFiles { f -> f.name.startsWith("terminal-backup-") }
            ?.sortedByDescending { it.name }
            ?.drop(KEEP)
            ?.forEach { it.delete() }
        return true
    }
}
