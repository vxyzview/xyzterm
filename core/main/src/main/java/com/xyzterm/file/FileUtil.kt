package com.xyzterm.file

import java.io.File
import java.util.zip.ZipFile

fun File.child(fileName: String): File {
    return File(this, fileName)
}

/**
 * Like [child], but rejects path-traversal segments (`..`, `/`, `\`, `.`). Guards callers that build paths from
 * untrusted names (e.g. session ids from a BROWSABLE deep link). Throws IllegalArgumentException on a bad segment.
 */
fun isSafeFileName(fileName: String): Boolean =
    fileName.isNotEmpty() && fileName != "." && fileName != ".." && !fileName.contains('/') && !fileName.contains('\\')

fun File.childSafe(fileName: String): File {
    require(isSafeFileName(fileName)) { "invalid path segment: $fileName" }
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

/**
 * Unzips the current file to the specified destination directory.
 *
 * @param destDir The directory where the contents of the zip file will be extracted.
 */
fun File.unzipTo(destDir: File) {
    if (!destDir.exists()) {
        destDir.mkdirs()
    }
    // ponytail: zip-slip guard — every downloaded archive is extracted
    // here, so a `../` or absolute entry name must not escape destDir.
    val destCanonical = destDir.canonicalPath
    ZipFile(this).use { zip ->
        zip.entries().asSequence().forEach { entry ->
            val target = File(destDir, entry.name)
            val targetCanonical = target.canonicalPath
            // separator boundary so a sibling-prefix dir (destXYZ) can't pass as inside dest.
            require(
                targetCanonical.startsWith("$destCanonical${File.separator}") &&
                    targetCanonical.length > destCanonical.length,
            ) {
                "zip entry escapes destination: ${entry.name}"
            }
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            }
        }
    }
}
