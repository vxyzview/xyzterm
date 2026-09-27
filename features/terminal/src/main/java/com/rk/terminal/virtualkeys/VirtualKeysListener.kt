package com.rk.terminal.virtualkeys

import android.view.View
import android.widget.Button
import com.termux.terminal.TerminalSession

class VirtualKeysListener(val session: TerminalSession) : VirtualKeysView.IVirtualKeysView {

    override fun onVirtualKeyButtonClick(view: View?, buttonInfo: VirtualKeyButton?, button: Button?) {

        val key = buttonInfo?.key ?: return
        val writeable: String =
            when (key) {
                "UP" -> TerminalEscapes.UP
                "DOWN" -> TerminalEscapes.DOWN
                "LEFT" -> TerminalEscapes.LEFT
                "RIGHT" -> TerminalEscapes.RIGHT
                "ENTER" -> TerminalEscapes.ENTER
                "PGUP" -> TerminalEscapes.PGUP
                "PGDN" -> TerminalEscapes.PGDN
                "TAB" -> TerminalEscapes.TAB
                "HOME" -> TerminalEscapes.HOME
                "END" -> TerminalEscapes.END
                "ESC" -> TerminalEscapes.ESC
                else -> key
            }

        session.write(writeable)
    }

    override fun performVirtualKeyButtonHapticFeedback(
        view: View?,
        buttonInfo: VirtualKeyButton?,
        button: Button?,
    ): Boolean {
        return false
    }
}
