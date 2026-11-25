package com.xyzterm.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsManifestTest {
    @Test
    fun parses_directories_symlinks_and_files() {
        val manifest =
            parseRootfsManifest(
                listOf(
                        "B\t1234",
                        "D\tetc\t493",
                        "L\tbin\t493\tusr/bin",
                        "F\tetc/hosts\t420\t512",
                    )
                    .joinToString("\n"),
            )

        assertEquals(1234L, manifest.bytes)
        assertEquals(3, manifest.entries.size)
        assertEquals("etc", manifest.entries[0].path)
        assertEquals("d", manifest.entries[0].type)
        assertEquals("usr/bin", manifest.entries[1].target)
        assertEquals(512L, manifest.entries[2].size)
        assertNull(manifest.entries[2].target)
    }

    @Test
    fun unsafe_entry_paths_are_rejected() {
        assertTrue(isSafeEntryPath("usr/bin/sh"))
        assertFalse(isSafeEntryPath("/etc/passwd"))
        assertFalse(isSafeEntryPath("usr/../../etc/passwd"))
        assertFalse(isSafeEntryPath("usr/../bin"))
    }

    @Test
    fun symlink_targets_escaping_root_are_rejected() {
        assertTrue(isSafeLinkTarget("bin", "usr/bin"))
        assertTrue(isSafeLinkTarget("usr/bin", "../../bin"))
        assertFalse(isSafeLinkTarget("bin", "/etc/passwd"))
        assertFalse(isSafeLinkTarget("usr/bin", "../../../etc/passwd"))
        assertFalse(isSafeLinkTarget("bin", "../.."))
    }
}
