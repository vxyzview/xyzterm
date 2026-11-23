package com.xyzterm.file

import android.app.Activity
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.xyzterm.DefaultScope
import com.xyzterm.utils.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.net.io.Util.copyStream

class FileManager(private val activity: ComponentActivity) {

    // Generic activity result handler
    private val activityResultLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            activityResultCallback?.invoke(result)
            activityResultCallback = null
        }
    private var activityResultCallback: ((ActivityResult) -> Unit)? = null

    // Generic directory picker
    private val directoryPickerLauncher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            directoryPickerCallback?.invoke(uri)
            directoryPickerCallback = null
        }
    private var directoryPickerCallback: ((Uri?) -> Unit)? = null

    private fun launchActivityForResult(intent: Intent, callback: (ActivityResult) -> Unit) {
        activityResultCallback = callback
        activityResultLauncher.launch(intent)
    }

    private fun launchDirectoryPicker(callback: (Uri?) -> Unit) {
        directoryPickerCallback = callback
        directoryPickerLauncher.launch(null)
    }

    fun requestOpenFile(mimeType: String = "*/*", callback: (Uri?) -> Unit) {
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            launchActivityForResult(this) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    callback(result.data?.data)
                } else {
                    callback(null)
                }
            }
        }
    }

    fun createNewFile(mimeType: String, title: String, callback: (FileObject?) -> Unit = {}) {
        launchActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = mimeType
                putExtra(Intent.EXTRA_TITLE, title)
            },
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                this.activity.lifecycleScope.launch {
                    val uri = result.data?.data

                    val fileObject = uri?.toFileObject(expectedIsFile = true)
                    callback(fileObject)
                }
            } else {
                callback(null)
            }
        }
    }

    var parentFile: FileObject? = null

    fun requestAddFile(parent: FileObject, callback: (FileObject?) -> Unit = {}) {
        parentFile = parent
        launchActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
        ) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                callback(null)
                parentFile = null
                return@launchActivityForResult
            }

            val sourceUri =
                result.data?.data
                    ?: run {
                        callback(null)
                        parentFile = null
                        return@launchActivityForResult
                    }

            DefaultScope.launch(Dispatchers.IO) {
                try {
                    val fileName = getFileName(activity.contentResolver, sourceUri)
                    val destinationFile = parentFile?.createChild(true, fileName)

                    destinationFile?.let { file ->
                        copyUriData(activity.contentResolver, sourceUri, file.toUri())
                        withContext(Dispatchers.Main) { callback(file) }
                    } ?: run { withContext(Dispatchers.Main) { callback(null) } }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) { callback(null) }
                } finally {
                    parentFile = null
                }
            }
        }
    }

    fun requestOpenDirectoryToSaveFile(file: FileObject, callback: (Boolean) -> Unit = {}) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
        val activities = application!!.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)

        if (activities.isNotEmpty()) {
            launchActivityForResult(intent) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val uri = result.data?.data
                    if (uri != null) {
                        DefaultScope.launch(Dispatchers.IO) {
                            try {
                                activity.contentResolver.openInputStream(file.toUri()).use { inputStream ->
                                    activity.contentResolver.openOutputStream(uri)?.use { outputStream ->
                                        inputStream?.copyTo(outputStream)
                                        callback(true)
                                    } ?: callback(false)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                                callback(false)
                            }
                        }
                    } else {
                        callback(false)
                    }
                } else {
                    callback(false)
                }
            }
        } else {
            launchDirectoryPicker { uri ->
                if (uri == null) {
                    callback(false)
                    return@launchDirectoryPicker
                }

                DefaultScope.launch(Dispatchers.IO) {
                    try {
                        activity.contentResolver.openInputStream(file.toUri()).use { inputStream ->
                            activity.contentResolver.openOutputStream(uri)?.use { outputStream ->
                                inputStream?.copyTo(outputStream)
                                callback(true)
                            } ?: callback(false)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        callback(false)
                    }
                }
            }
        }
    }

    private fun getFileName(contentResolver: ContentResolver, uri: Uri): String {
        var name = "default_file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun copyUriData(contentResolver: ContentResolver, sourceUri: Uri, destinationUri: Uri) {
        contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                copyStream(inputStream, outputStream)
            }
        } ?: throw RuntimeException("Failed to copy data from $sourceUri to $destinationUri")
    }
}
