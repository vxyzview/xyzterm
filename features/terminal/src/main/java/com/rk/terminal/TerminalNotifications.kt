package com.rk.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.rk.activities.terminal.Terminal
import com.rk.resources.drawables
import com.rk.resources.getFilledString
import com.rk.resources.getString
import com.rk.resources.strings

/**
 * Owns the full Android-notification ceremony for the terminal package: the
 * two channel IDs, channel-importance levels, Notification(Compat) builders,
 * PendingIntent flag combos, and the nm.notify swallow-and-log. Two callers
 * (SessionService and TerminalBackEnd) used to hand-roll the same boilerplate
 * independently; this object makes the ceremony reviewable in one file.
 *
 * Throttling of the bell notify stays in TerminalBackEnd — it's session-state,
 * not a notification concern.
 */
object TerminalNotifications {
    const val SERVICE_CHANNEL_ID = "session_service_channel"
    const val BELL_CHANNEL_ID = "terminal_bell"

    /** Idempotent: called once from SessionService.onCreate. */
    fun registerChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(SERVICE_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    strings.notification_channel_name.getString(),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = strings.notification_channel_desc.getString()
                }
            )
        }
        if (nm.getNotificationChannel(BELL_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    BELL_CHANNEL_ID,
                    strings.bell_notification.getString(),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }
    }

    fun serviceNotification(
        context: Context,
        sessionCount: Int,
        wakelockHeld: Boolean,
    ): Notification {
        val tapIntent = openActivity(context, requestCode = 0, Intent(context, Terminal::class.java))
        val exitPending = serviceAction(context, requestCode = 1, action = "ACTION_EXIT")
        val wakelockPending = serviceAction(context, requestCode = 2, action = "ACTION_WAKE_LOCK")

        return NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
            .setContentTitle(strings.notification_title.getString())
            .setContentText(serviceContentText(sessionCount, wakelockHeld))
            .setSmallIcon(drawables.terminal)
            .setContentIntent(tapIntent)
            .addAction(
                NotificationCompat.Action.Builder(
                    null,
                    strings.exit.getString(),
                    exitPending,
                ).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    null,
                    if (wakelockHeld) {
                        strings.release_wakelock.getString()
                    } else {
                        strings.acquire_wakelock.getString()
                    },
                    wakelockPending,
                ).build()
            )
            .setOngoing(true)
            .build()
    }

    fun bellNotification(context: Context, openActivityIntent: Intent): Notification {
        val pendingIntent = openActivity(context, requestCode = 3, openActivityIntent)
        return Notification.Builder(context, BELL_CHANNEL_ID)
            .setSmallIcon(drawables.terminal)
            .setContentTitle(strings.app_name.getString())
            .setContentText(strings.bell_notification.getString())
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
    }

    fun openActivity(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun serviceAction(context: Context, requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, SessionService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun serviceContentText(sessionCount: Int, wakelockHeld: Boolean): String {
        val base = strings.sessions_running.getFilledString(sessionCount)
        return if (wakelockHeld) {
            "$base ${strings.wake_lock_active.getString()}"
        } else {
            base
        }
    }

    /** Swallow + log; mirrors the ceremony the two callers used to hand-roll. */
    internal fun notifySafely(context: Context, id: Int, notification: Notification) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(id, notification) }
    }
}