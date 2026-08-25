package com.rk.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.util.Log
import com.rk.exec.TarExtractor
import com.rk.file.TERMINAL_SETUP_OK_MARKER
import com.rk.file.child
import com.rk.file.createFileIfNot
import com.rk.file.localDir
import com.rk.file.rootfsFiles
import com.rk.file.sandboxDir
import com.rk.utils.getTempDir
import com.rk.utils.isMainThread
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class NEXT_STAGE {
    NONE,
    EXTRACTION,
}

private const val TAG = "TerminalInstall"

suspend fun CoroutineScope.getNextStage(context: Context): NEXT_STAGE = withContext(Dispatchers.IO) {
    if (isMainThread()) {
        throw RuntimeException("IO operation on the main thread")
    }

    val sandboxFile = File(getTempDir(), "sandbox.tar.gz")
    val rootfsFiles = rootfsFiles()

    return@withContext when {
        // Fresh extraction: verified tarball present, nothing extracted yet.
        rootfsFiles.isEmpty() && sandboxFile.exists() -> NEXT_STAGE.EXTRACTION
        rootfsFiles.isEmpty() -> NEXT_STAGE.NONE
        localDir().child(TERMINAL_SETUP_OK_MARKER).exists() -> NEXT_STAGE.NONE
        else -> {
            // Rootfs files without the success marker mean a previous attempt
            // died mid-extraction; wipe so the retry starts clean instead of
            // being mistaken for an installed system.
            rootfsFiles.forEach {
                if (!it.deleteRecursively()) {
                    throw IOException("Failed to wipe incomplete rootfs: ${it.absolutePath}")
                }
            }
            if (sandboxFile.exists()) NEXT_STAGE.EXTRACTION else NEXT_STAGE.NONE
        }
    }
}

/**
 * Extracts the downloaded rootfs natively into [sandboxDir] (no proot, so no
 * ptrace overhead on every syscall) and applies the post-extract configuration
 * that setup.sh used to perform after its tar step.
 */
suspend fun extractRootfs(onProgress: (Float) -> Unit) =
    withContext(Dispatchers.IO) {
        val sandbox = sandboxDir()
        val tarball = File(getTempDir(), "sandbox.tar.gz")

        Log.w(TAG, "extract start: ${tarball.absolutePath} bytes=${tarball.length()}")

        val mainHandler = Handler(Looper.getMainLooper())
        TarExtractor.extract(tarball, sandbox) { fraction ->
            mainHandler.post { onProgress(fraction) }
        }
        Log.w(TAG, "tar extract ok, applying rootfs config")

        val etc = sandbox.child("etc")
        etc.mkdirs()

        writeRootfsFile(etc.child("hostname"), "xyz\n")

        writeRootfsFile(
            etc.child("resolv.conf"),
            """
            nameserver 8.8.8.8
            nameserver 8.8.4.4
            """.trimIndent(),
        )

        writeRootfsFile(etc.child("hosts"), HOSTS.trimIndent())

        appendAndroidGroups(etc.child("group"))

        val aptConfDir = sandbox.child("etc/apt/apt.conf.d")
        aptConfDir.mkdirs()
        writeRootfsFile(aptConfDir.child("99node-hook"), NODE_APT_HOOK.trimIndent())

        val nodeHook = sandbox.child("usr/local/bin/node-postinstall.sh")
        nodeHook.parentFile?.mkdirs()
        writeRootfsFile(nodeHook, NODE_POSTINSTALL.trimIndent())
        Os.chmod(nodeHook.path, Integer.parseInt("755", 8))

        val tmpDir = sandbox.child("tmp")
        tmpDir.mkdirs()
        Os.chmod(tmpDir.path, Integer.parseInt("1777", 8))

        // Marker goes down before the tarball is deleted: deleting the tarball
        // first leaves a window where a kill loses the complete rootfs.
        // DO NOT REMOVE THIS FILE JUST DON'T, TRUST ME (same contract as setup.sh)
        localDir().child(TERMINAL_SETUP_OK_MARKER).createFileIfNot()
        Log.w(TAG, "install marker written, removing tarball")

        tarball.delete()
    }

/**
 * Rootfs archives ship some directories with restrictive modes; a plain
 * writeText into one fails with EACCES. Open the parent once and retry before
 * giving up (the extractor's deferred chmod pass only covers its own run).
 */
private fun writeRootfsFile(file: File, content: String) {
    try {
        file.writeText(content)
    } catch (e: IOException) {
        file.parentFile?.let { parent -> runCatching { Os.chmod(parent.path, Integer.parseInt("755", 8)) } }
        file.writeText(content)
    }
}

private fun appendAndroidGroups(groupFile: File) {
    val aid = Os.getgid()
    val candidates =
        listOf(
            "inet:x:3003",
            "everybody:x:9997",
            "android_app:x:20455",
            "android_debug:x:50455",
            "android_cache:x:${10000 + aid}",
            "android_storage:x:${40000 + aid}",
            "android_media:x:${50000 + aid}",
            "android_external_storage:x:1077",
        )

    val existing = if (groupFile.isFile) groupFile.readText() else ""
    val missing = candidates.filter { ":${it.substringAfterLast(':')}" !in existing }
    if (missing.isEmpty()) return

    val separator = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
    groupFile.appendText(separator + missing.joinToString("") { "$it\n" })
}

private const val HOSTS =
    """
    127.0.0.1   localhost.localdomain localhost xyz

    # IPv6.
    ::1         localhost.localdomain localhost ip6-localhost ip6-loopback
    fe00::0     ip6-localnet
    ff00::0     ip6-mcastprefix
    ff02::1     ip6-allnodes
    ff02::2     ip6-allrouters
    ff02::3     ip6-allhosts
    """

private const val NODE_APT_HOOK =
    """
    DPkg::Post-Invoke {
        "if [ -x /usr/bin/node ]; then /usr/local/bin/node-postinstall.sh; fi";
    };
    """

private val NODE_POSTINSTALL =
    """
    #!/bin/sh
    set -e

    echo "[node-hook] Running Node.js post-install hook..."

    JEMALLOC=""

    echo "[node-hook] Searching for jemalloc..."

    for path in \
        /usr/lib/*/libjemalloc.so* \
        /usr/lib/libjemalloc.so* \
        /lib/*/libjemalloc.so* \
        /lib/libjemalloc.so*; do

        if [ -e "${'$'}path" ]; then
            JEMALLOC="${'$'}path"
            echo "[node-hook] Found jemalloc: ${'$'}JEMALLOC"
            break
        fi
    done

    if [ -z "${'$'}JEMALLOC" ]; then
        echo "[node-hook] jemalloc not installed, skipping"
        exit 0
    fi

    if [ ! -e /usr/bin/node ]; then
        echo "[node-hook] Node binary not found, skipping"
        exit 0
    fi

    if [ -e /usr/bin/node.distrib ]; then
        echo "[node-hook] Node already wrapped, skipping"
        exit 0
    fi

    echo "[node-hook] Verifying node binary..."

    if file /usr/bin/node | grep -q ELF; then
        echo "[node-hook] Wrapping Node.js with jemalloc..."

        mv /usr/bin/node /usr/bin/node.distrib

        cat > /usr/bin/node << WRAP
    #!/bin/sh
    LD_PRELOAD=${'$'}JEMALLOC exec /usr/bin/node.distrib "\${'$'}@"
    WRAP

        chmod +x /usr/bin/node

        echo "[node-hook] Node wrapper installed successfully"
    else
        echo "[node-hook] /usr/bin/node is not an ELF binary, skipping"
    fi
    """
