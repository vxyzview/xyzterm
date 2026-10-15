package com.rk.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.provider.DocumentsContract

/**
 * Returned by [toPath] when a content provider cannot be read.
 *
 * This is NOT a usable location. Downstream, `File(path).exists()` is true for it and it is
 * writable, so `Uri.toFileObject()` used to hand back a FileWrapper for the storage root and a
 * transient provider failure turned into a copy or delete against the wrong directory. Callers
 * must reject it with [isStorageRootFallback].
 */
const val STORAGE_ROOT_FALLBACK = "/storage/emulated/0/"

private const val TAG = "PathUtils"

/** True when [path] is the unusable [STORAGE_ROOT_FALLBACK] rather than a real location. */
fun isStorageRootFallback(path: String): Boolean =
    path == STORAGE_ROOT_FALLBACK || path == STORAGE_ROOT_FALLBACK.trimEnd('/')

object PathUtils {
    fun Uri.toPath(): String {
        val path = internalConvertUriToPath(application!!, this)
        // ponytail: only map the AOSP content-uri /document/ segment to /storage/.
        // The docId ':' separator is already consumed by internalConvertUriToPath;
        // a global replace(":", "/") silently corrupts real ':' in filenames.
        return path.replace("/document", "/storage")
    }

    private fun convertUriToPath(context: Context, uri: Uri?): String {
        val path = internalConvertUriToPath(context, uri)
        return path.replace("/document", "/storage")
    }

    private fun internalConvertUriToPath(context: Context, uri: Uri?): String {
        uri?.let {
            when {
                DocumentsContract.isTreeUri(it) -> {
                    // Handle tree URI
                    val docId = DocumentsContract.getTreeDocumentId(it)
                    return when {
                        docId.startsWith("primary:") -> {
                            // Internal storage
                            "${Environment.getExternalStorageDirectory()}/${docId.substringAfter("primary:")}"
                        }

                        else -> {
                            // External storage (SD card)
                            val split = docId.split(":")
                            "/storage/${split[0]}/${split.getOrElse(1) { "" }}"
                        }
                    }
                }

                DocumentsContract.isDocumentUri(context, it) -> {
                    // Handle document URI
                    val docId = DocumentsContract.getDocumentId(it)
                    when {
                        isExternalStorageDocument(it) -> {
                            val split = docId.split(":")
                            val type = split[0]
                            if ("primary".equals(type, ignoreCase = true)) {
                                return "${Environment.getExternalStorageDirectory()}/${
                                    split.getOrElse(
                                        1
                                    ) { "" }
                                }"
                            } else {
                                // Handle SD card
                                return "/storage/$type/${split.getOrElse(1) { "" }}"
                            }
                        }

                        isDownloadsDocument(it) -> {
                            val fileName = getFilePath(context, it)
                            if (fileName != null) {
                                return "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/$fileName"
                            }
                            // If unable to get the file name, fall back to a default path
                            return "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/"
                        }

                        isMediaDocument(it) -> {
                            val split = docId.split(":")
                            val type = split[0]
                            var contentUri: Uri? = null
                            when (type) {
                                "image" -> contentUri = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI

                                "video" -> contentUri = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI

                                "audio" -> contentUri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            }
                            val selection = "_id=?"
                            // split[1] is unguarded for a document id with no ':', which threw
                            // IndexOutOfBoundsException straight out of toPath().
                            val selectionArgs = arrayOf(split.getOrElse(1) { "" })
                            return getDataColumn(context, contentUri, selection, selectionArgs)
                                ?: STORAGE_ROOT_FALLBACK
                        }
                    }
                }

                "content".equals(it.scheme, ignoreCase = true) -> {
                    // Handle content URI
                    return getDataColumn(context, it, null, null) ?: STORAGE_ROOT_FALLBACK
                }

                "file".equals(it.scheme, ignoreCase = true) -> {
                    // Handle file URI
                    return it.path ?: STORAGE_ROOT_FALLBACK
                }

                else -> {}
            }
        }
        return STORAGE_ROOT_FALLBACK
    }

    private fun getDataColumn(context: Context, uri: Uri?, selection: String?, selectionArgs: Array<String>?): String? {
        val column = "_data"
        val projection = arrayOf(column)
        try {
            context.contentResolver.query(uri!!, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(column)
                    return cursor.getString(columnIndex)
                }
            }
        } catch (e: Exception) {
            // Swallowed silently before, which made every provider failure look like a
            // successful resolve to the storage-root fallback below.
            Log.w(TAG, "could not read $column from $uri", e)
        }
        return null
    }

    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    private fun isDownloadsDocument(uri: Uri): Boolean {
        return "com.android.providers.downloads.documents" == uri.authority
    }

    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    private fun getFilePath(context: Context, uri: Uri): String? {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndex("_display_name")
                    if (columnIndex != -1) {
                        return cursor.getString(columnIndex)
                    }
                }
            }
        } catch (e: Exception) {
            // Log the exception or handle it as needed
        }
        return null
    }
}
