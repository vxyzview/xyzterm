package com.xyzterm.terminal

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the X11/D-Bus environment the desktop launcher depends on. */
class DesktopScriptTest {
    private val script = File("src/main/assets/terminal/start-desktop.sh").readText()

    @Test
    fun x11_socket_uses_guest_tmp() {
        assertTrue(script.contains("export TMPDIR=/tmp"))
        assertTrue(script.contains("/tmp/.X11-unix/X"))
    }

    @Test
    fun waits_for_x_server_before_starting_desktop() {
        assertTrue(script.contains("while [ ! -S \"\$SOCKET\" ]"))
        assertTrue(script.contains("exit 1"))
    }

    @Test
    fun starts_a_dbus_session_when_available() {
        assertTrue(script.contains("dbus-launch --exit-with-session"))
        assertTrue(script.contains("XDG_RUNTIME_DIR"))
    }
}
