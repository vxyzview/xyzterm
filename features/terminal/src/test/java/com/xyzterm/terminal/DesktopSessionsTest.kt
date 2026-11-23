package com.xyzterm.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSessionsTest {
    @Test
    fun sessions_are_unique_and_resolvable() {
        assertEquals(
            DesktopSessions.all.map { it.key }.toSet().size,
            DesktopSessions.all.size,
        )
        DesktopSessions.all.forEach { assertNotNull(DesktopSessions.find(it.key)) }
    }

    @Test
    fun covers_full_desktops_and_window_managers() {
        val keys = DesktopSessions.all.map { it.key }
        assertTrue(keys.containsAll(listOf("xfce4", "kde", "gnome", "cinnamon", "mate", "lxqt")))
        assertTrue(keys.containsAll(listOf("i3", "openbox", "fluxbox", "awesome", "dwm", "bspwm", "qtile")))
    }
}
