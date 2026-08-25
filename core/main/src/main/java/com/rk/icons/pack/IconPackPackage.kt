package com.rk.icons.pack

import com.rk.extension.model.Package
import com.rk.file.FileObject
import com.rk.file.FileType
import com.rk.file.FileTypeManager
import java.io.File

data class LocalIconPack(
    val manifest: IconPackManifest,
    val installPath: String,
) : Package {
    override val id: String
        get() = manifest.id

    override val name: String
        get() = manifest.name

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
