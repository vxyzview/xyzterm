package com.xyzterm.terminal

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupLayoutTest {
    @Test
    fun `sandbox and home root means current layout`() {
        assertEquals(BackupLayout.V2_SANDBOX_HOME, detectBackupLayout(listOf("sandbox", "home")))
    }

    @Test
    fun `sandbox child entry also means current layout`() {
        assertEquals(BackupLayout.V2_SANDBOX_HOME, detectBackupLayout(listOf("sandbox/etc", "home")))
    }

    @Test
    fun `top level sandbox without home is the old layout`() {
        assertEquals(BackupLayout.V1_ROOT, detectBackupLayout(listOf("sandbox", "etc", "usr")))
    }

    @Test
    fun `file named sandbox without home is the old layout`() {
        assertEquals(BackupLayout.V1_ROOT, detectBackupLayout(listOf("sandbox")))
    }

    @Test
    fun `home without sandbox is the old layout`() {
        assertEquals(BackupLayout.V1_ROOT, detectBackupLayout(listOf("home", "etc")))
    }

    @Test
    fun `archive without sandbox entry is the old layout`() {
        assertEquals(BackupLayout.V1_ROOT, detectBackupLayout(listOf("etc", "usr", "home")))
    }

    @Test
    fun `empty archive is the old layout`() {
        assertEquals(BackupLayout.V1_ROOT, detectBackupLayout(emptyList()))
    }

    @Test
    fun `home with entries is still detected as current layout`() {
        assertEquals(BackupLayout.V2_SANDBOX_HOME, detectBackupLayout(listOf("sandbox", "home/user")))
    }

    @Test
    fun `symlink named sandbox is not a real directory`() {
        val root = Files.createTempDirectory("backup-layout").toFile()
        try {
            val real = File(root, "real")
            assertTrue(real.mkdir())
            val link = File(root, "sandbox")
            Files.createSymbolicLink(link.toPath(), real.toPath())
            assertTrue(isRealDirectory(real))
            assertFalse(isRealDirectory(link))
        } finally {
            root.deleteRecursively()
        }
    }
}
