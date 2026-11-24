package com.xyzterm.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNamesTest {
    @Test
    fun rejects_empty() {
        assertFalse(isValidSessionName(""))
    }

    @Test
    fun rejects_dot() {
        assertFalse(isValidSessionName("."))
    }

    @Test
    fun rejects_dotdot() {
        assertFalse(isValidSessionName(".."))
    }

    @Test
    fun rejects_slash() {
        assertFalse(isValidSessionName("a/b"))
    }

    @Test
    fun rejects_backslash() {
        assertFalse(isValidSessionName("a\\b"))
    }

    @Test
    fun rejects_control_character() {
        assertFalse(isValidSessionName("a\u0000b"))
    }

    @Test
    fun accepts_ordinary_names() {
        assertTrue(isValidSessionName("main"))
        assertTrue(isValidSessionName("my project"))
        assertTrue(isValidSessionName("会话"))
    }
}
