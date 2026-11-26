package com.xyzterm.terminal

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallCompleteTest {
    @Test
    fun `edited config file with changed size still counts as complete`() {
        val root = Files.createTempDirectory("rootfs-complete").toFile()
        try {
            val config = File(root, "pacman.conf")
            config.writeText("this file is longer than the manifest says")
            val manifest =
                RootfsManifest(
                    bytes = 100,
                    entries = listOf(RootfsEntry("pacman.conf", "f", 0b100000000, 4L, null)),
                )
            assertTrue(RootfsInstaller.isInstallComplete(root, manifest))
        } finally {
            root.deleteRecursively()
        }
    }
}
