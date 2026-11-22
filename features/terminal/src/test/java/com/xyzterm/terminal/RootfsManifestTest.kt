package com.xyzterm.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                ).joinToString("\n"),
            )

        assertEquals(1234L, manifest.bytes)
        assertEquals(3, manifest.entries.size)
        assertEquals("etc", manifest.entries[0].path)
        assertEquals("d", manifest.entries[0].type)
        assertEquals("usr/bin", manifest.entries[1].target)
        assertEquals(512L, manifest.entries[2].size)
        assertNull(manifest.entries[2].target)
    }
}
