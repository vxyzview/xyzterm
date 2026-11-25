package com.xyzterm.terminal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xyzterm.settings.Settings

/** Re-arms periodic auto-backup after a reboot; scheduled jobs do not survive one. */
class AutoBackupBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Settings.auto_backup) {
            AutoBackupJobService.schedule(context)
        }
    }
}
