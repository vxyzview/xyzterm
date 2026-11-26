package com.xyzterm.terminal

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRollbackTest {
    @Test
    fun `failed second swap puts the exact old sandbox back`() {
        val root = Files.createTempDirectory("backup-rollback").toFile()
        try {
            val old = File(root, "sandbox.old")
            assertTrue(old.mkdir())
            File(old, "old-marker").writeText("old")
            val target = File(root, "sandbox")
            assertTrue(target.mkdir())
            File(target, "new-marker").writeText("new")

            val restored = restoreRollback(target, old)

            assertTrue(restored)
            assertTrue(File(target, "old-marker").isFile)
            assertFalse(File(target, "new-marker").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
