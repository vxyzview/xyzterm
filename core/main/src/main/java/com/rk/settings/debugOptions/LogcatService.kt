package com.rk.settings.debugOptions

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.IBinder
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rk.activities.settings.SettingsActivity
import java.util.Locale
import com.rk.resources.drawables
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class LogcatService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var logcatProcess: Process? = null

    companion object {
        private const val CHANNEL_ID = "logcat_service_channel"

        val logcatLogs = mutableStateListOf<String>()

        private val _logFlow = MutableSharedFlow<String>(extraBufferCapacity = 512)
        val logFlow = _logFlow.asSharedFlow()

        /**
         * Starts in the foreground.
         *
         * `startService()` from the background throws IllegalStateException on API 26+ and
         * ForegroundServiceStartNotAllowedException on 31+. This was called from a coroutine on
         * Dispatchers.IO whose scheduling relative to the app going background is not
         * guaranteed, and appScope carries only a SupervisorJob — so a throw here was an
         * uncaught startup crash.
         */
        fun start(context: Context) {
            val intent = Intent(context, LogcatService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w("LogcatService", "could not start the logcat service", it) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, LogcatService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
         * Hoisted to a file-level constant, lowercased once.
         *
         * This runs for every line logcat emits — thousands per second — and was allocating a
         * 20-element `List` plus up to 20 `contains(ignoreCase = true)` scans per line. Pure
         * allocation churn for as long as the service was on.
         */
    private val noisyKeywords =
        setOf(
            "viewrootimpl",
            "choreographer",
            "openglrenderer",
            "inputmethodmanager",
            "renderthread",
            "libegl",
            "egl_emulation",
            "compatchangereporter",
            "vndksupport",
            "configstore",
            "mono",
            "me.weishu.reflection",
            "chatty",
            "hostconnection",
            "gralloc",
            "gesturedetector",
            "windowmanager",
            "imefocuscontroller",
            "insets",
        )

    private fun shouldCaptureLog(line: String): Boolean {
        if (line.isEmpty()) {
            return false
        }

        val lower = line.lowercase(Locale.ROOT)
        if (noisyKeywords.any { lower.contains(it) }) {
            return false
        }

        if (!line.contains('/') || !line.contains('(')) {
            return true
        }

        val slashIdx = line.indexOf('/')
        val parenIdx = line.indexOf('(')
        if (slashIdx != 1 || parenIdx <= slashIdx) {
            return true
        }

        val priority = line[0]
        val tag = line.substring(slashIdx + 1, parenIdx).trim()

        // 1. Capture warning + error + fatal + assert logs
        if (priority == 'W' || priority == 'E' || priority == 'F' || priority == 'A') {
            return true
        }

        // 2. Capture System.* calls
        if (tag.startsWith("System.out") || tag.startsWith("System.err")) {
            return true
        }

        // 3. Default to capturing
        return true
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = createNotification()

        // Required: startForegroundService() promises a startForeground() within a few seconds.
        // Without this the system kills the service with a ForegroundServiceDidNotStartInTime
        // crash. The separate notify(99) is kept for the persistent notification the UI reads.
        startForeground(98, notification)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(99, notification)

        // Start collecting logcat
        serviceScope.launch {
            try {
                // Clear any existing logs
                launch(Dispatchers.Main) { logcatLogs.clear() }

                // Run logcat continuously in brief format
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "brief"))
                logcatProcess = process

                val batch = mutableListOf<String>()
                var lastUpdateTime = System.currentTimeMillis()

                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (shouldCaptureLog(line)) {
                            _logFlow.tryEmit(line)
                            synchronized(batch) { batch.add(line) }

                            val now = System.currentTimeMillis()
                            if (batch.size >= 50 || now - lastUpdateTime > 100) {
                                val itemsToAdd =
                                    synchronized(batch) {
                                        val copy = batch.toList()
                                        batch.clear()
                                        copy
                                    }
                                lastUpdateTime = now
                                launch(Dispatchers.Main) {
                                    synchronized(logcatLogs) {
                                        logcatLogs.addAll(itemsToAdd)
                                        if (logcatLogs.size > 1000) {
                                            val toRemove = logcatLogs.size - 1000
                                            logcatLogs.removeRange(0, toRemove)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Add any remaining items
                if (batch.isNotEmpty()) {
                    launch(Dispatchers.Main) {
                        synchronized(logcatLogs) {
                            logcatLogs.addAll(batch)
                            if (logcatLogs.size > 1000) {
                                val toRemove = logcatLogs.size - 1000
                                logcatLogs.removeRange(0, toRemove)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP") {
            Settings.enable_logcat = false
            stopSelf()
            return START_NOT_STICKY
        }
        // START_NOT_STICKY, not START_STICKY: this service wraps a never-terminating `logcat`
        // child process. With START_STICKY the system restarts it indefinitely once enabled —
        // a permanent battery drain with no way for the user to see why.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // The logcat child is not a ProcessHandle we can signal portably; destroying the
        // stream is what actually ends it.
        runCatching { logcatProcess?.destroy() }
            .onFailure { Log.w("LogcatService", "could not stop logcat", it) }
        logcatProcess = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(99)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(CHANNEL_ID, "Logcat Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Notification for Logcat Background Service"
            }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, SettingsActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val stopIntent = Intent(this, LogcatService::class.java).apply { action = "ACTION_STOP" }
        val stopPendingIntent =
            PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(strings.logcat_running.getString())
            .setContentText(strings.logcat_running_desc.getString())
            .setSmallIcon(drawables.terminal)
            .setContentIntent(pendingIntent)
            .addAction(NotificationCompat.Action.Builder(null, strings.stop.getString(), stopPendingIntent).build())
            .setOngoing(true)
            .build()
    }
}
