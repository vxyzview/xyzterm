package com.rk.terminal

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.termux.view.TerminalView
import java.lang.ref.WeakReference

/**
 * The "view surface" the [TerminalBackEnd] needs to read but does not own.
 *
 * Replaces the package-level `terminalView` / `virtualKeysView` / `bellPulse`
 * globals: instead of reaching for them, [TerminalBackEnd] receives a port
 * through its constructor and reads through it. Two real adapters exist
 * today (the back-end and the [TerminalTopBar]), so the seam is real, not
 * speculative — see ADR-0001.
 */
interface TerminalViewPort {
    /** Current [TerminalView], or null before the view attaches / after it detaches. */
    fun view(): TerminalView?

    /** Current [VirtualKeysView], or null before the view attaches / after it detaches. */
    fun virtualKeys(): VirtualKeysView?

    /** "Shell rang the bell" pulse. The header observes it; the back-end sets it. */
    val bell: BellState

    /**
     * True while the terminal activity is in the foreground. The back-end
     * reads this to gate bell notifications. Owned by the activity, read
     * through the port so the back-end has no static reference to it.
     */
    val isForeground: MutableState<Boolean>
}

/**
 * "Shell rang the bell" signal. A thin wrapper around [MutableState] so
 * callers that only want to read or write the value do not see Compose's
 * mutable API directly. The Compose header resets the value to `false`
 * after the 2s flash; the back-end sets it to `true` in `onBell`.
 */
class BellState {
    private val state = mutableStateOf(false)
    var value: Boolean
        get() = state.value
        set(v) {
            state.value = v
        }
}

/**
 * Default [TerminalViewPort] for one screen.
 *
 * Constructed once by [TerminalScreen] via `remember { TerminalViewPortHolder() }`
 * and passed to every view / session helper that previously reached for the
 * package-level vars. The view factories call [installView] /
 * [installVirtualKeys]; everyone else reads through the interface.
 */
class TerminalViewPortHolder : TerminalViewPort {
    private var viewRef: WeakReference<TerminalView?> = WeakReference(null)
    private var virtualKeysRef: WeakReference<VirtualKeysView?> = WeakReference(null)

    override val bell: BellState = BellState()
    override val isForeground: MutableState<Boolean> = mutableStateOf(false)

    override fun view(): TerminalView? = viewRef.get()
    override fun virtualKeys(): VirtualKeysView? = virtualKeysRef.get()

    /** Called by the TerminalView AndroidView factory once the view is built. */
    fun installView(view: TerminalView) {
        viewRef = WeakReference(view)
    }

    /** Called by the VirtualKeysView AndroidView factory once the view is built. */
    fun installVirtualKeys(view: VirtualKeysView) {
        virtualKeysRef = WeakReference(view)
    }
}
