package com.rk

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.rk.commands.KeybindingsManager
import com.rk.file.child
import com.rk.file.localBinDir
import com.rk.file.localDir
import com.rk.file.sandboxDir
import com.rk.file.sandboxHomeDir
import com.rk.file.toFileWrapper
import com.rk.resources.getFilledString
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Preference
import com.rk.settings.Settings
import com.rk.utils.GithubReleasesApi
import com.rk.utils.application
import com.rk.utils.dialogRes
import com.rk.utils.toast
import com.xyzterm.BuildConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

object UpdateManager {
    private const val UPDATE_OWNER = "vxyzview"
    private const val UPDATE_REPO = "xyzterm"
    private const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val LAST_UPDATE_CHECK_KEY = "last_update_check"

    /** App-lifetime scope for update work; replaces GlobalScope so jobs are cancellable and testable. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Compares "vX.Y.Z" (or "X.Y.Z") tags. Returns true if [latest] > [installed]. */
    private fun isNewer(latest: String, installed: String): Boolean {
        val parse = { v: String ->
            Regex("""v?(\d+)\.(\d+)\.(\d+)""").matchEntire(v.trim())?.groupValues?.drop(1)?.map { it.toInt() }
        }
        val a = parse(latest) ?: return false
        val b = parse(installed) ?: return true
        return a.zip(b).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
    }

    private fun installedVersionName(): String? = runCatching {
        application!!.packageManager.getPackageInfo(application!!.packageName, 0).versionName
    }
        .getOrNull()

    /**
     * True when a store client (F-Droid, Droid-ify, Play, ...) installed the app. Those clients deliver updates
     * themselves; an in-app updater would violate F-Droid policy and bypass the client's signature pinning. Only
     * sideloaded APKs (null installer) get the self-update prompt.
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
        installer != null
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
            val latest = GithubReleasesApi(UPDATE_OWNER, UPDATE_REPO).fetchLatestVersion()
            if (latest == null) {
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
                    val target = File(application!!.filesDir, "update/xyzterm-$version.apk")
                    target.parentFile?.mkdirs()
                    val request =
                        Request.Builder()
                            .url(
                                "https://github.com/$UPDATE_OWNER/$UPDATE_REPO/releases/latest/download/xyzterm-$version.apk"
                            )
                            .build()
                    OkHttpClient().newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("download failed: ${response.code}")
                        target.outputStream().use { response.body!!.byteStream().copyTo(it) }
                    }
                    target
                }
            }
                .getOrNull()

            if (file == null) {
                toast(strings.update_download_failed.getString())
                return@launch
            }

            if (!signatureMatches(file.absolutePath)) {
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
        val app = application ?: return true
        val pm = app.packageManager
        val installed =
            runCatching { pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES) }.getOrNull() ?: return true
        val archive =
            runCatching { pm.getPackageArchiveInfo(apkPath, PackageManager.GET_SIGNATURES) }.getOrNull() ?: return false
        val a = installed.signatures?.firstOrNull() ?: return true
        val b = archive.signatures?.firstOrNull() ?: return false
        return a == b
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
            val lastVersionCode = Settings.last_version_code
            val currentVersionCode = PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))

            if (lastVersionCode != currentVersionCode) {
                // App is updated -> Migrate existing files
                if (lastVersionCode <= 40L) {
                    Preference.clearData()
                }

                if (lastVersionCode <= 68L) {
                    val rootfs =
                        sandboxDir().listFiles()?.filter {
                            it.absolutePath != sandboxHomeDir().absolutePath &&
                                it.absolutePath != sandboxDir().child("tmp").absolutePath
                        } ?: emptyList()

                    if (rootfs.isNotEmpty()) {
                        localDir().child(".terminal_setup_ok_DO_NOT_REMOVE").createNewFile()
                    }
                }

                if (lastVersionCode <= 69L) {
                    sandboxDir().child(".cache/.packages_ensured").apply {
                        if (exists()) {
                            delete()
                        }
                    }
                }

                if (lastVersionCode <= 73) {
                    runCatching {
                        val filesToCopy = application!!.cacheDir.listFiles { it.isFile && it.extension.isEmpty() }
                        filesToCopy?.forEach {
                            it.copyTo(
                                application!!.filesDir.child(it.name),
                                overwrite = true,
                            )
                        }
                    }
                }

                if (lastVersionCode <= 76) {
                    runCatching {
                        val filesToCopy = application!!.filesDir.listFiles { it.isFile && it.extension.isEmpty() }
                        filesToCopy?.forEach { it.delete() }
                    }
                }

                if (lastVersionCode <= 80) {
                    val oldShownDisclaimer = Preference.getBoolean("shownDisclaimer", false)
                    Preference.removeKey("shownDisclaimer")
                    if (oldShownDisclaimer) {
                        Preference.setBoolean("shown_disclaimer", true)
                    }

                    val oldOled = Preference.getBoolean("oled", false)
                    Preference.removeKey("oled")
                    if (oldOled) {
                        Preference.setBoolean("amoled", true)
                    }

                    val oldStrictMode = Preference.getBoolean("strictMode", BuildConfig.DEBUG)
                    Preference.removeKey("strictMode")
                    if (oldStrictMode != BuildConfig.DEBUG) {
                        Preference.setBoolean("strict_mode", oldStrictMode)
                    }

                    val oldDesktopMode = Preference.getBoolean("desktopMode", false)
                    Preference.removeKey("desktopMode")
                    if (oldDesktopMode) {
                        Preference.setBoolean("desktop_mode", true)
                    }

                    val defaultLang = application!!.resources.configuration.locales[0].language
                    val oldCurrentLang = Preference.getString("currentLang", defaultLang)
                    Preference.removeKey("currentLang")
                    if (oldCurrentLang != defaultLang) {
                        Preference.setString("current_lang", oldCurrentLang)
                    }
                }

                if (lastVersionCode <= 81) {
                    scope.launch {
                        runCatching {
                            application!!.filesDir.child("projects").toFileWrapper().renameTo("drawerTabs")
                            application!!.filesDir.child("currentTab").toFileWrapper().renameTo("currentDrawerTab")
                            application!!
                                .filesDir
                                .child("expanded_filetree_nodes")
                                .toFileWrapper()
                                .renameTo("expandedFileTree")
                        }
                    }
                }

                if (lastVersionCode <= 94L) {
                    runCatching {
                        val legacySeccomp = Preference.getBoolean("seccomp", false)

                        val newValue =
                            if (legacySeccomp) {
                                "yes"
                            } else {
                                "unspecified"
                            }

                        Preference.setString("seccomp_mode", newValue)
                        Preference.removeKey("seccomp")
                    }
                }

                if (lastVersionCode <= 99) {
                    runCatching {
                        KeybindingsManager.migrate()
                        KeybindingsManager.loadKeybindings()
                    }
                }

                deleteCommonFiles()
            }

            Settings.last_version_code = currentVersionCode
        }
}
