package com.xyzterm.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupLayoutTest {
    @Test
    fun `sandbox root means current layout`() {
        assertEquals(BackupLayout.V2_SANDBOX_HOME, detectBackupLayout(listOf("sandbox", "home")))
    }

    @Test
    fun `sandbox child entry also means current layout`() {
        assertEquals(BackupLayout.V2_SANDBOX_HOME, detectBackupLayout(listOf("sandbox/etc", "home")))
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
