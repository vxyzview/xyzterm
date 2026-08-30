package com.rk.terminal

import android.content.Context
import com.rk.exec.ProotSandboxPaths
import com.rk.utils.isMainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class NEXT_STAGE {
    NONE,
    EXTRACTION,
}

suspend fun CoroutineScope.getNextStage(context: Context): NEXT_STAGE = withContext(Dispatchers.IO) {
    if (isMainThread()) {
        throw RuntimeException("IO operation on the main thread")
    }

    val paths = ProotSandboxPaths(context)

    return@withContext if (!paths.hasPendingTarball() || paths.hasRootfsFiles()) {
        NEXT_STAGE.NONE
    } else {
        NEXT_STAGE.EXTRACTION
    }
}