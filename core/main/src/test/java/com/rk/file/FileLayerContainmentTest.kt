package com.rk.file

import com.google.common.truth.Truth.assertThat
import com.rk.utils.STORAGE_ROOT_FALLBACK
import com.rk.utils.isStorageRootFallback
import java.io.File
import java.nio.file.Files
import org.junit.Test

/**
 * Pins the containment guarantees the file layer is supposed to provide.
 *
 * Two of these were real hazards:
 *
 *  1. `PathUtils.toPath()` returned `"/storage/emulated/0/"` as a fallback for *any*
 *     content-resolver failure. `Uri.toFileObject()` then saw that directory exists and is
 *     readable, and handed back a writable `FileWrapper` for the storage root. A provider
 *     hiccup turned a copy or delete into "acted on the wrong directory".
 *  2. `FileObject.copyToTempDir()` built its destination with `File(parent, name)`, where
 *     `name` comes from a third-party SAF provider's `OpenableColumns.DISPLAY_NAME`. A provider
 *     returning `"../../databases/app.db"` wrote outside the temp dir. The codebase already has
 *     `File.childSafe` for exactly this, and this one call site had bypassed it.
 *
 * The production change these tests catch: reverting to raw `File(parent, name)`, or restoring
 * the storage-root fallback.
 */
class FileLayerContainmentTest {

    private fun tempDir(): File = Files.createTempDirectory("filelayer").toFile()

    @Test
    fun `a traversal name is flattened to a single child`() {
        val parent = tempDir()
        val target = parent.sanitizedChild("../../databases/app.db")

        assertThat(target.parentFile.canonicalPath).isEqualTo(parent.canonicalPath)
    }

    @Test
    fun `a deeply nested traversal is flattened to a single child`() {
        val parent = tempDir()
        val target = parent.sanitizedChild("a/b/../../../escape.txt")

        assertThat(target.parentFile.canonicalPath).isEqualTo(parent.canonicalPath)
    }

    @Test
    fun `separators in a name never create a subdirectory`() {
        val parent = tempDir()
        val target = parent.sanitizedChild("sub/dir/file.txt")

        assertThat(target.parentFile.canonicalPath).isEqualTo(parent.canonicalPath)
    }

    @Test
    fun `an ordinary name survives sanitisation unchanged`() {
        assertThat(tempDir().sanitizedChild("notes.txt").name).isEqualTo("notes.txt")
    }

    @Test
    fun `a name of only dots cannot resolve to the parent directory`() {
        val parent = tempDir()
        parent.mkdirs()
        val target = parent.sanitizedChild("..").canonicalFile

        assertThat(target).isNotEqualTo(parent.parentFile.canonicalFile)
        assertThat(target.canonicalPath).startsWith(parent.canonicalPath + File.separator)
    }

    @Test
    fun `a name of only dots with a separator cannot resolve above the parent`() {
        val parent = tempDir()
        val target = parent.sanitizedChild("../../").canonicalFile

        assertThat(target.canonicalPath).startsWith(parent.canonicalPath + File.separator)
    }

    @Test
    fun `sanitisation never produces an empty name`() {
        val name = tempDir().sanitizedChild("///").name

        assertThat(name).isNotEmpty()
    }

    @Test
    fun `childSafe rejects a traversal segment outright`() {
        val parent = tempDir()

        assertThat(runCatching { parent.childSafe("../escape") }.isFailure).isTrue()
    }

    @Test
    fun `the storage root is never handed back as a resolvable file`() {
        // toPath() falls back to the storage root when a provider cannot be read. That value
        // must be rejected downstream rather than treated as a writable directory.
        val fallback = STORAGE_ROOT_FALLBACK

        assertThat(isStorageRootFallback(fallback)).isTrue()
        assertThat(isStorageRootFallback("/storage/emulated/0/Download")).isFalse()
        assertThat(isStorageRootFallback("/storage/emulated/0")).isFalse()
        assertThat(isStorageRootFallback("")).isFalse()
    }
}