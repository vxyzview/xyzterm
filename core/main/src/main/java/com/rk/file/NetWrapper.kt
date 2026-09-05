package com.rk.file

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale

class NetWrapper(private val url: URL) : FileObject {
    /** Explicit port, or the scheme default when unspecified (http:80, https:443). */
    private fun effectivePort(url: URL): Int = if (url.port != -1) url.port else url.defaultPort

    private fun openConnection(): HttpURLConnection {
        // ponytail: same-host redirect following — block cross-host hijack (a tampered
        // http response redirecting to an attacker host) while allowing legitimate
        // same-host redirects (GitHub /releases/latest, CDN shortlinks) to work.
        // HttpURLConnection's instanceFollowRedirects is all-or-nothing, so we
        // manually follow only same-host redirects, surfacing a cross-host 3xx to the
        // caller (who treats it as an error) instead of following it.
        var currentUrl = url
        var hops = 0
        while (true) {
            val conn =
                (currentUrl.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    requestMethod = "GET"
                }
            conn.connect()
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                val next = location?.let { URL(currentUrl, it) }
                // Same-origin only: scheme + host (case-insensitive) + effective
                // port. A tampered http response must not bounce the fetch to an
                // attacker host, a downgraded scheme, or a different port.
                val sameOrigin =
                    next != null &&
                        next.protocol.equals(currentUrl.protocol, ignoreCase = true) &&
                        next.host.equals(currentUrl.host, ignoreCase = true) &&
                        effectivePort(next) == effectivePort(currentUrl)
                if (!sameOrigin || ++hops > 10) {
                    return conn // cross-origin, loop, or malformed redirect: surface the 3xx
                }
                conn.disconnect()
                currentUrl = next
            } else {
                return conn // final response
            }
        }
    }

    override suspend fun listFiles(): List<FileObject> = emptyList()

    override fun isDirectory(): Boolean = false

    override fun isFile(): Boolean = true

    override fun getName(): String {
        return url.path.substringAfterLast('/', "")
    }

    override fun getExtension(): String {
        return MimeTypeMap.getFileExtensionFromUrl(url.toString())
    }

    override suspend fun getParentFile(): FileObject? {
        val path = url.path
        val parent = path.substringBeforeLast('/', "")
        if (parent.isEmpty()) return null
        return NetWrapper(URL(url.protocol, url.host, url.port, parent))
    }

    override suspend fun exists(): Boolean {
        return try {
            openConnection().run {
                connect()
                responseCode in 200..299
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun createNewFile(): Boolean = false

    override suspend fun getCanonicalPath(): String = url.toString()

    override suspend fun mkdir(): Boolean = false

    override suspend fun mkdirs(): Boolean = false

    override suspend fun writeText(text: String) {
        throw UnsupportedOperationException("URL is read-only")
    }

    override suspend fun getInputStream(): InputStream {
        return withContext(Dispatchers.IO) { openConnection().inputStream }
    }

    override suspend fun <R> useInputStream(block: suspend (InputStream) -> R): R {
        return withContext(Dispatchers.IO) { openConnection().inputStream.use { block(it) } }
    }

    override suspend fun getOutputStream(append: Boolean): OutputStream {
        throw UnsupportedOperationException("URL is read-only")
    }

    override fun getAbsolutePath(): String = url.toString()

    override suspend fun length(): Long {
        return runCatching {
                openConnection().contentLengthLong
            }
            .getOrDefault(0L)
    }

    override suspend fun delete(): Boolean = false

    override suspend fun toUri(): Uri = Uri.parse(url.toString())

    override suspend fun getMimeType(context: Context): String? {
        val ext = MimeTypeMap.getFileExtensionFromUrl(url.toString())
        return if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase(Locale.getDefault()))
        } else {
            null
        }
    }

    override suspend fun renameTo(string: String): Boolean = false

    override suspend fun hasChild(name: String): Boolean = false

    override suspend fun createChild(createFile: Boolean, name: String): FileObject? = null

    override fun canWrite(): Boolean = false

    override fun canRead(): Boolean = true

    override fun canExecute(): Boolean = false

    override suspend fun lastModified(): Long? = null

    override suspend fun getChild(name: String): FileObject {
        throw UnsupportedOperationException("URL is not a directory")
    }

    override suspend fun readText(): String {
        return getInputStream().bufferedReader().use { it.readText() }
    }

    override suspend fun readText(charset: Charset): String {
        return getInputStream().reader(charset).use { it.readText() }
    }

    override suspend fun writeText(content: String, charset: Charset): Boolean = false

    override fun isSymlink(): Boolean = false
}
