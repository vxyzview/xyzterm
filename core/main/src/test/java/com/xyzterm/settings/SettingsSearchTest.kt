package com.xyzterm.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private val entries =
        listOf(
            SettingsEntry("Themes", "Theme settings", "theme"),
            SettingsEntry("Keybindings", "Remap shortcuts", "keybindings"),
            SettingsEntry("Terminal health", "Run diagnostics", "terminal_check"),
        )

    @Test
    fun empty_query_returns_everything() {
        assertEquals(entries, filterSettings(entries, ""))
    }

    @Test
    fun search_is_case_insensitive_on_label_and_description() {
        assertEquals(listOf(entries[0]), filterSettings(entries, "THEME"))
        assertEquals(listOf(entries[1]), filterSettings(entries, "shortcuts"))
    }

    @Test
    fun unrelated_query_returns_nothing() {
        assertTrue(filterSettings(entries, "not a setting").isEmpty())
    }

    @Test
    fun dynamic_terminal_routes_are_kept_unchanged() {
        assertEquals(listOf(entries[2]), filterSettings(entries, "terminal_check"))
    }
}
