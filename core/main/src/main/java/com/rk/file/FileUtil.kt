package com.rk.file

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

fun File.child(fileName: String): File {
    return File(this, fileName)
}

fun File.createFileIfNot(): File {
    if (parentFile?.exists()?.not() == true) {
        parentFile!!.mkdirs()
    }
    if (exists().not()) {
        createNewFile()
    }
    return this
}

suspend fun FileObject.createFileIfNot(): FileObject {
    if (getParentFile()?.exists()?.not() == true) {
        getParentFile()!!.mkdirs()
    }
    if (exists().not()) {
        createNewFile()
    }
    return this
}

fun File.createDirIfNot(): File {
    if (exists().not()) {
        mkdirs()
    }
    return this
}

suspend fun FileObject.createDirIfNot(): FileObject {
    if (exists().not()) {
        mkdirs()
    }
    return this
}

fun File.toFileWrapper(): FileWrapper {
    return FileWrapper(this)
}

/**
 * Unzips the current file to the specified destination directory.
 *
 * @param destDir The directory where the contents of the zip file will be extracted.
 */
fun File.unzipTo(destDir: File) {
    if (!destDir.exists()) {
        destDir.mkdirs()
    }
    val root = destDir.canonicalFile
    ZipFile(this).use { zip ->
        zip.entries().asSequence().forEach { entry ->
            val segments = entry.name.split('/').filter { it.isNotEmpty() && it != "." }
            if (segments.isEmpty() || ".." in segments) {
                throw IOException("Illegal zip entry path: ${entry.name}")
            }
            val target = File(root, segments.joinToString("/"))
            // Canonical containment guards symlinked parents escaping [destDir].
            val canonical = target.canonicalFile
            if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) {
                throw IOException("Zip entry escapes destination: ${entry.name}")
            }
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}

