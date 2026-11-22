package com.xyzterm.terminal

import android.content.Context
import android.os.SystemClock
import android.system.Os
import com.xyzterm.file.child
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class RootfsEntry(
    val path: String,
    val type: String,
    val mode: Int,
    val size: Long,
    val target: String?,
)

internal data class RootfsManifest(val bytes: Long, val entries: List<RootfsEntry>)

/** Hand-rolled TSV parse: no reflection, so R8 cannot break first launch. */
internal fun parseRootfsManifest(text: String): RootfsManifest {
    var bytes = 0L
    val entries = mutableListOf<RootfsEntry>()
    text.lineSequence().forEach { line ->
        val parts = line.split('\t')
        when (parts.firstOrNull()) {
            "B" -> bytes = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            "D",
            "L",
            "F" -> {
                val path = parts.getOrNull(1) ?: return@forEach
                val type = parts[0].lowercase()
                val mode = parts.getOrNull(2)?.toIntOrNull() ?: 0
                val size = if (type == "f") parts.getOrNull(3)?.toLongOrNull() ?: 0L else 0L
                val target = if (type == "l") parts.getOrNull(3) else null
                entries += RootfsEntry(path, type, mode, size, target)
            }
        }
    }
    return RootfsManifest(bytes, entries)
}

/**
 * Copies a rootfs that was already unpacked at build time. No tar, no proot, no ptrace overhead on the device — just
 * files, directories and symlinks.
 */
object RootfsInstaller {
    fun isAvailable(context: Context, asset: ArchmAsset): Boolean {
        val files = context.assets.list("terminal")?.toSet().orEmpty()
        return "rootfs-${asset.abi}.zip" in files && "rootfs-${asset.abi}.tsv" in files
    }

    suspend fun install(
        context: Context,
        asset: ArchmAsset,
        root: File,
        onProgress: suspend (copied: Long, total: Long) -> Unit,
    ) =
        withContext(Dispatchers.IO) {
            val manifestName = "terminal/rootfs-${asset.abi}.tsv"
            val zipName = "terminal/rootfs-${asset.abi}.zip"
            val manifest = context.assets.open(manifestName).use { parseRootfsManifest(it.reader().readText()) }

            if (root.exists()) root.deleteRecursively()
            root.mkdirs()

            val modes = manifest.entries.associate { it.path to it.mode }
            // Android creates dirs with 0700; pacman compares them to the
            // package's 0755, so apply the archived modes to every directory.
            manifest.entries
                .filter { it.type == "d" }
                .forEach { entry ->
                    val dir = File(root, entry.path)
                    dir.mkdirs()
                    if (entry.mode != 0) runCatching { Os.chmod(dir.absolutePath, entry.mode) }
                }
            runCatching { Os.chmod(root.absolutePath, 493) }
            manifest.entries
                .filter { it.type == "l" }
                .forEach { entry -> runCatching { Os.symlink(entry.target, File(root, entry.path).absolutePath) } }

            var copied = 0L
            var lastEmit = 0L
            context.assets.open(zipName).use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val target = File(root, name)
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> zip.copyTo(out, 64 * 1024) }
                        modes[name]?.let { mode -> if (mode != 0) runCatching { Os.chmod(target.absolutePath, mode) } }
                        copied += target.length()
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastEmit >= 250L) {
                            lastEmit = now
                            onProgress(copied, manifest.bytes)
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            onProgress(manifest.bytes, manifest.bytes)
            writeContainerConfig(root)
            File(root.parentFile, ".terminal_setup_ok_DO_NOT_REMOVE").createNewFile()
        }

    private fun writeContainerConfig(root: File) {
        root.child("etc").mkdirs()
        root.child("tmp").mkdirs()
        runCatching { Os.chmod(root.child("tmp").absolutePath, 0b1000000000 or 0b111111111) }
        root.child("etc/hostname").writeText("xyz\n")
        root.child("etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        root
            .child("etc/hosts")
            .writeText(
                "127.0.0.1   localhost.localdomain localhost xyz\n\n" +
                    "::1         localhost.localdomain localhost ip6-localhost ip6-loopback\n" +
                    "fe00::0     ip6-localnet\nff00::0     ip6-mcastprefix\n" +
                    "ff02::1     ip6-allnodes\nff02::2     ip6-allrouters\nff02::3     ip6-allhosts\n",
            )
    }
}
