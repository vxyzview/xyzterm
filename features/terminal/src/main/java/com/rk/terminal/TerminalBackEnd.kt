package com.rk.terminal

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.rk.activities.terminal.Terminal
import com.rk.resources.drawables
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.settings.terminal.TerminalCursorStyle
import com.rk.terminal.virtualkeys.SpecialButton
import com.rk.utils.copyToClipboard
import com.rk.utils.dialog
import com.rk.utils.dpToPx
import com.rk.utils.openUrl
import com.rk.utils.toast
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.launch

private val URL_REGEX = Regex("""https?://[^\s"'<>]+|www\.[^\s"'<>]+""")

private const val BELL_CHANNEL_ID = "terminal_bell"
private const val BELL_NOTIFICATION_ID = 2
private const val BELL_NOTIFY_THROTTLE_MS = 5_000L
private const val FONT_SIZE_PERSIST_DELAY_MS = 300L

class TerminalBackEnd : TerminalViewClient, TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView.get()?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        toast(strings.session_ended)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val context = terminalView.get()?.context ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val emulator = terminalView.get()?.mEmulator ?: return
        val clip =
            terminalView
                .get()
                ?.context
                ?.getSystemService(ClipboardManager::class.java)
                ?.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString()
                .orEmpty()
        if (clip.isNotBlank()) {
            emulator.paste(clip)
        }
    }

    override fun onBell(session: TerminalSession) {
        bellPulse = true
        notifyBellInBackground(session)
    }

    // Throttle: a job that rings repeatedly (e.g. `while true; echo -e '\a'`)
    // must not spam notifications.
    private var lastBellNotifyAt = 0L

    private fun notifyBellInBackground(session: TerminalSession) {
        if (Terminal.isForeground) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBellNotifyAt < BELL_NOTIFY_THROTTLE_MS) return
        lastBellNotifyAt = now

        val context = terminalView.get()?.context?.applicationContext ?: return

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(
                    BELL_CHANNEL_ID,
                    strings.bell_notification.getString(),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(context, Terminal::class.java)
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                3,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val notification =
            Notification.Builder(context, BELL_CHANNEL_ID)
                .setSmallIcon(drawables.terminal)
                .setContentTitle(strings.app_name.getString())
                .setContentText(strings.bell_notification.getString())
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

        runCatching { nm.notify(BELL_NOTIFICATION_ID, notification) }
    }

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    //override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

    override fun getTerminalCursorStyle(): Int {
        return when (Settings.terminal_cursor_style) {
            TerminalCursorStyle.BAR.value -> TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR
            TerminalCursorStyle.UNDERLINE.value -> TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE
            else -> TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        }
    }

    override fun logError(tag: String?, message: String?) {
        Log.e(tag.toString(), message.toString())
    }

    override fun logWarn(tag: String?, message: String?) {
        Log.w(tag.toString(), message.toString())
    }

    override fun logInfo(tag: String?, message: String?) {
        Log.i(tag.toString(), message.toString())
    }

    override fun logDebug(tag: String?, message: String?) {
        Log.d(tag.toString(), message.toString())
    }

    override fun logVerbose(tag: String?, message: String?) {
        Log.v(tag.toString(), message.toString())
    }

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag.toString(), message.toString())
        e?.printStackTrace()
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        e?.printStackTrace()
    }

    override fun onScale(scale: Float): Float {
        val view = terminalView.get() ?: return 1f
        // Settings stores the size in dp; convert like every other call site.
        // Returning 1f resets the view's cumulative factor, making `scale` a
        // per-gesture increment applied to the float accumulator below (int
        // round-trips through Settings would stall small pinches).
        if (fontSizeDp < 0f) fontSizeDp = Settings.terminal_font_size.toFloat()
        val newSize = (fontSizeDp * scale).coerceIn(10f, 20f)
        fontSizeDp = newSize
        view.setTextSize(dpToPx(newSize, view.context))
        fontSizePersistHandler.removeCallbacks(persistFontSize)
        fontSizePersistHandler.postDelayed(persistFontSize, FONT_SIZE_PERSIST_DELAY_MS)
        return 1f
    }

    private var fontSizeDp = -1f
    private val fontSizePersistHandler = Handler(Looper.getMainLooper())
    private val persistFontSize = Runnable {
        if (fontSizeDp >= 0f) {
            Settings.terminal_font_size = fontSizeDp.toInt()
        }
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val view = terminalView.get()
        val emulator = view?.mEmulator
        if (view != null && emulator != null) {
            val (column, row) = view.getColumnAndRow(e, true).let { it[0] to it[1] }
            val line = emulator.getScreen().getSelectedText(0, row, emulator.mColumns - 1, row)
            val url = URL_REGEX.find(line)?.takeIf { column in it.range }?.value?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
            if (url != null) {
                openUrlPrompt(url)
                return
            }
        }
        showSoftInput()
    }

    private fun openUrlPrompt(url: String) {
        val activity = Terminal.instance ?: return
        val target = if (url.startsWith("www.")) "https://$url" else url
        dialog(
            activity = activity,
            title = url,
            msg = strings.open_url_msg.getString(),
            okText = strings.open.getString(),
            neutralText = strings.copy.getString(),
            onOk = { activity.openUrl(target) },
            onNeutral = { copyToClipboard(target) },
        )
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean {
        return false
    }

    override fun shouldEnforceCharBasedInput(): Boolean {
        return true
    }

    override fun shouldUseCtrlSpaceWorkaround(): Boolean {
        return true
    }

    override fun isTerminalViewSelected(): Boolean {
        return true
    }

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        // Clipboard keybindings (toggleable in terminal settings). The pinned
        // terminal-view v0.118.3 has no shouldSupportClipboardKeybindings() client
        // hook, so Ctrl+Shift+C / Ctrl+Shift+V are handled here instead.
        if (Settings.terminal_clipboard_keybindings && e.isCtrlPressed && e.isShiftPressed) {
            val view = terminalView.get()
            when (keyCode) {
                KeyEvent.KEYCODE_V -> {
                    val clip =
                        view
                            ?.context
                            ?.getSystemService(ClipboardManager::class.java)
                            ?.primaryClip
                            ?.getItemAt(0)
                            ?.text
                            ?.toString()
                            .orEmpty()
                    if (clip.isNotEmpty()) {
                        view?.mEmulator?.paste(clip)
                    }
                    return true
                }
                KeyEvent.KEYCODE_C -> {
                    val selected = view?.selectedText
                    if (!selected.isNullOrEmpty()) {
                        val context = view.context
                        context
                            .getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Terminal", selected))
                        return true
                    }
                }
            }
        }

        if (keyCode == KeyEvent.KEYCODE_ENTER && !session.isRunning) {
            val activity = Terminal.instance ?: return false
            val sessionBinder = activity.sessionBinder?.get() ?: return false
            val finishedId = sessionBinder.getSessionId(session) ?: return false
            sessionBinder.terminateSession(finishedId)
            if (sessionBinder.getService().sessionList.isEmpty()) {
                activity.finish()
            } else {
                activity.lifecycleScope.launch {
                    activity.changeSession(sessionBinder.getService().sessionList.first())
                }
            }
            return true
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        return false
    }

    override fun onLongPress(event: MotionEvent): Boolean {
        return false
    }

    // keys
    override fun readControlKey(): Boolean {
        val state = virtualKeysView.get()?.readSpecialButton(SpecialButton.CTRL, true)
        return state != null && state
    }

    override fun readAltKey(): Boolean {
        val state = virtualKeysView.get()?.readSpecialButton(SpecialButton.ALT, true)
        return state != null && state
    }

    override fun readShiftKey(): Boolean {
        val state = virtualKeysView.get()?.readSpecialButton(SpecialButton.SHIFT, true)
        return state != null && state
    }

    override fun readFnKey(): Boolean {
        val state = virtualKeysView.get()?.readSpecialButton(SpecialButton.FN, true)
        return state != null && state
    }

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        return false
    }

    override fun onEmulatorSet() {
        setTerminalCursorBlinkingState(true)
    }

    private fun setTerminalCursorBlinkingState(start: Boolean) {
        if (terminalView.get()?.mEmulator != null) {
            terminalView.get()?.setTerminalCursorBlinkerState(start, true)
        }
    }

    private fun showSoftInput() {
        val view = terminalView.get() ?: return
        view.requestFocus()
        view.context.getSystemService(InputMethodManager::class.java)?.showSoftInput(view, 0)
    }
}
