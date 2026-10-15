package com.rk.file

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.utils.toast
import java.io.File
import java.util.zip.ZipFile

fun File.child(fileName: String): File {
    return File(this, fileName)
}

/**
 * Like [child], but rejects path-traversal segments (`..`, `/`, `\`, `.`). Guards callers that build paths from
 * untrusted names (e.g. session ids from a BROWSABLE deep link). Throws IllegalArgumentException on a bad segment.
 */
fun File.childSafe(fileName: String): File {
    require(
        fileName.isNotEmpty() &&
            fileName != "." &&
            fileName != ".." &&
            !fileName.contains('/') &&
            !fileName.contains('\\')
    ) {
        "invalid path segment: $fileName"
    }
    return File(this, fileName)
}

/**
 * Like [childSafe], but sanitises instead of throwing.
 *
 * For names that come from outside the app and are expected to be *usable* rather than
 * validated — notably a SAF provider's `OpenableColumns.DISPLAY_NAME`, which is attacker-chosen.
 * `File(parent, "../../databases/app.db")` writes outside the parent; this cannot.
 *
 * The name is flattened to its last path component, then any remaining character outside a safe
 * set is replaced, and a name that reduces to nothing becomes "file".
 */
fun File.sanitizedChild(fileName: String): File {
    val flattened = fileName.replace('\\', '/').substringAfterLast('/')
    val cleaned = flattened.map { c -> if (c.isLetterOrDigit() || c in "._-") c else '_' }.joinToString("")
    val safe =
        when {
            cleaned.isEmpty() || cleaned.all { it == '.' } -> "file"
            else -> cleaned
        }
    return File(this, safe)
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

fun isFileManager(): Boolean {
    return ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) && Environment.isExternalStorageManager())
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
    // ponytail: zip-slip guard — every .xed (theme/extension/icon pack) is extracted
    // here, so a `../` or absolute entry name must not escape destDir.
    val destCanonical = destDir.canonicalPath
    ZipFile(this).use { zip ->
        zip.entries().asSequence().forEach { entry ->
            val target = File(destDir, entry.name)
            val targetCanonical = target.canonicalPath
            // separator boundary so a sibling-prefix dir (destXYZ) can't pass as inside dest.
            require(
                targetCanonical.startsWith("$destCanonical${File.separator}") &&
                    targetCanonical.length > destCanonical.length
            ) {
                "zip entry escapes destination: ${entry.name}"
            }
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { output -> input.copyTo(output) } }

                // Restore the Unix mode from the zip's external attributes. Without this every
                // extracted file comes out non-executable, which silently breaks any archive
                // that ships scripts or binaries rather than just throwing an error.
                val unixMode = entry.unixMode
                if (unixMode and 0b001_001_001 != 0) {
                    // Intentionally best-effort: some providers cannot set the bit.
                    runCatching { target.setExecutable(true, false) }
                }
                (unixMode ushr 16 and 0o777).takeIf { it != 0 }?.let { mode ->
                    runCatching { target.setReadable(mode and 0o444 != 0) }
                    runCatching { target.setWritable(mode and 0o222 != 0) }
                }
            }
        }
    }
}

suspend fun openWith(context: Context, file: FileObject) {
    try {
        val uri: Uri =
            when (file) {
                is UriWrapper -> {
                    file.toUri()
                }

                is FileWrapper -> {
                    FileProvider.getUriForFile(
                        context,
                        context.applicationContext.packageName + ".fileprovider",
                        file.file,
                    )
                }

                else -> {
                    throw RuntimeException("Unsupported FileObject")
                }
            }

        val mimeType = file.getMimeType(context)

        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }

        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        } else {
            Toast.makeText(context, strings.cant_handle.getString(), Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        toast(strings.file_open_denied.getString())
    }
}
