package com.xyzterm.terminal

import com.xyzterm.exec.UserBindings
import org.junit.Assert.assertEquals
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

    @Test
    fun decode_dedups_by_effective_guest_target() {
        val json =
            """
            [
              {"outside":"/a","inside":"/same"},
              {"outside":"/b","inside":"/same"},
              {"outside":"/c"}
            ]
            """
                .trimIndent()
        // decode() calls the generated JNI shadow; JVM unit tests have no native
        // library, so force the Kotlin-only oracle for this test.
        val previousMode = System.getProperty("rust-anything.mode")
        System.setProperty("rust-anything.mode", "old")
        val decoded =
            try {
                UserBindings.decode(json)
            } finally {
                if (previousMode == null) System.clearProperty("rust-anything.mode")
                else System.setProperty("rust-anything.mode", previousMode)
            }
        assertEquals(2, decoded.size)
        assertEquals("/a", decoded[0].outside)
        assertEquals("/c", decoded[1].outside)
    }
}
