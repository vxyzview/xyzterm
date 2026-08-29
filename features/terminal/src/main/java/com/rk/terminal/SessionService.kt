package com.rk.terminal

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import com.rk.DefaultScope
import com.rk.activities.terminal.Terminal
import com.rk.resources.drawables
import com.rk.resources.getFilledString
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android-boundary adapter for [SessionController]. Owns the things that
 * only Android cares about: foreground notification, wakelock, service
 * start/stop, and the policy hooks in [onDestroy]/[onTaskRemoved]. All
 * session-map state and the intent state machine live in [controller].
 */
class SessionService : Service() {
    lateinit var controller: SessionController
        private set

    private val binder = SessionBinder()
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val notificationHandler = Handler(Looper.getMainLooper())
    private val notificationRunnable = Runnable { postNotification() }
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var wakeLock: PowerManager.WakeLock? = null

    /** Adapter binder — only exposes the [SessionController]. */
    inner class SessionBinder : Binder() {
        fun getController(): SessionController = controller
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        controller =
            SessionController(
                context = applicationContext,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
                factory = MkSessionFactory(applicationContext),
            )

        createNotificationChannel()
        val notification = createNotification()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(1, notification)
        }

        controller.markDaemonRunning()
        observeSessionCount()

        wakeLock =
            (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "${strings.app_name.getString()}::${this::class.java.simpleName}",
            )
    }

    override fun onDestroy() {
        controller.shutdown()
        notificationScope.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (Settings.auto_backup) {
            // IO dispatcher: tar blocks for minutes; Default pool is for CPU.
            DefaultScope.launch(Dispatchers.IO) { runCatching { TerminalBackup.autoBackup() } }
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (Settings.terminate_sessions_on_exit) actionExit()
    }

    /** Public for the drawer's "Logout" button. */
    fun actionExit() {
        controller.shutdown()
        stopSelf()
    }

    @SuppressLint("WakelockTimeout", "Wakelock")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_EXIT" -> actionExit()
            "ACTION_WAKE_LOCK" -> {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                } else {
                    wakeLock?.acquire()
                }
                updateNotification()
            }
        }
        return START_NOT_STICKY
    }

    private fun observeSessionCount() {
        notificationScope.launch {
            snapshotFlow { controller.sessions.size }.collect { updateNotification() }
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, Terminal::class.java)
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val exitIntent = Intent(this, SessionService::class.java).apply { action = "ACTION_EXIT" }
        val wakeLockIntent = Intent(this, SessionService::class.java).apply { action = "ACTION_WAKE_LOCK" }

        val exitPendingIntent =
            PendingIntent.getService(
                this,
                1,
                exitIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val wakelockPendingIntent =
            PendingIntent.getService(
                this,
                2,
                wakeLockIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(strings.notification_title.getString())
            .setContentText(getNotificationContentText(wakeLock?.isHeld == true))
            .setSmallIcon(drawables.terminal)
            .setContentIntent(pendingIntent)
            .addAction(NotificationCompat.Action.Builder(null, strings.exit.getString(), exitPendingIntent).build())
            .addAction(
                NotificationCompat.Action.Builder(
                        null,
                        if (wakeLock?.isHeld == true) {
                            strings.release_wakelock.getString()
                        } else {
                            strings.acquire_wakelock.getString()
                        },
                        wakelockPendingIntent,
                    )
                    .build()
            )
            .setOngoing(true)
            .build()
    }

    private val CHANNEL_ID = "session_service_channel"

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(CHANNEL_ID, strings.notification_channel_name.getString(), NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = strings.notification_channel_desc.getString()
                }
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        // Coalesce bursts (a script spawning many shells) into a single post.
        // ponytail: 500ms window, last call wins; bump if session churn needs tighter counts.
        notificationHandler.removeCallbacks(notificationRunnable)
        notificationHandler.postDelayed(notificationRunnable, 500)
    }

    private fun postNotification() {
        runCatching {
            val notification = createNotification()
            notificationManager.notify(1, notification)
        }.onFailure { it.printStackTrace() }
    }

    private fun getNotificationContentText(wakelock: Boolean): String {
        val base = strings.sessions_running.getFilledString(controller.sessions.size)
        return if (wakelock) {
            "$base ${strings.wake_lock_active.getString()}"
        } else {
            base
        }
    }
}