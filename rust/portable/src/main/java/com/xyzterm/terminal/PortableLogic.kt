package com.xyzterm.terminal

/**
 * Faithful extractions of the pure, platform-independent logic of xyzterm.
 *
 * Every function body below is copied verbatim from its original file; only Android,
 * coroutine and resource dependencies were left out so the JVM harness can compile
 * and record these functions without an Android SDK:
 *
 *  - detectBackupLayout / BackupLayout: features/terminal/.../TerminalBackup.kt:14-30
 *  - isWellFormedHost / isValidGuestPath: features/terminal/.../exec/UserBindings.kt:45,51,53
 *  - nameSafe: features/terminal/.../terminal/MkSession.kt:21-31
 *  - isNewer: core/main/.../UpdateManager.kt:50-58
 */

/** Archive layouts understood by TerminalBackup.restore. */
internal enum class BackupLayout {
    V1_ROOT,
    V2_SANDBOX_HOME,
}

/** Detect the archive layout from its top-level entry names. */
internal fun detectBackupLayout(entries: List<String>): BackupLayout =
    if (entries.any { it == "sandbox" || it.startsWith("sandbox/") }) {
        BackupLayout.V2_SANDBOX_HOME
    } else {
        BackupLayout.V1_ROOT
    }

/**
 * Bridge adapter for the JUnit-tested function above. The JNI bridge carries only
 * strings, so the list is passed newline-separated ("" means an empty list) and the
 * enum is returned by name. The original function's body is untouched.
 */
fun detectBackupLayoutName(entriesJoined: String): String {
    val entries = if (entriesJoined.isEmpty()) emptyList() else entriesJoined.split('\n')
    return detectBackupLayout(entries).name
}

/** Shape check for a host bind path (verbatim from UserBindings). */
fun isWellFormedHost(path: String): Boolean = path.startsWith("/") && path.length > 1

/** Guest bind path validation (verbatim from UserBindings). */
fun isValidGuestPath(path: String): Boolean = path.startsWith("/") && path.length > 1

/** Sanitize a session id for use as a directory name (verbatim from MkSession.nameSafe). */
fun nameSafe(id: String): String {
    val clean = id.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    val base =
        when {
            clean.isEmpty() || clean == "." || clean == ".." -> "session"
            else -> clean
        }
    return "${base}_${id.hashCode().toUInt().toString(36)}"
}

/** Compare "vX.Y.Z" (or "X.Y.Z") tags (verbatim from UpdateManager.isNewer). */
fun isNewer(latest: String, installed: String): Boolean {
    val parse = { v: String ->
        Regex("""v?(\d+)\.(\d+)\.(\d+)""").matchEntire(v.trim())?.groupValues?.drop(1)?.map { it.toInt() }
    }
    val a = parse(latest) ?: return false
    val b = parse(installed) ?: return true
    return a.zip(b).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
}
