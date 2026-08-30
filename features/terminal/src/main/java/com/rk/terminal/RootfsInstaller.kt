package com.rk.terminal

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where a rootfs asset comes from and where the verified copy must land. */
data class RootfsSource(val url: String, val outputFile: File, val sha256: String? = null)

/** Progress event for one source; emitted on the main thread, throttled to ~250ms. */
data class InstallProgress(val fileName: String, val downloadedBytes: Long, val totalBytes: Long)

/** Sealed outcome of [RootfsInstaller.install]. */
sealed interface InstallResult {
    data class Success(val stage: NEXT_STAGE) : InstallResult

    data class Failure(val error: Throwable, val file: File?) : InstallResult
}

/**
 * Pure install pipeline: HTTP resume, sha256 verification, gzip-trailer check, setExecutable.
 *
 * Two adapters call into this: the first-launch install button and the cold-start re-install
 * when the rootfs is missing. Behaviour is byte-for-byte the same as the previous inline
 * `Terminal.setupEnvironment / downloadFile / isValidGzip / sha256Matches` quartet.
 */
class RootfsInstaller(private val context: Context) {

    suspend fun install(sources: List<RootfsSource>, onProgress: (InstallProgress) -> Unit): InstallResult {
        return withContext(Dispatchers.IO) {
            var currentFile: File? = null
            try {
                sources.forEach { source ->
                    val outputFile = source.outputFile
                    currentFile = outputFile

                    outputFile.parentFile?.mkdirs()

                    if (
                        !outputFile.exists() ||
                        !isValidGzip(outputFile) ||
                        !sha256Matches(outputFile, source.sha256)
                    ) {
                        // Existing file is a leftover from a killed download, a
                        // pre-resume version, or a stale rootfs from an older app
                        // release (hash mismatch): discard it, the download writes
                        // a .part sibling and only renames it once verified.
                        outputFile.delete()
                        downloadOne(
                            url = source.url,
                            outputFile = outputFile,
                            onProgress = { downloaded, total ->
                                onProgress(InstallProgress(outputFile.name, downloaded, total))
                            },
                        )
                    } else {
                        // Report existing file as already downloaded
                        onProgress(InstallProgress(outputFile.name, outputFile.length(), outputFile.length()))
                    }

                    if (source.sha256 != null && !sha256Matches(outputFile, source.sha256)) {
                        outputFile.delete()
                        throw Exception("Rootfs checksum mismatch: ${outputFile.name}")
                    }

                    runCatching { outputFile.setExecutable(true) }.onFailure { it.printStackTrace() }
                }

                InstallResult.Success(getNextStage(context))
            } catch (e: Exception) {
                e.printStackTrace()
                if (currentFile?.exists() == true) {
                    currentFile.delete()
                }
                InstallResult.Failure(e, currentFile)
            }
        }
    }

    private suspend fun downloadOne(
        url: String,
        outputFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        val client =
            OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.MINUTES)
                .readTimeout(1, TimeUnit.MINUTES)
                .writeTimeout(1, TimeUnit.MINUTES)
                .callTimeout(10, TimeUnit.MINUTES)
                .build()

        // Download to a .part sibling: a killed download leaves a resumable
        // partial, and a partial can never be mistaken for a complete rootfs.
        val partFile = File(outputFile.parentFile, outputFile.name + ".part")

        // Resume from where the previous attempt stopped; the server answers
        // 206 with the remainder, or 200 if it ignores the Range header.
        val rangeStart = if (partFile.exists()) partFile.length() else 0L
        val request =
            Request.Builder()
                .url(url)
                .apply { if (rangeStart > 0) header("Range", "bytes=$rangeStart-") }
                .build()

        var startedAt = 0L
        client.newCall(request).execute().use { response ->
            when (response.code) {
                206 -> startedAt = rangeStart
                200 -> {
                    startedAt = 0
                    partFile.delete()
                }
                416 -> {
                    // Server reports nothing past what we already have: the
                    // partial is complete, integrity is verified below.
                }
                else -> {
                    // e.g. 404 after a re-release: the partial is stale, drop
                    // it so the next attempt starts clean.
                    partFile.delete()
                    throw Exception("Failed to download file: ${response.code}")
                }
            }

            val body = response.body
            if (body == null) throw Exception("Empty response body")
            val totalBytes = startedAt + body.contentLength()

            var downloadedBytes = startedAt
            // Throttle progress: hopping to the main thread and recomposing the
            // progress UI on every 8 KiB block (tens of thousands of times for a
            // 200-400 MB rootfs) janks the setup screen. Emit at most every ~250ms
            // and always send the final 100% update.
            val THROTTLE_MS = 250L
            var lastEmit = 0L

            FileOutputStream(partFile, startedAt > 0).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastEmit >= THROTTLE_MS) {
                            lastEmit = now
                            withContext(Dispatchers.Main) { onProgress(downloadedBytes, totalBytes) }
                        }
                    }
                    withContext(Dispatchers.Main) { onProgress(downloadedBytes, totalBytes) }
                }
            }
        }

        // Reading the file to EOF verifies the gzip CRC-32 trailer: a stream
        // cut short cannot pass, so the file is only promoted to the real name
        // once it is actually complete and uncorrupted.
        if (!isValidGzip(partFile)) {
            partFile.delete()
            throw Exception("Downloaded file failed integrity check: ${outputFile.name}")
        }
        if (!partFile.renameTo(outputFile)) {
            throw Exception("Failed to move downloaded file: ${outputFile.name}")
        }
    }

    private fun isValidGzip(file: File): Boolean =
        runCatching {
            GZIPInputStream(file.inputStream()).use { input ->
                val buffer = ByteArray(8 * 1024)
                while (input.read(buffer) != -1) {
                    // Drain to EOF: GZIPInputStream only verifies the trailer CRC
                    // once the stream is fully consumed.
                }
            }
        }.isSuccess

    private fun sha256Matches(file: File, expectedSha256: String?): Boolean {
        if (expectedSha256.isNullOrEmpty()) return true
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest
                .digest()
                .joinToString("") { "%02x".format(it) }
                .equals(expectedSha256, ignoreCase = true)
        }
            .getOrElse {
                it.printStackTrace()
                false
            }
    }
}
