package com.xyzterm.terminal

import android.view.KeyEvent

/** Controller buttons as terminal input bytes. D-pad already works via TerminalView. */
internal fun gamepadBytes(keyCode: Int): String? =
    when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> "\r"
        KeyEvent.KEYCODE_BUTTON_B -> "\u001b"
        KeyEvent.KEYCODE_BUTTON_X -> "\u0003"
        KeyEvent.KEYCODE_BUTTON_Y -> "\t"
        KeyEvent.KEYCODE_BUTTON_L1 -> "\u001b[5~"
        KeyEvent.KEYCODE_BUTTON_R1 -> "\u001b[6~"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "\u001b[Z"
        else -> null
    }
