package com.xyzterm.terminal

import com.xyzterm.exec.UserBindings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserBindingsTest {
    @Test
    fun proot_separators_are_rejected_in_bind_paths() {
        assertTrue(UserBindings.isValidGuestPath("/home/x"))
        assertFalse(UserBindings.isValidGuestPath("/home/a:b"))
        assertFalse(UserBindings.isValidGuestPath("/home/a\nb"))
        assertTrue(UserBindings.isWellFormedHost("/sdcard/a"))
        assertFalse(UserBindings.isWellFormedHost("/sdcard/a:b"))
    }

    @Test
    fun slash_only_guest_path_is_rejected() {
        assertFalse(UserBindings.isValidGuestPath("//"))
    }

    @Test
    fun traversal_guest_path_is_rejected() {
        assertFalse(UserBindings.isValidGuestPath("/home/../../data"))
    }
}
