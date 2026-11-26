package com.xyzterm.terminal

import android.content.Context
import android.os.SystemClock
import android.os.Trace
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

/** Rejects absolute paths and any `..` segment before they touch the filesystem. */
internal fun isSafeEntryPath(path: String): Boolean = !path.startsWith("/") && path.split('/').none { it == ".." }

/** A link target is safe only if it stays inside the rootfs relative to the link's own directory. */
internal fun isSafeLinkTarget(linkPath: String, target: String): Boolean {
    if (target.startsWith("/")) return false
    val segments = mutableListOf<String>()
    (File(linkPath).parent.orEmpty() + "/" + target).split('/').forEach { part ->
        when {
            part.isEmpty() || part == "." -> Unit
            part == ".." -> {
                if (segments.isEmpty()) return false
                segments.removeAt(segments.lastIndex)
            }
            else -> segments += part
        }
    }
    return true
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
            // Named trace section so macrobenchmark can measure the real first-launch cost.
            Trace.beginSection("rootfs_install")
            try {
                installInternal(context, asset, root, onProgress)
            } finally {
                Trace.endSection()
            }
        }

    private suspend fun installInternal(
        context: Context,
        asset: ArchmAsset,
        root: File,
        onProgress: suspend (copied: Long, total: Long) -> Unit,
    ) {
        val manifestName = "terminal/rootfs-${asset.abi}.tsv"
        val zipName = "terminal/rootfs-${asset.abi}.zip"
        val manifest = context.assets.open(manifestName).use { parseRootfsManifest(it.reader().readText()) }

        // A lost marker must not destroy a working userspace: recreate it
        // instead. Only a clearly incomplete tree is replaced.
        val marker = File(root.parentFile, ".terminal_setup_ok_DO_NOT_REMOVE")
        if (isInstallComplete(root, manifest)) {
            marker.createNewFile()
            writeContainerConfig(root)
            onProgress(manifest.bytes, manifest.bytes)
            return
        }
        if (root.exists()) root.deleteRecursively()
        root.mkdirs()

        val modes = manifest.entries.filter { isSafeEntryPath(it.path) }.associate { it.path to it.mode }
        // Android creates dirs with 0700; pacman compares them to the
        // package's 0755, so apply the archived modes to every directory.
        manifest.entries
            .filter { it.type == "d" && isSafeEntryPath(it.path) }
            .forEach { entry ->
                val dir = File(root, entry.path)
                dir.mkdirs()
                if (entry.mode != 0) runCatching { Os.chmod(dir.absolutePath, entry.mode) }
            }
        runCatching { Os.chmod(root.absolutePath, 493) }

        // Files first: a malicious symlink must never redirect a later write outside the rootfs.
        var copied = 0L
        var lastEmit = 0L
        context.assets.open(zipName).use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!isSafeEntryPath(name)) {
                        zip.closeEntry()
                        entry = zip.nextEntry
                        continue
                    }
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
        manifest.entries
            .filter { it.type == "l" }
            .forEach { entry ->
                val target = entry.target
                if (target == null || !isSafeEntryPath(entry.path) || !isSafeLinkTarget(entry.path, target)) {
                    return@forEach
                }
                runCatching { Os.symlink(target, File(root, entry.path).absolutePath) }
            }
        writeContainerConfig(root)
        marker.createNewFile()
    }

    internal fun isInstallComplete(root: File, manifest: RootfsManifest): Boolean {
        // Only existence matters here. Comparing byte length used to wipe a working
        // rootfs when a user edited a config file and the marker was missing.
        val files = manifest.entries.filter { it.type == "f" && isSafeEntryPath(it.path) }
        // Symlinks are created after the file loop; without this an interruption
        // between the two is mistaken for a finished install.
        val links = manifest.entries.filter { it.type == "l" && isSafeEntryPath(it.path) }
        return files.isNotEmpty() &&
            files.all { File(root, it.path).isFile } &&
            links.all { File(root, it.path).exists() }
    }

    private fun writeContainerConfig(root: File) {
        root.child("etc").mkdirs()
        root.child("tmp").mkdirs()
        runCatching { Os.chmod(root.child("tmp").absolutePath, 0b1000000000 or 0b111111111) }
        root.child("etc/hostname").writeText("xyz\n")
        val dns = systemDns().ifEmpty { listOf("8.8.8.8", "8.8.4.4") }
        root.child("etc/resolv.conf").writeText(dns.joinToString("\n") { "nameserver $it" } + "\n")
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
