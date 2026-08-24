package com.rk.icons.pack

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rk.common.PackageType
import com.rk.extension.model.Package
import com.rk.extension.model.PackageAuthor
import com.rk.extension.model.Review
import com.rk.file.FileObject
import com.rk.file.FileType
import com.rk.file.FileTypeManager
import java.io.File

interface IconPackPackage : Package {
    override val type: PackageType
        get() = PackageType.ICON_PACK
}

data class LocalIconPack(
    val manifest: IconPackManifest,
    val installPath: String,
    override val createdAt: Long?,
    override val updatedAt: Long?,
    val initSize: Long?,
) : IconPackPackage {
    override val id: String
        get() = manifest.id

    override val name: String
        get() = manifest.name

    override val version: String
        get() = manifest.version

    override val author: PackageAuthor
        get() = manifest.author

    override val description: String?
        get() = manifest.description

    override val tags: List<String>
        get() = manifest.tags

    override val repository: String?
        get() = manifest.repository

    override val license: String?
        get() = manifest.license

    override val dependencies: List<String>
        get() = emptyList()

    override val recommendations: List<String>
        get() = emptyList()

    override val hasSettings: Boolean
        get() = false

    override val iconUrl: String
        get() = "$installPath/icon.png"

    override val readmeUrl: String
        get() = "$installPath/README.md"

    override val changelogUrl: String
        get() = "$installPath/CHANGELOG.md"

    override val minAppVersion: Int?
        get() = manifest.minAppVersion

    override val supportedArchitectures: List<String>?
        get() = null

    override val downloads: Int?
        get() = null

    override val rating: Float?
        get() = null

    override var size by mutableStateOf(initSize)

    override suspend fun getReviews(): List<Review> = emptyList()

    private val installDir: File
        get() = File(installPath)

    fun getIconFileForFile(file: FileObject, isExpanded: Boolean = false): File? {
        val fileName = file.getName()
        val isDirectory = file.isDirectory()
        return getIconFileForName(fileName, isDirectory, isExpanded)
    }

    fun getIconFileForName(fileName: String, isDirectory: Boolean, isExpanded: Boolean = false): File? {
        val path =
            if (isDirectory) {
                if (isExpanded) {
                    // First use folderNamesExpanded, then defaultFolderExpanded
                    manifest.icons.folderNamesExpanded[fileName.lowercase()]
                        ?.let { installDir.resolve(it) }
                        ?.takeIf { it.exists() } ?: installDir.resolve(manifest.icons.defaultFolderExpanded)
                } else {
                    // First use folderNames, then defaultFolder
                    manifest.icons.folderNames[fileName.lowercase()]
                        ?.let { installDir.resolve(it) }
                        ?.takeIf { it.exists() } ?: installDir.resolve(manifest.icons.defaultFolder)
                }
            } else {
                // First use fileNames, then fileExtensions, then languageNames, then defaultFile
                val ext = fileName.substringAfterLast(".", "")

                manifest.icons.fileNames[fileName.lowercase()]?.let { installDir.resolve(it) }?.takeIf { it.exists() }
                    ?: manifest.icons.fileExtensions[ext.lowercase()]
                        ?.let { installDir.resolve(it) }
                        ?.takeIf { it.exists() }
                    ?: manifest.icons.languageNames[FileTypeManager.fromExtension(ext).name.lowercase()]
                        ?.let { installDir.resolve(it) }
                        ?.takeIf { it.exists() }
                    ?: installDir.resolve(manifest.icons.defaultFile)
            }

        // If no icon was working (even the fallback ones)
        if (!path.exists()) return null

        return path
    }

    fun getIconFileForExt(fileExtension: String): File? {
        val path =
            // First use fileExtensions, then languageNames, then defaultFile
            manifest.icons.fileExtensions[fileExtension.lowercase()]
                ?.let { installDir.resolve(it) }
                ?.takeIf { it.exists() }
                ?: manifest.icons.languageNames[FileTypeManager.fromExtension(fileExtension).name.lowercase()]
                    ?.let { installDir.resolve(it) }
                    ?.takeIf { it.exists() }
                ?: installDir.resolve(manifest.icons.defaultFile)

        // If no icon was working (even the fallback ones)
        if (!path.exists()) return null

        return path
    }

    fun getIconFileForFileType(fileType: FileType): File? {
        val extension = fileType.extensions.firstOrNull()?.lowercase()
        val typeName = fileType.name.lowercase()

        val path =
            // First use fileExtensions, then languageNames, then defaultFile
            extension?.let { manifest.icons.fileExtensions[it] }?.let { installDir.resolve(it) }?.takeIf { it.exists() }
                ?: manifest.icons.languageNames[typeName]?.let { installDir.resolve(it) }?.takeIf { it.exists() }
                ?: installDir.resolve(manifest.icons.defaultFile)

        // If no icon was working (even the fallback ones)
        if (!path.exists()) return null

        return path
    }
}

