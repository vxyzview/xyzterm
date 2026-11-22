package com.xyzterm.terminal

import android.content.Context
import com.xyzterm.file.localDir
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

internal const val INSTALL_MARKER = ".terminal_setup_ok_DO_NOT_REMOVE"

/**
 * The marker, not the directory contents, decides whether extraction finished. A kill mid-`tar` leaves partial files
 * behind; they must not block a retry.
 */
internal fun nextStageFor(tarballExists: Boolean, markerExists: Boolean): NEXT_STAGE =
    if (tarballExists && !markerExists) NEXT_STAGE.EXTRACTION else NEXT_STAGE.NONE

suspend fun CoroutineScope.getNextStage(context: Context): NEXT_STAGE =
    withContext(Dispatchers.IO) {
        if (isMainThread()) {
            throw RuntimeException("IO operation on the main thread")
        }

        val tarball = File(getTempDir(), "sandbox.tar.gz")
        val marker = File(localDir(context), INSTALL_MARKER)
        return@withContext nextStageFor(tarball.exists(), marker.exists())
    }
