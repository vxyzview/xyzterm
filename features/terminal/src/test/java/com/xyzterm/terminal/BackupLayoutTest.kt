package com.xyzterm.terminal

import org.junit.Assert.assertEquals
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
}
