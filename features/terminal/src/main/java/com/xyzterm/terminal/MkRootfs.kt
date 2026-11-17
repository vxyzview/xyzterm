package com.xyzterm.terminal

import android.content.Context
import com.xyzterm.file.child
import com.xyzterm.file.sandboxDir
import com.xyzterm.file.sandboxHomeDir
import com.xyzterm.utils.getTempDir
import com.xyzterm.utils.isMainThread
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class NEXT_STAGE {
    NONE,
    EXTRACTION,
}

suspend fun CoroutineScope.getNextStage(context: Context): NEXT_STAGE =
    withContext(Dispatchers.IO) {
        if (isMainThread()) {
            throw RuntimeException("IO operation on the main thread")
        }

        val sandboxFile = File(getTempDir(), "sandbox.tar.gz")
        val rootfsFiles =
            sandboxDir().listFiles()?.filter {
                it.absolutePath != sandboxHomeDir().absolutePath &&
                    it.absolutePath != sandboxDir().child("tmp").absolutePath
            } ?: emptyList()

        return@withContext if (sandboxFile.exists().not() || rootfsFiles.isEmpty().not()) {
            NEXT_STAGE.NONE
        } else {
            NEXT_STAGE.EXTRACTION
        }
    }
