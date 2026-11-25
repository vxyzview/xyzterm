package com.xyzterm

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.xyzterm.file.localBinDir
import com.xyzterm.resources.getFilledString
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings
import com.xyzterm.settings.Preference
import com.xyzterm.settings.Settings
import com.xyzterm.utils.GithubReleasesApi
import com.xyzterm.utils.application
import com.xyzterm.utils.dialogRes
import com.xyzterm.utils.toast
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Only well-formed release tags may reach the APK filename/path. */
internal val RELEASE_TAG = Regex("""^v?\d+\.\d+\.\d+(?:-[\w.]+)?$""")

/** Matches the per-ABI names produced by android.yml and build-pixeldrain.yml. */
internal fun updateApkName(version: String, supportedAbis: Array<String>): String? {
    val abi =
        when {
            supportedAbis.any { it.contains("arm64-v8a") } -> "arm64"
            supportedAbis.any { it.contains("arm") } -> "arm"
            else -> return null
        }
    return "xyzterm-$version-$abi.apk"
}

object UpdateManager {
    private const val UPDATE_OWNER = "vxyzview"
    private const val UPDATE_REPO = "xyzterm"
    private const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val LAST_UPDATE_CHECK_KEY = "last_update_check"
    private val STORE_INSTALLERS =
        setOf(
            "org.fdroid.fdroid",
            "com.looker.droidify",
            "com.machiav3lli.fdroid",
            "com.android.vending",
        )

    /** App-lifetime scope for update work; replaces GlobalScope so jobs are cancellable and testable. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Compares "vX.Y.Z" (or "X.Y.Z") tags; a final release outranks an equal-numbered prerelease. */
    internal fun isNewer(latest: String, installed: String): Boolean {
        fun parse(v: String): List<Int>? {
            val parts = Regex("""v?(\d+)\.(\d+)\.(\d+)""").find(v.trim())?.groupValues?.drop(1) ?: return null
            return parts.map { it.toIntOrNull() ?: return null }
        }
        val a = parse(latest) ?: return false
        val b = parse(installed) ?: return false
        val diff =
            a.zip(b).firstOrNull { it.first != it.second }
                ?: return installed.trim().contains('-') && !latest.trim().contains('-')
        return diff.first > diff.second
    }

    private fun installedVersionName(): String? = runCatching {
        application!!.packageManager.getPackageInfo(application!!.packageName, 0).versionName
    }
        .getOrNull()

    /**
     * True when a store client (F-Droid, Droid-ify, Neo Store, Play) installed the app. Those clients deliver updates
     * themselves; an in-app updater would violate F-Droid policy and bypass the client's signature pinning. Browser /
     * package-installer sideloads are not stores and must keep the self-update prompt.
     */
    @Suppress("DEPRECATION")
    private fun installedFromStore(): Boolean = runCatching {
        val pm = application!!.packageManager
        val installer =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(application!!.packageName).initiatingPackageName
            } else {
                pm.getInstallerPackageName(application!!.packageName)
            }
        installer in STORE_INSTALLERS
    }
        .getOrDefault(false)

    /**
     * Checks the GitHub latest release against the installed app. Throttled to once per day; only runs when the "Check
     * for updates" setting is on. If the installed app was built locally (same version as the release), nothing is
     * offered — no install conflict. If the release is signed with a different key, the user gets a clear message
     * instead of an install failure.
     */
    fun checkForUpdates(activity: Activity) {
        if (!Settings.check_for_update) return
        if (installedFromStore()) return
        val now = System.currentTimeMillis()
        if (now - Preference.getLong(LAST_UPDATE_CHECK_KEY, 0L) < UPDATE_INTERVAL_MS) return
        // Stamp optimistically so parallel opens don't stack checks; a failed
        // fetch below reverts the stamp so tomorrow's open retries sooner than
        // "silently dead for another 24h".
        Preference.setLong(LAST_UPDATE_CHECK_KEY, now)

        scope.launch(Dispatchers.Main) {
            val latest = GithubReleasesApi(UPDATE_OWNER, UPDATE_REPO).fetchLatestVersion()?.trim()
            if (latest == null || !RELEASE_TAG.matches(latest)) {
                Preference.setLong(LAST_UPDATE_CHECK_KEY, 0L)
                return@launch
            }
            val installed = installedVersionName() ?: return@launch
            if (!isNewer(latest, installed)) return@launch
            // The activity may be gone by the time the network call returns.
            if (activity.isFinishing || activity.isDestroyed) return@launch

            val version = latest.removePrefix("v")
            dialogRes(
                activity = activity,
                title = strings.update_available.getString(),
                msg = strings.update_available_msg.getFilledString(version),
                onOk = { downloadAndInstall(activity, version) },
            )
        }
    }

    private fun downloadAndInstall(activity: Activity, version: String) {
        toast(strings.update_downloading.getString())
        scope.launch(Dispatchers.Main) {
            val file = runCatching {
                withContext(Dispatchers.IO) {
                    val apkName = updateApkName(version, Build.SUPPORTED_ABIS) ?: error("unsupported abi")
                    val target = File(application!!.filesDir, "update/$apkName")
                    target.parentFile?.mkdirs()
                    val request =
                        Request.Builder()
                            .url(
                                "https://github.com/$UPDATE_OWNER/$UPDATE_REPO/releases/latest/download/$apkName",
                            )
                            .build()
                    OkHttpClient().newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("download failed: ${response.code}")
                        target.outputStream().use { response.body.byteStream().copyTo(it) }
                    }
                    target
                }
            }
                .getOrNull()

            if (file == null) {
                toast(strings.update_download_failed.getString())
                return@launch
            }

            // Parses the APK signature block — keep it off the UI thread.
            if (!withContext(Dispatchers.IO) { signatureMatches(file.absolutePath) }) {
                toast(strings.update_signature_mismatch.getString())
                return@launch
            }

            val uri = FileProvider.getUriForFile(application!!, "${application!!.packageName}.fileprovider", file)
            // Multi-MB download may outlive the prompt activity.
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val intent =
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            activity.startActivity(intent)
        }
    }

    /** True when the release APK is signed with the same key as the installed app. */
    @Suppress("DEPRECATION")
    private fun signatureMatches(apkPath: String): Boolean {
        val app = application ?: return false
        val pm = app.packageManager
        val installed =
            runCatching { pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES) }.getOrNull()
                ?: return false
        val archive =
            runCatching { pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES) }.getOrNull()
                ?: return false
        val a = installed.signatures ?: return false
        val b = archive.signatures ?: return false
        return a.isNotEmpty() && b.isNotEmpty() && a.contentEquals(b)
    }

    private fun deleteCommonFiles() =
        with(application!!) {
            codeCacheDir.apply {
                if (exists()) {
                    deleteRecursively()
                }
            }

            localBinDir().apply {
                if (exists()) {
                    deleteRecursively()
                }
            }
        }

    @Suppress("DEPRECATION") // migration code intentionally uses deprecated legacy APIs
    fun inspect() =
        with(application!!) {
            // Downloaded update APKs are only needed until the installer consumes
            // them. Keep recent ones (install may still be running), drop the rest.
            File(filesDir, "update").listFiles()?.forEach {
                if (System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L) it.deleteRecursively()
            }

            val lastVersionCode = Settings.last_version_code
            val currentVersionCode = PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))

            // 1.0 ships a fresh per-ABI versioning scheme (1 = arm, 2 = arm64).
            // The old pre-1.0 migration ladder is intentionally removed: running
            // it here would wipe all preferences on every ABI switch or update.
            if (lastVersionCode != currentVersionCode) {
                deleteCommonFiles()
            }

            Settings.last_version_code = currentVersionCode
        }
}
