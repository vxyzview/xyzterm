package com.rk.exec

import android.content.Context
import com.rk.file.child
import com.rk.file.localBinDir
import com.rk.file.localDir
import com.rk.file.localLibDir
import com.rk.file.sandboxDir
import com.rk.file.sandboxHomeDir
import com.rk.utils.getTempDir
import java.io.File

/**
 * Single source of truth for the proot sandbox's filesystem layout.
 *
 * Before this class, the same rootfs-vs-home-vs-tmp filter was hand-rolled in
 * [TerminalUtils.isTerminalInstalled] and [getNextStage] — and they had already
 * drifted on what counts as "installed". Funnelling both through [hasRootfsFiles]
 * / [isInstalled] makes the contract impossible to drift again.
 */
class ProotSandboxPaths(context: Context) {

    /** proot rootfs (the `-r` argument). Auto-created by the [sandboxDir] getter. */
    val sandboxRoot: File = sandboxDir(context)

    /** User home outside the rootfs; bound as `/home` inside. */
    val sandboxHome: File = sandboxHomeDir(context)

    /** Local bin dir: ships scripts (sandbox, setup, init, termux-x11, utils). */
    val sandboxBin: File = localBinDir(context)

    /** Local lib dir: `LD_LIBRARY_PATH` for scripts that need proot's host libs. */
    val sandboxLib: File = localLibDir(context)

    /** Per-session scratch dir; not part of the rootfs but lives under [sandboxRoot]. */
    val sandboxTmp: File = sandboxRoot.child("tmp")

    /** CWD handed to TerminalSession when no per-call override is set. */
    val processCwd: File = localDir(context)

    /** Pending rootfs tarball staged by [RootfsInstaller] before [getNextStage] runs setup.sh. */
    val pendingTarball: File = File(getTempDir(), "sandbox.tar.gz")

    /** ".terminal_setup_ok_DO_NOT_REMOVE" — the post-setup sentinel in [processCwd]. */
    private val setupMarker: File = processCwd.child(".terminal_setup_ok_DO_NOT_REMOVE")

    /**
     * True iff setup.sh completed and the rootfs has real content. Matches
     * the original [TerminalUtils.isTerminalInstalled] semantics exactly.
     */
    fun isInstalled(): Boolean = setupMarker.exists() && hasRootfsFiles()

    /**
     * True iff a fresh rootfs tarball is sitting in [pendingTarball] waiting
     * for setup.sh to extract it. Used by [getNextStage].
     */
    fun hasPendingTarball(): Boolean = pendingTarball.exists()

    /**
     * True iff [sandboxRoot] contains any files other than the always-present
     * home bind-mount and per-session tmp scratch dir. This is the shared
     * filter — [isInstalled] composes it with the marker check.
     */
    fun hasRootfsFiles(): Boolean = rootfsFiles().isNotEmpty()

    private fun rootfsFiles(): List<File> =
        sandboxRoot
            .listFiles()
            ?.filter { it.absolutePath != sandboxHome.absolutePath && it.absolutePath != sandboxTmp.absolutePath }
            ?: emptyList()
}