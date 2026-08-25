package com.rk.exec

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Pure-Kotlin .tar.gz extractor (no external deps, no proot/ptrace overhead).
 * Supports regular files, dirs, symlinks, hardlinks (link2symlink fallback),
 * PAX and GNU longname/longlink headers, base-256 sizes; skips device/fifo
 * entries. Rejects absolute paths and ".." segments, and refuses to write
 * through a symlinked parent that escapes [destDir].
 */
object TarExtractor {

    private const val BLOCK = 512
    private const val COPY_BUFFER = 64 * 1024
    private const val MAX_METADATA = 1048576
    private const val THROTTLE_MS = 250L
    private const val MODE_MASK = 0b111111111

    fun extract(tarGz: File, destDir: File, onProgress: (Float) -> Unit) {
        val root = destDir.canonicalFile
        root.mkdirs()
        val total = tarGz.length().coerceAtLeast(1L)

        GZIPInputStream(ProgressStream(FileInputStream(tarGz), total, onProgress), COPY_BUFFER).use { gzip ->
            val header = ByteArray(BLOCK)
            // Hoisted out of the per-entry loop: reallocating a 64KB buffer for
            // each of ~30k entries churns gigabytes of garbage.
            val buf = ByteArray(COPY_BUFFER)
            var pendingPax: Map<String, String> = emptyMap()
            val globalPax = HashMap<String, String>()
            var longName: String? = null
            var longLink: String? = null
            val dirModes = ArrayList<Pair<File, Int>>()
            val madeDirs = HashSet<String>()
            val canonicalCache = HashMap<String, String>()

            while (true) {
                if (!readBlock(gzip, header)) break
                if (isZeroBlock(header)) break

                var name = parseString(header, 0, 100)
                var size = parseSize(header, 124)
                val mode = parseOctal(header, 100, 8)
                val type = header[156].toInt().toChar()
                var linkName = parseString(header, 157, 100)

                if (parseString(header, 257, 6).startsWith("ustar")) {
                    val prefix = parseString(header, 345, 155)
                    if (prefix.isNotEmpty()) name = "$prefix/$name"
                }

                when (type) {
                    'x' -> { pendingPax = parsePax(payload(gzip, size)); skipPadding(gzip, size, buf) }
                    'g' -> { globalPax.putAll(parsePax(payload(gzip, size))); skipPadding(gzip, size, buf) }
                    'L' -> {
                        longName = String(payload(gzip, size), Charsets.UTF_8).trimEnd('\u0000')
                        skipPadding(gzip, size, buf)
                    }
                    'K' -> {
                        longLink = String(payload(gzip, size), Charsets.UTF_8).trimEnd('\u0000')
                        skipPadding(gzip, size, buf)
                    }
                    else -> {
                        fun pax(key: String): String? = pendingPax[key] ?: globalPax[key]

                        val entryName = pax("path") ?: longName ?: name
                        val entryLink = pax("linkpath") ?: longLink ?: linkName
                        pax("size")?.let {
                            size =
                                it.toLongOrNull()?.takeIf { s -> s >= 0 }
                                    ?: throw IOException("Malformed PAX size: $it")
                        }
                        longName = null
                        longLink = null
                        pendingPax = emptyMap()

                        val target = resolveSecure(root, entryName, canonicalCache)

                        when (type) {
                            '0', '\u0000', '7' -> {
                                ensureParentDir(target, madeDirs)
                                if (target.exists()) target.delete()
                                FileOutputStream(target).use { out ->
                                    var remaining = size
                                    while (remaining > 0) {
                                        val n = gzip.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                        if (n < 0) throw IOException("Truncated archive at ${target.name}")
                                        out.write(buf, 0, n)
                                        remaining -= n
                                    }
                                }
                                skipPadding(gzip, size, buf)
                                Os.chmod(target.path, mode and MODE_MASK)
                            }

                            '5' -> {
                                skipPayload(gzip, size, buf)
                                ensureParentDir(target, madeDirs)
                                target.mkdirs()
                                dirModes.add(target to (mode and MODE_MASK))
                            }

                            '2' -> {
                                skipPayload(gzip, size, buf)
                                ensureParentDir(target, madeDirs)
                                if (target.exists()) target.delete()
                                Os.symlink(entryLink, target.path)
                                // A new symlink changes what canonical paths resolve
                                // to; drop the whole cache rather than track subtrees.
                                canonicalCache.clear()
                            }

                            '1' -> {
                                skipPayload(gzip, size, buf)
                                ensureParentDir(target, madeDirs)
                                val linkTarget = resolveSecure(root, entryLink, canonicalCache)
                                try {
                                    Os.link(linkTarget.path, target.path)
                                } catch (e: ErrnoException) {
                                    if (e.errno != OsConstants.EXDEV) throw e
                                    // Mirrors proot --link2symlink: hardlinks fail across
                                    // mounts/devices, fall back to a symlink to the target.
                                    if (target.exists()) target.delete()
                                    Os.symlink(linkTarget.path, target.path)
                                    canonicalCache.clear()
                                }
                            }

                            else -> skipPayload(gzip, size, buf)
                        }
                    }
                }
            }

            // Applied last so restrictive directory modes cannot block children.
            dirModes.forEach { (dir, dirMode) -> Os.chmod(dir.path, dirMode) }
        }

        onProgress(1f)
    }

    /** Counts compressed bytes consumed and reports progress at most ~4x/sec. */
    private class ProgressStream(
        input: InputStream,
        private val total: Long,
        private val onProgress: (Float) -> Unit,
    ) : FilterInputStream(input) {

        private var count = 0L

        private var lastEmit = 0L

        private fun emit(n: Long) {
            count += n
            val now = System.nanoTime() / 1_000_000
            if (now - lastEmit >= THROTTLE_MS) {
                lastEmit = now
                onProgress((count.toFloat() / total).coerceIn(0f, 1f))
            }
        }

        override fun read(): Int = super.read().also { if (it >= 0) emit(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) emit(it.toLong()) }

        override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) emit(it) }
    }

    private fun readBlock(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) {
                if (off == 0) return false
                throw IOException("Truncated archive header")
            }
            off += n
        }
        return true
    }

    private fun isZeroBlock(block: ByteArray): Boolean = block.all { it == 0.toByte() }

    private fun payload(input: InputStream, size: Long): ByteArray {
        if (size < 0 || size > MAX_METADATA) throw IOException("Oversized tar metadata entry: $size")
        val out = ByteArray(size.toInt())
        var off = 0
        while (off < out.size) {
            val n = input.read(out, off, out.size - off)
            if (n < 0) throw IOException("Truncated archive metadata")
            off += n
        }
        return out
    }

    private fun skipPayload(input: InputStream, size: Long, buf: ByteArray) {
        var remaining = size
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) throw IOException("Truncated archive entry")
            remaining -= n
        }
        skipPadding(input, size, buf)
    }

    /** Tar pads every entry payload to a 512-byte block boundary. */
    private fun skipPadding(input: InputStream, size: Long, buf: ByteArray) {
        var padding = (BLOCK - (size % BLOCK)) % BLOCK
        while (padding > 0) {
            val n = input.read(buf, 0, minOf(padding, buf.size.toLong()).toInt())
            if (n < 0) throw IOException("Truncated archive padding")
            padding -= n
        }
    }

    /** Memoized mkdirs: parent dirs repeat across tens of thousands of entries. */
    private fun ensureParentDir(target: File, madeDirs: MutableSet<String>) {
        val parent = target.parentFile ?: return
        if (madeDirs.add(parent.path)) parent.mkdirs()
    }

    private fun resolveSecure(root: File, name: String, canonicalCache: MutableMap<String, String>): File {
        if (name.isEmpty() || name.startsWith("/")) throw IOException("Illegal tar entry name: $name")
        val segments = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (".." in segments) throw IOException("Illegal tar entry path: $name")
        val resolved = File(root, segments.joinToString("/"))
        val parent = resolved.parentFile ?: return resolved
        val rootPath = root.path + File.separator
        // realpath per unique parent only; the cache is dropped wholesale when a
        // symlink is created since it changes resolution for its subtree.
        val canonical =
            canonicalCache.getOrPut(parent.path) {
                if (parent.exists()) parent.canonicalPath else ""
            }
        if (canonical.isNotEmpty() && canonical != root.path && !canonical.startsWith(rootPath)) {
            throw IOException("Tar entry escapes destination via symlinked parent: $name")
        }
        return resolved
    }

    private fun parseString(header: ByteArray, off: Int, len: Int): String {
        var end = 0
        while (end < len && header[off + end] != 0.toByte()) end++
        return String(header, off, end, Charsets.UTF_8)
    }

    private fun parseOctal(header: ByteArray, off: Int, len: Int): Int {
        val s = parseString(header, off, len).trim(' ', '\u0000')
        return if (s.isEmpty()) 0 else s.toInt(8)
    }

    private fun parseSize(header: ByteArray, off: Int): Long {
        if (header[off].toInt() and 0x80 != 0) {
            var value = (header[off].toInt() and 0x7f).toLong()
            for (i in 1 until 12) {
                value = (value shl 8) or (header[off + i].toInt() and 0xff).toLong()
            }
            return value
        }
        val s = parseString(header, off, 12).trim(' ', '\u0000')
        return if (s.isEmpty()) 0L else s.toLong(8)
    }

    private fun parsePax(bytes: ByteArray): Map<String, String> {
        val map = HashMap<String, String>()
        var i = 0
        while (i < bytes.size) {
            var sp = i
            while (sp < bytes.size && bytes[sp] != ' '.code.toByte()) sp++
            val len = String(bytes, i, sp - i, Charsets.UTF_8).trim().toIntOrNull() ?: break
            if (len <= 0 || i + len > bytes.size) break
            val record = String(bytes, sp + 1, i + len - sp - 1, Charsets.UTF_8).removeSuffix("\n")
            val eq = record.indexOf('=')
            if (eq > 0) map[record.substring(0, eq)] = record.substring(eq + 1)
            i += len
        }
        return map
    }
}
