package com.xyzterm.terminal

import android.app.Activity
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xyzterm.file.localDir
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings
import com.xyzterm.settings.Settings
import com.xyzterm.utils.toast
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Owns the desktop session lifecycle and the Termux:X11 display activity. */
object DesktopController {
    var binder: SessionService.SessionBinder? = null
    var running by mutableStateOf(false)
        private set

    private const val SESSION_ID = "desktop"

    fun refresh() {
        running = binder?.getSession(SESSION_ID) != null
    }

    fun markStarting() {
        running = true
    }

    /** Opens the X11 surface once the guest X socket exists, so it does not show "not connected". */
    suspend fun openX11WhenReady(activity: Activity) =
        withContext(Dispatchers.IO) {
            val num = Settings.x11_display.removePrefix(":").substringBefore(".")
            val socket = File(localDir(), "sandbox/tmp/.X11-unix/X$num")
            var ready = false
            var tries = 0
            while (!socket.exists() && tries < 40) {
                delay(250)
                tries++
            }
            ready = socket.exists()
            withContext(Dispatchers.Main) {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    if (ready) openX11(activity) else toast(strings.x11_start_failed.getString())
                }
            }
        }

    private fun openX11(activity: Activity) {
        runCatching {
            val intent =
                Intent(Intent.ACTION_VIEW).setClassName("com.termux.x11", "com.termux.x11.MainActivity")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        }
            .onFailure { toast(strings.x11_not_installed.getString()) }
    }

    suspend fun stop(activity: Activity) =
        withContext(Dispatchers.Main) {
            binder?.terminateSession(SESSION_ID)
            running = false
            runCatching {
                activity.sendBroadcast(Intent("com.termux.x11.ACTION_STOP").setPackage("com.termux.x11"))
            }
        }
}
