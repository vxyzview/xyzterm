package com.rk.terminal

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import com.blankj.utilcode.util.ClipboardUtils
import com.blankj.utilcode.util.KeyboardUtils
import com.rk.activities.terminal.Terminal
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.settings.terminal.TerminalCursorStyle
import com.rk.terminal.virtualkeys.SpecialButton
import com.rk.utils.dialog
import com.rk.utils.dpToPx
import com.rk.utils.openUrl
import com.rk.utils.toast
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient

private val URL_REGEX = Regex("""https?://[^\s"'<>]+|www\.[^\s"'<>]+""")

class TerminalBackEnd : TerminalViewClient, TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView.get()?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        toast(strings.session_ended)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        ClipboardUtils.copyText("Terminal", text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = ClipboardUtils.getText().toString()
        val emulator = terminalView.get()?.mEmulator ?: return
        if (clip.isNotBlank()) {
            emulator.paste(clip)
        }
    }

    override fun onBell(session: TerminalSession) {
        bellPulse = true
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

    //TODO

//    override fun shouldSupportClipboardKeybindings(): Boolean {
//        return Settings.terminal_clipboard_keybindings
//    }


    override fun isTerminalViewSelected(): Boolean {
        return true
    }

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && !session.isRunning) {
            val activity = Terminal.instance ?: return false
            val sessionBinder = activity.sessionBinder?.get() ?: return false
            sessionBinder.terminateSession(sessionBinder.getService().currentSession.value)
            if (sessionBinder.getService().sessionList.isEmpty()) {
                activity.finish()
            } else {
                activity.changeSession(sessionBinder.getService().sessionList.first())
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
        terminalView.get()?.requestFocus()
        terminalView.get()?.let { KeyboardUtils.showSoftInput(it) }
    }
}
