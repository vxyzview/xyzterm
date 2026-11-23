package com.xyzterm.terminal

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings
import com.xyzterm.utils.DialogHost
import com.xyzterm.utils.DialogRequest
import com.xyzterm.utils.application
import com.xyzterm.utils.dialogRes
import com.xyzterm.utils.toast
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Installs the latest Termux:X11 release from GitHub after verifying its published SHA-256. */
object X11Installer {
    private const val OWNER = "termux"
    private const val REPO = "termux-x11"
    private const val ASSET = "termux-x11-universal-release.apk"
    private const val PACKAGE = "com.termux.x11"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = OkHttpClient()

    private var progress by mutableStateOf(0f)
    private var progressKnown by mutableStateOf(false)

    private data class Release(val url: String, val digest: String)

    @Composable
    private fun ProgressContent() {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val track = MaterialTheme.colorScheme.surfaceContainerHighest
                if (progressKnown) {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.size(64.dp),
                        strokeWidth = 8.dp,
                        trackColor = track,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(64.dp),
                        strokeWidth = 8.dp,
                        trackColor = track,
                    )
                }
                Text(
                    text = stringResource(strings.x11_install_progress_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text =
                        if (progressKnown) {
                            "${(progress * 100).toInt()}%"
                        } else {
                            stringResource(strings.x11_install_downloading)
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (progressKnown) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = stringResource(strings.x11_install_progress_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    private fun showProgress(): Long {
        progress = 0f
        progressKnown = false
        val id = DialogHost.nextId()
        DialogHost.push(DialogRequest(id = id, createdAt = System.currentTimeMillis(), cancelable = false) { ProgressContent() })
        return id
    }

    fun installLatest(activity: Activity) {
        dialogRes(
            activity = activity,
            title = strings.x11_install.getString(),
            msg = strings.x11_install_confirm.getString(),
            onOk = { downloadAndInstall(activity) },
        )
    }

    private fun downloadAndInstall(activity: Activity) {
        val dialogId = showProgress()
        scope.launch(Dispatchers.Main) {
            try {
                val release = withContext(Dispatchers.IO) { fetchLatest() }
                if (release == null) {
                    toast(strings.x11_install_failed.getString())
                    return@launch
                }
                val file =
                    withContext(Dispatchers.IO) {
                        download(release.url) { value, known ->
                            withContext(Dispatchers.Main) {
                                progress = value
                                progressKnown = known
                            }
                        }
                    }
                if (file == null) {
                    toast(strings.x11_install_failed.getString())
                    return@launch
                }
                if (!withContext(Dispatchers.IO) { verify(file, release.digest) }) {
                    file.delete()
                    toast(strings.x11_install_digest_failed.getString())
                    return@launch
                }
                if (withContext(Dispatchers.IO) { signatureConflict(file) }) {
                    file.delete()
                    toast(strings.x11_install_signature_conflict.getString())
                    return@launch
                }
                // The download may outlive the settings activity.
                if (activity.isFinishing || activity.isDestroyed) return@launch
                val uri = FileProvider.getUriForFile(application!!, "${application!!.packageName}.fileprovider", file)
                val intent =
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                activity.startActivity(intent)
            } finally {
                DialogHost.remove(dialogId)
            }
        }
    }

    private fun fetchLatest(): Release? =
        runCatching {
            val request = Request.Builder().url("https://api.github.com/repos/$OWNER/$REPO/releases/latest").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val json = JSONObject(response.body.string())
                val assets = json.optJSONArray("assets") ?: return null
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name") == ASSET) {
                        return Release(
                            url = asset.optString("browser_download_url"),
                            digest = asset.optString("digest"),
                        )
                    }
                }
                null
            }
        }
            .getOrNull()

    private suspend fun download(
        url: String,
        onProgress: suspend (Float, Boolean) -> Unit,
    ): File? =
        runCatching {
            if (url.isBlank()) return null
            val target = File(application!!.filesDir, "x11/$ASSET")
            target.parentFile?.mkdirs()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body
                val total = body.contentLength()
                var bytes = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    target.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            bytes += read
                            if (total > 0) {
                                val percent = (bytes * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(bytes.toFloat() / total, true)
                                }
                            }
                        }
                    }
                }
                if (total <= 0) onProgress(0f, false)
            }
            target
        }
            .getOrNull()

    private fun verify(file: File, digest: String): Boolean {
        val expected = digest.substringAfter(':', "").ifEmpty { digest }.trim()
        if (expected.length != 64) return false
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }.equals(expected, ignoreCase = true)
    }

    @Suppress("DEPRECATION")
    private fun signatureConflict(apk: File): Boolean {
        val pm = application?.packageManager ?: return false
        val installed =
            runCatching { pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES) }.getOrNull() ?: return false
        val archive =
            runCatching { pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES) }.getOrNull()
                ?: return false
        val a = installed.signatures?.firstOrNull() ?: return false
        val b = archive.signatures?.firstOrNull() ?: return false
        return a != b
    }
}
