package com.rk.terminal.virtualkeys

import android.view.View
import android.widget.Button
import com.rk.terminal.virtualkeys.VirtualKeysView.IVirtualKeysView
import com.termux.terminal.TerminalSession

class VirtualKeyClient(val session: TerminalSession) : IVirtualKeysView {
    override fun onVirtualKeyButtonClick(view: View?, buttonInfo: VirtualKeyButton?, button: Button?) {
        val key = buttonInfo?.key
        if (key.isNullOrEmpty()) {
            return
        }
        when (key) {
            "ESC" -> session.write(TerminalEscapes.ESC)
            "TAB" -> session.write(TerminalEscapes.TAB)
            "HOME" -> session.write(TerminalEscapes.HOME)
            "UP" -> session.write(TerminalEscapes.UP)
            "DOWN" -> session.write(TerminalEscapes.DOWN)
            "LEFT" -> session.write(TerminalEscapes.LEFT)
            "RIGHT" -> session.write(TerminalEscapes.RIGHT)
            "PGUP" -> session.write(TerminalEscapes.PGUP)
            "PGDN" -> session.write(TerminalEscapes.PGDN)
            "END" -> session.write(TerminalEscapes.END)
            else -> session.write(buttonInfo.key)
        }
    }

    override fun performVirtualKeyButtonHapticFeedback(
        view: View?,
        buttonInfo: VirtualKeyButton?,
        button: Button?,
    ): Boolean {
        return false
    }
}
