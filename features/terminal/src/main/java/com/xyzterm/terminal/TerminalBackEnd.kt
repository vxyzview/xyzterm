package com.xyzterm.terminal

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import com.xyzterm.activities.terminal.Terminal
import com.xyzterm.resources.drawables
import com.xyzterm.resources.getFilledString
import com.xyzterm.resources.getString
import com.xyzterm.resources.strings
import com.xyzterm.settings.Settings
import com.xyzterm.settings.terminal.TerminalCursorStyle
import com.xyzterm.terminal.virtualkeys.SpecialButton
import com.xyzterm.utils.dialog
import com.xyzterm.utils.dpToPx
import com.xyzterm.utils.openUrl
import com.xyzterm.utils.toast
import java.io.File
import kotlinx.coroutines.launch

private val URL_REGEX = Regex("""https?://[^\s"'<>]+|www\.[^\s"'<>]+""")

private const val BELL_CHANNEL_ID = "terminal_bell"
private const val BELL_NOTIFICATION_ID = 2
private const val BELL_NOTIFY_THROTTLE_MS = 5_000L
private const val FINISHED_CHANNEL_ID = "terminal_finished"
private const val FINISHED_NOTIFICATION_ID = 3

/** Live OSC 0/2 title and shell working directory, keyed by session so early title changes are not lost. */
object TerminalLiveState {
    val titles = mutableStateMapOf<TerminalSession, String>()
    val cwds = mutableStateMapOf<TerminalSession, String>()
}

/**
 * A full scrollback easily exceeds the ~1 MB binder transaction limit, so copy and share hand around a FileProvider URI
 * backed by one cache file instead of a String.
 */
internal fun terminalTextUri(
    context: Context,
    text: String,
): Uri? {
    val dir = File(context.cacheDir, "terminal-text").apply { mkdirs() }
    dir.listFiles()?.forEach { it.delete() }
    val file = File(dir, "scrollback.txt")
    return runCatching {
        file.writeText(text)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
        .getOrNull()
}

class TerminalBackEnd : TerminalViewClient, TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView.get()?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {
        // OSC 0/2 reaches us through the pinned emulator's title callback.
        val title = changedSession.getTitle().orEmpty()
        if (title.isBlank()) {
            TerminalLiveState.titles.remove(changedSession)
        } else {
            TerminalLiveState.titles[changedSession] = title
        }
        // The emulator has no OSC 7 hook; /proc/<pid>/cwd gives the same live view.
        changedSession.getCwd()?.let { TerminalLiveState.cwds[changedSession] = it }
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        TerminalLiveState.titles.remove(finishedSession)
        TerminalLiveState.cwds.remove(finishedSession)
        val status = runCatching { finishedSession.getExitStatus() }.getOrDefault(0)
        toast(
            if (status == 0) strings.session_ended.getString() else strings.session_ended_code.getFilledString(status),
        )
        if (Settings.command_finished_notifications) notifyFinishedInBackground(finishedSession, status)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val context = terminalView.get()?.context ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        val uri = terminalTextUri(context, text) ?: return
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Terminal", uri))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val view = terminalView.get() ?: return
        val emulator = view.mEmulator ?: return
        val item = view.context.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)
        val clip =
            item?.text?.toString()
                ?: item
                    ?.uri
                    ?.let { uri ->
                        runCatching {
                            view.context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        }
                            .getOrNull()
                    }
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
        if (Terminal.isForeground && Terminal.visibleSession === session) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBellNotifyAt < BELL_NOTIFY_THROTTLE_MS) return
        lastBellNotifyAt = now

        // The service outlives the activity and owns the session-name map.
        val service = SessionService.instance ?: return
        val context = service.applicationContext
        val id = service.sessionIdFor(session) ?: return
        if (!isValidSessionName(id)) return

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(
                    BELL_CHANNEL_ID,
                    strings.bell_notification.getString(),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }

        if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("xyzterm://session/${Uri.encode(id)}"))
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
                .setContentTitle(strings.bell_notification.getString())
                .setContentText(id)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

        runCatching { nm.notify(BELL_NOTIFICATION_ID, notification) }
    }

    private fun notifyFinishedInBackground(session: TerminalSession, status: Int) {
        if (Terminal.isForeground && Terminal.visibleSession === session) return
        val service = SessionService.instance ?: return
        val context = service.applicationContext
        val id = service.sessionIdFor(session) ?: return
        if (!isValidSessionName(id)) return
        if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(
                    FINISHED_CHANNEL_ID,
                    strings.command_finished.getString(),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                4,
                Intent(Intent.ACTION_VIEW, Uri.parse("xyzterm://session/${Uri.encode(id)}")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification.Builder(context, FINISHED_CHANNEL_ID)
                .setSmallIcon(drawables.terminal)
                .setContentTitle(strings.command_finished.getString())
                .setContentText("$id · exit $status")
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
        runCatching { nm.notify(FINISHED_NOTIFICATION_ID, notification) }
    }

    override fun onColorsChanged(session: TerminalSession) {}

    override fun onTerminalCursorStateChange(state: Boolean) {}

    // override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

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
        Settings.terminal_font_size = newSize.toInt()
        view.setTextSize(dpToPx(newSize, view.context))
        return 1f
    }

    private var fontSizeDp = -1f

    override fun onSingleTapUp(e: MotionEvent) {
        val view = terminalView.get()
        val emulator = view?.mEmulator
        if (view != null && emulator != null) {
            val (column, row) = view.getColumnAndRow(e, true).let { it[0] to it[1] }
            // getWordAtLocation follows line-wrap metadata, so a URL broken across
            // visual rows is returned as one word instead of a partial fragment.
            val word = emulator.getScreen().getWordAtLocation(column, row)
            val url = URL_REGEX.find(word)?.value?.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
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
            onOk = { activity.openUrl(target) },
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
            sessionBinder.terminateSession(sessionBinder.getService().currentSession.value)
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
        Terminal.instance?.flushPendingPrefill()
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
