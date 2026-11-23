package com.xyzterm.terminal

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings
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

    private data class Release(val url: String, val digest: String)

    fun installLatest(activity: Activity) {
        dialogRes(
            activity = activity,
            title = strings.x11_install.getString(),
            msg = strings.x11_install_confirm.getString(),
            onOk = { downloadAndInstall(activity) },
        )
    }

    private fun downloadAndInstall(activity: Activity) {
        toast(strings.x11_install_fetching.getString())
        scope.launch(Dispatchers.Main) {
            val release = withContext(Dispatchers.IO) { fetchLatest() }
            if (release == null) {
                toast(strings.x11_install_failed.getString())
                return@launch
            }
            toast(strings.x11_install_downloading.getString())
            val file = withContext(Dispatchers.IO) { download(release.url) }
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
        }
    }

    private fun fetchLatest(): Release? = runCatching {
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

    private fun download(url: String): File? = runCatching {
        if (url.isBlank()) return null
        val target = File(application!!.filesDir, "x11/$ASSET")
        target.parentFile?.mkdirs()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            target.outputStream().use { response.body.byteStream().copyTo(it) }
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
