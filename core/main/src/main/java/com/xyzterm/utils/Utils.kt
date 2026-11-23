package com.xyzterm.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.PictureDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.net.toUri
import com.caverock.androidsvg.SVG
import com.xyzterm.extension.ActivityProvider
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings
import com.xyzterm.settings.Settings
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlin.math.roundToInt

fun runOnUiThread(runnable: Runnable) {
    // No coroutine needed: post directly to the main looper.
    Handler(Looper.getMainLooper()).post(runnable)
}

fun toast(@StringRes resId: Int) {
    toast(resId.getString())
}

fun toast(message: String?) {
    if (message.isNullOrBlank()) {
        Log.w("UTILS", "Toast with null or empty message")
        return
    }
    if (message == "Job was cancelled") {
        Log.w("TOAST", message)
        return
    }

    runOnUiThread {
        val context = ActivityProvider.currentActivity as? Context
        if (context != null) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } else {
            Log.w("Utils", "no valid ui context available for making toast: $message")
        }
    }
}

/** Returns true if the currently selected user theme is dark. If it's set to system, the system theme is used. */
fun isDarkTheme(ctx: Context): Boolean {
    return when (Settings.theme_mode) {
        AppCompatDelegate.MODE_NIGHT_YES -> true
        AppCompatDelegate.MODE_NIGHT_NO -> false
        else -> isSystemInDarkTheme(ctx)
    }
}

/** Returns true if the system theme is dark. **NOTE:** Prefer [isDarkTheme] to respect user settings. */
fun isSystemInDarkTheme(ctx: Context): Boolean {
    return ((ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES)
}

fun dpToPx(dp: Float, ctx: Context): Int {
    val density = ctx.resources.displayMetrics.density
    return (dp * density).roundToInt()
}

fun isMainThread(): Boolean {
    return Looper.myLooper() == Looper.getMainLooper()
}

fun Context.openUrl(url: String) {
    val uri = runCatching { url.toUri() }.getOrNull() ?: return
    // ponytail: allow common safe schemes that launch system handlers without
    // executing code. Block javascript:/file:/content:/data: as they can escape
    // sandbox or run arbitrary code. A theme/extension-supplied URL must not be
    // able to craft a dangerous scheme.
    val scheme = uri.scheme
    // intent:/data:/file:/content:/javascript: are deliberately excluded — they can
    // wrap an arbitrary intent or execute code from an untrusted theme/extension URL.
    if (scheme !in setOf("http", "https", "mailto", "tel", "sms", "geo", "market")) return
    val intent = Intent(Intent.ACTION_VIEW, uri)
    // A theme/extension-supplied URL may resolve to a scheme with no handler
    // on this device — don't crash the caller.
    runCatching {
        // FLAG_ACTIVITY_NEW_TASK is required when the context is not an Activity.
        if (this !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}

@Suppress("DEPRECATION")
fun origin(): String {
    return application!!.run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return@run packageManager.getInstallSourceInfo(packageName).installingPackageName.toString()
        } else {
            return@run packageManager.getInstallerPackageName(packageName).toString()
        }
    }
}

fun copyToClipboard(label: String, text: String, showToast: Boolean = true) {
    val clipboard = application!!.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
    if (showToast) {
        toast(strings.copied)
    }
}

fun copyToClipboard(text: String, showToast: Boolean = true) {
    copyToClipboard(label = "xyzterm", text, showToast = showToast)
}

fun getSourceDirOfPackage(context: Context, packageName: String): String? {
    return try {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        info.sourceDir
    } catch (e: PackageManager.NameNotFoundException) {
        null // App not found
    }
}

fun getTempDir(): File {
    val tmp = File(application!!.filesDir.parentFile, "tmp")
    if (!tmp.exists()) {
        tmp.mkdir()
    }
    return tmp
}

fun hasBinaryChars(text: String): Boolean {
    val threshold = 0.3
    val checkedCharacters = 1024

    val checkText = text.take(checkedCharacters)
    if (checkText.isEmpty()) return false

    // Null character
    if (checkText.any { it.code == 0 }) return true

    // Amount of unusual control characters
    val unusualCharCount = checkText.count { c ->
        c.isISOControl() && c.code != 9 && c.code != 10 && c.code != 12 && c.code != 13
    }

    // If the amount of unusual control chars in the file content is over 30%
    return unusualCharCount.toDouble() / checkText.length > threshold
}

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.getDefault(), "%.1f GB", gb)
}

fun formatNumberCompact(number: Int): String {
    if (number < 1000) return number.toString()

    val suffix = arrayOf("k", "m", "b")
    var value = number.toDouble()
    var index = -1

    while (value >= 1000 && index < suffix.lastIndex) {
        value /= 1000
        index++
    }

    return String.format("%.1f%s", value, suffix[index]).replace(".0", "")
}

/** Parses a string of comma or space-separated file extensions into a uniform list of extensions (without the dot). */
fun parseExtensions(input: String): List<String> {
    return input.split(",", " ").map { it.trim().trimStart('.') }.filter { it.isNotEmpty() }
}

fun loadSvg(inputStream: InputStream): Drawable? {
    val svg =
        try {
            SVG.getFromInputStream(inputStream)
        } catch (_: Exception) {
            return null
        }

    val picture = svg.renderToPicture()
    return PictureDrawable(picture)
}
