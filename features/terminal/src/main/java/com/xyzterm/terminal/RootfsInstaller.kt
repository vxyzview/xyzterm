package com.xyzterm.terminal

import android.content.Context
import android.system.Os
import com.google.gson.Gson
import com.xyzterm.file.child
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class RootfsEntry(
    val p: String,
    val t: String,
    val m: Int = 0,
    val s: Long = 0,
    val to: String? = null,
)

private data class RootfsManifest(val bytes: Long, val entries: List<RootfsEntry>)

/**
 * Copies a rootfs that was already unpacked at build time. No tar, no proot, no ptrace overhead on the device — just
 * files, directories and symlinks.
 */
object RootfsInstaller {
    fun isAvailable(context: Context, asset: ArchmAsset): Boolean {
        val files = context.assets.list("terminal")?.toSet().orEmpty()
        return "rootfs-${asset.abi}.zip" in files && "rootfs-${asset.abi}.json" in files
    }

    suspend fun install(
        context: Context,
        asset: ArchmAsset,
        root: File,
        onProgress: (copied: Long, total: Long) -> Unit,
    ) =
        withContext(Dispatchers.IO) {
            val manifestName = "terminal/rootfs-${asset.abi}.json"
            val zipName = "terminal/rootfs-${asset.abi}.zip"
            val manifest =
                context.assets.open(manifestName).use { Gson().fromJson(it.reader(), RootfsManifest::class.java) }

            if (root.exists()) root.deleteRecursively()
            root.mkdirs()

            val modes = manifest.entries.associate { it.p to it.m }
            manifest.entries.filter { it.t == "d" }.forEach { File(root, it.p).mkdirs() }
            manifest.entries
                .filter { it.t == "l" }
                .forEach { entry -> runCatching { Os.symlink(entry.to, File(root, entry.p).absolutePath) } }

            var copied = 0L
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
                        onProgress(copied, manifest.bytes)
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }

            writeContainerConfig(root)
            File(root.parentFile, ".terminal_setup_ok_DO_NOT_REMOVE").createNewFile()
        }

    private fun writeContainerConfig(root: File) {
        root.child("etc").mkdirs()
        root.child("tmp").mkdirs()
        runCatching { Os.chmod(root.child("tmp").absolutePath, 0b111111111) }
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
