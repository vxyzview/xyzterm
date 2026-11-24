package com.xyzterm.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkCommandTest {
    @Test
    fun accepts_plain_command() {
        assertTrue(isValidDeepLinkCommand("ls -la /home"))
    }

    @Test
    fun accepts_unicode_command() {
        assertTrue(isValidDeepLinkCommand("echo 会话"))
    }

    @Test
    fun rejects_empty() {
        assertFalse(isValidDeepLinkCommand(""))
    }

    @Test
    fun rejects_blank() {
        assertFalse(isValidDeepLinkCommand("   "))
    }

    @Test
    fun rejects_newline() {
        assertFalse(isValidDeepLinkCommand("echo hi\nrm -rf /"))
    }

    @Test
    fun rejects_carriage_return() {
        assertFalse(isValidDeepLinkCommand("echo hi\rmore"))
    }

    @Test
    fun rejects_tab() {
        assertFalse(isValidDeepLinkCommand("echo\thi"))
    }

    @Test
    fun rejects_input_over_limit() {
        assertFalse(isValidDeepLinkCommand("a".repeat(MAX_DEEP_LINK_COMMAND_LENGTH + 1)))
    }

    @Test
    fun accepts_input_at_limit() {
        assertTrue(isValidDeepLinkCommand("a".repeat(MAX_DEEP_LINK_COMMAND_LENGTH)))
    }
}
