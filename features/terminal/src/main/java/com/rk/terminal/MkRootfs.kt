package com.rk.terminal

import android.content.Context
import android.os.Process
import android.system.Os
import com.rk.exec.TarExtractor
import com.rk.file.child
import com.rk.file.createFileIfNot
import com.rk.file.localDir
import com.rk.file.sandboxDir
import com.rk.file.sandboxHomeDir
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

suspend fun CoroutineScope.getNextStage(context: Context): NEXT_STAGE = withContext(Dispatchers.IO) {
    if (isMainThread()) {
        throw RuntimeException("IO operation on the main thread")
    }

    val sandboxFile = File(getTempDir(), "sandbox.tar.gz")
    val excluded =
        setOf(sandboxHomeDir().absolutePath, sandboxDir().child("tmp").absolutePath)
    val rootfsFiles = sandboxDir().listFiles()?.filter { it.absolutePath !in excluded } ?: emptyList()

    return@withContext when {
        // Fresh extraction: verified tarball present, nothing extracted yet.
        rootfsFiles.isEmpty() && sandboxFile.exists() -> NEXT_STAGE.EXTRACTION
        rootfsFiles.isEmpty() -> NEXT_STAGE.NONE
        localDir().child(".terminal_setup_ok_DO_NOT_REMOVE").exists() -> NEXT_STAGE.NONE
        else -> {
            // Rootfs files without the success marker mean a previous attempt
            // died mid-extraction; wipe so the retry starts clean instead of
            // being mistaken for an installed system.
            rootfsFiles.forEach { it.deleteRecursively() }
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

        TarExtractor.extract(tarball, sandbox) { fraction ->
            withContext(Dispatchers.Main.immediate) { onProgress(fraction) }
        }

        val etc = sandbox.child("etc")
        etc.mkdirs()

        etc.child("hostname").writeText("xyz\n")

        etc.child("resolv.conf").writeText(
            """
            nameserver 8.8.8.8
            nameserver 8.8.4.4
            """.trimIndent(),
        )

        etc.child("hosts").writeText(HOSTS.trimIndent())

        appendAndroidGroups(etc.child("group"))

        val aptConfDir = sandbox.child("etc/apt/apt.conf.d")
        aptConfDir.mkdirs()
        aptConfDir.child("99node-hook").writeText(NODE_APT_HOOK.trimIndent())

        val nodeHook = sandbox.child("usr/local/bin/node-postinstall.sh")
        nodeHook.parentFile?.mkdirs()
        nodeHook.writeText(NODE_POSTINSTALL.trimIndent())
        Os.chmod(nodeHook.path, Integer.parseInt("755", 8))

        val tmpDir = sandbox.child("tmp")
        tmpDir.mkdirs()
        Os.chmod(tmpDir.path, Integer.parseInt("1777", 8))

        tarball.delete()

        // DO NOT REMOVE THIS FILE JUST DON'T, TRUST ME (same contract as setup.sh)
        localDir().child(".terminal_setup_ok_DO_NOT_REMOVE").createFileIfNot()
    }

private fun appendAndroidGroups(groupFile: File) {
    val aid = Process.myGid()
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
