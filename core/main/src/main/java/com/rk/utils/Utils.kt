package com.rk.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.PictureDrawable
import android.os.Build
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.net.toUri
import com.caverock.androidsvg.SVG
import com.rk.extension.ActivityProvider
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Settings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import kotlin.math.roundToInt

@OptIn(DelicateCoroutinesApi::class)
fun runOnUiThread(runnable: Runnable) {
    GlobalScope.launch(Dispatchers.Main) { runnable.run() }
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
            // App backgrounded: no RESUMED activity, but plain text toasts are
            // still legal from the application context — e.g. backup finished.
            val app = application as? Context
            if (app != null) {
                Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
            } else {
                Log.w("Utils", "no valid ui context available for making toast: $message")
            }
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
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
    startActivity(intent)
}

fun hasHardwareKeyboard(context: Context): Boolean {
    val configuration = context.resources.configuration
    return configuration.keyboard != Configuration.KEYBOARD_NOKEYS
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
