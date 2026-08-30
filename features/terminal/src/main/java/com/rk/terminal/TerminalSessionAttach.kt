package com.rk.terminal

import com.rk.terminal.virtualkeys.VirtualKeysListener
import com.rk.terminal.virtualkeys.VirtualKeysView
import com.rk.settings.Settings
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import java.util.Properties

/**
 * Concentrates the seven-step "wire a TerminalSession to a TerminalView and
 * publish it to the on-screen extra-keys row" dance. The two duplicate copies
 * (AndroidView.factory on cold start, Terminal.changeSession on a switch) had
 * already drifted — changeSession omitted the early setTerminalViewClient race
 * guard and the two placed reapplyTerminalColors at different points. Every
 * recent session-attach bug paid for that.
 *
 * The seven steps, in order:
 *
 *  1. setTerminalViewClient(client)            — race guard (see 48345ecb)
 *  2. session.updateTerminalSessionClient(client)
 *  3. terminalView.attachSession(session)
 *  4. terminalView.setTerminalViewClient(client) (again, after attach)
 *  5. wireExtraKeysClient()                    — post { virtualKeysViewClient = … }
 *  6. reapplyTerminalColors(terminalView)      — per-view tag-keyed cache
 *  7. terminalView.post { keepScreenOn = true; isFocusableInTouchMode = true; requestFocus() }
 */
class TerminalSessionAttach {

    fun run(
        view: TerminalView,
        virtualKeys: VirtualKeysView?,
        session: TerminalSession,
        client: TerminalBackEnd,
    ) {
        // Step 1: race guard. TerminalView.onCreateInputConnection reads mClient
        // unconditionally as soon as the view is attached/focused — deferring
        // setTerminalViewClient until a session exists (the original pre-fix
        // order) let cold-start restores NPE.
        view.setTerminalViewClient(client)

        // Step 2: hand the client to the session emulator so it can push
        // title-change / copy-text / bell callbacks back to TerminalBackEnd.
        session.updateTerminalSessionClient(client)

        // Step 3: bind session to view (creates a fresh emulator).
        view.attachSession(session)

        // Step 4: re-publish the client on the view after the emulator swap.
        view.setTerminalViewClient(client)

        // Step 5: extra-keys follow the attached session. Posted so the
        // VirtualKeysView AndroidView factory (a frame later than the
        // terminal view's) has had a chance to install itself.
        wireExtraKeysClient(view, virtualKeys, session)

        // Step 6: attachSession builds a fresh emulator whose palette reset
        // to Termux defaults — reapply the themed palette from the view tag.
        reapplyTerminalColors(view)

        // Step 7: focus + keep-screen-on so the IME pops and the screen
        // doesn't dim while the user is reading shell output.
        view.post {
            if (Settings.terminal_keep_screen_on) view.keepScreenOn = true
            view.isFocusableInTouchMode = true
            view.requestFocus()
        }
    }

    private fun wireExtraKeysClient(
        view: TerminalView,
        virtualKeys: VirtualKeysView?,
        session: TerminalSession,
    ) {
        view.post {
            virtualKeys?.virtualKeysViewClient = VirtualKeysListener(session)
        }
    }

    /**
     * Wire the extra-keys view to whatever session is already attached to the
     * terminal view (if any). Used by the VirtualKeysView's AndroidView.factory
     * at first attach: at that point the TerminalView may or may not have a
     * session yet, so this is a no-op when there's nothing to bind to. After
     * the first frame, the terminal-side [run] call takes over and re-wires
     * via [wireExtraKeysClient] on every session switch.
     */
    fun wireInitial(view: TerminalView, virtualKeys: VirtualKeysView?) {
        val session = view.mTermSession ?: return
        virtualKeys?.virtualKeysViewClient = VirtualKeysListener(session)
    }

    private fun reapplyTerminalColors(view: TerminalView) {
        val sig = view.tag as? TerminalColorSignature ?: return
        if (sig.colors == null || sig.colors.isEmpty) return
        view.applyTerminalColors(sig.onSurface, sig.surface, sig.colors)
    }
}

// Signature of colors last applied to a given TerminalView, stored on the
// view itself (tag) so applyTerminalColors can skip the expensive reset +
// full repaint when nothing changed. Per-view storage matters: after activity
// recreation the fresh TerminalView has no signature yet, so its first apply
// always runs — a process-global cache here would let the new view inherit
// a stale "already applied" verdict and render with default palette colors.
internal data class TerminalColorSignature(
    val colors: Properties?,
    val surface: Int,
    val onSurface: Int,
)

/**
 * Apply the themed palette to a TerminalView, with a per-view tag-keyed cache
 * so we don't reset + repaint the emulator on every recomposition. The cache
 * is on `view.tag` (per-view, not process-global) because activity recreation
 * hands us a fresh TerminalView with no signature yet — its first apply must
 * always run, and a shared cache would let it inherit a stale verdict.
 */
internal fun TerminalView.applyTerminalColors(
    onSurfaceColor: Int,
    surfaceColor: Int,
    terminalColors: Properties,
) {
    if (mEmulator == null) return
    val last = tag as? TerminalColorSignature
    if (last?.colors == terminalColors && last.surface == surfaceColor && last.onSurface == onSurfaceColor) {
        return
    }
    tag = TerminalColorSignature(terminalColors, surfaceColor, onSurfaceColor)

    this.onScreenUpdated()

    mEmulator?.mColors?.reset()
    TerminalColors.COLOR_SCHEME.updateWith(terminalColors)

    val cursorColor =
        terminalColors.getProperty("cursor")?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() }
            ?: onSurfaceColor

    mEmulator?.mColors?.mCurrentColors?.apply {
        set(TextStyle.COLOR_INDEX_FOREGROUND, onSurfaceColor)
        set(TextStyle.COLOR_INDEX_BACKGROUND, surfaceColor)
        set(TextStyle.COLOR_INDEX_CURSOR, cursorColor)
    }

    invalidate()
}