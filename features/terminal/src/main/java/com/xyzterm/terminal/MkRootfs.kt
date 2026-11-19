package com.xyzterm.terminal

import android.content.Context
import com.xyzterm.file.child
import com.xyzterm.file.localDir
import com.xyzterm.file.sandboxDir
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
        val installed =
            localDir().child(".terminal_setup_ok_DO_NOT_REMOVE").exists() &&
                sandboxDir().child("etc/pacman.conf").exists()

        // A downloaded image whose extraction never finished (no marker) has to
        // be re-extracted, otherwise the install prompt reappears forever.
        return@withContext if (sandboxFile.exists() && !installed) {
            NEXT_STAGE.EXTRACTION
        } else {
            NEXT_STAGE.NONE
        }
    }
