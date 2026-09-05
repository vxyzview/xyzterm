package com.rk.terminal

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.rk.activities.terminal.Terminal

/** Quick-settings tile that opens the terminal (restoring the last active session). */
class TerminalTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, Terminal::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }
}
