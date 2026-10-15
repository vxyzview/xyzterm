package com.rk.terminal

import android.content.Context
import com.rk.file.localDir
import com.rk.utils.getTempDir
import com.rk.utils.isMainThread
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class NEXT_STAGE {
    NONE,
    EXTRACTION,
}

enum class StageState {
    /** A complete rootfs with its marker. Nothing to do. */
    INSTALLED,

    /** A hash-verified tarball is present but the rootfs is not complete. */
    NEEDS_EXTRACTION,

    /** No tarball and no complete rootfs — the install has to start from scratch. */
    NOWHERE_TO_START,
}

/** Written by setup.sh only after a fully staged extraction has been swapped into place. */
const val ROOTFS_COMPLETE_MARKER = ".rootfs-complete"

/**
 * Classifies the install from an explicit marker rather than from directory contents.
 *
 * The previous check was `sandboxDir().listFiles()?.isNotEmpty()`, and extraction wrote straight
 * into the live `sandbox/`. A tar killed by an OOM-kill, a battery pull or ENOSPC therefore left a
 * *partial* tree, which is non-empty — so the app concluded "installed", or concluded "not
 * installed" while refusing to re-extract a verified tarball that was sitting right there. Both
 * outcomes left the install screen spinning with nothing it could do.
 */
internal fun stageState(tarballPresent: Boolean, markerPresent: Boolean): StageState =
    when {
        markerPresent -> StageState.INSTALLED
        tarballPresent -> StageState.NEEDS_EXTRACTION
        else -> StageState.NOWHERE_TO_START
    }

internal fun rootfsMarker(): File = File(com.rk.file.sandboxDir(), ROOTFS_COMPLETE_MARKER)

internal fun stagingDir(): File = File(localDir(), "sandbox.staging")

internal fun leftoverStagingDir(): File = File(localDir(), "sandbox.staging")

suspend fun CoroutineScope.getNextStage(context: Context): NEXT_STAGE =
    withContext(Dispatchers.IO) {
        if (isMainThread()) {
            throw RuntimeException("IO operation on the main thread")
        }

        val tarball = File(getTempDir(), "sandbox.tar.gz")
        val state = stageState(tarballPresent = tarball.exists(), markerPresent = rootfsMarker().exists())

        // An interrupted run leaves a staging tree behind; drop it so the next attempt starts
        // clean rather than layering a partial extraction on top of a partial extraction.
        if (state == StageState.NEEDS_EXTRACTION) {
            runCatching { leftoverStagingDir().deleteRecursively() }
        }

        return@withContext when (state) {
            StageState.INSTALLED, StageState.NOWHERE_TO_START -> NEXT_STAGE.NONE
            StageState.NEEDS_EXTRACTION -> NEXT_STAGE.EXTRACTION
        }
    }