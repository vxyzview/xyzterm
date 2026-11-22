package com.xyzterm.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GamepadInputTest {
    @Test
    fun common_buttons_map_to_terminal_keys() {
        assertEquals("\r", gamepadBytes(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("\u001b", gamepadBytes(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals("\u0003", gamepadBytes(KeyEvent.KEYCODE_BUTTON_X))
        assertEquals("\t", gamepadBytes(KeyEvent.KEYCODE_BUTTON_Y))
    }

    @Test
    fun unrelated_keys_are_ignored() {
        assertNull(gamepadBytes(KeyEvent.KEYCODE_A))
        assertNull(gamepadBytes(KeyEvent.KEYCODE_DPAD_UP))
    }
}
