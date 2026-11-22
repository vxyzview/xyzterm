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

/** Binary-content sniffing (verbatim from Utils.kt:hasBinaryChars). */
fun hasBinaryChars(text: String): Boolean {
    val threshold = 0.3
    val checkedCharacters = 1024

    val checkText = text.take(checkedCharacters)
    if (checkText.isEmpty()) return false

    if (checkText.any { it.toInt() == 0 }) return true

    val unusualCharCount = checkText.count { c ->
        c.isISOControl() && c.toInt() != 9 && c.toInt() != 10 && c.toInt() != 12 && c.toInt() != 13
    }

    return unusualCharCount.toDouble() / checkText.length > threshold
}

/** Extension parsing (verbatim from Utils.kt:parseExtensions). */
fun parseExtensions(input: String): List<String> {
    return input.split(",", " ").map { it.trim().trimStart('.') }.filter { it.isNotEmpty() }
}

/** String-only seam for the list-returning parser above. */
fun parseExtensionsJoined(input: String): String = parseExtensions(input).joinToString("\n")

/** Path-traversal predicate (verbatim condition from FileUtil.kt:childSafe). */
fun isSafeFileName(fileName: String): Boolean =
    fileName.isNotEmpty() &&
        fileName != "." &&
        fileName != ".." &&
        !fileName.contains('/') &&
        !fileName.contains('\\')

/** Log-priority match (verbatim from AppLogs.kt:matchesLogLevel; level value replaces the enum). */
fun matchesLogLevel(line: String, levelValue: Int): Boolean {
    if (line.isEmpty()) return true

    val idx = line.indexOf('/')
    if (idx != 1) return true

    val priority =
        when (line[0]) {
            'V',
            'D' -> 5
            'I' -> 3
            'W' -> 2
            'E',
            'F' -> 1
            else -> 5
        }

    return priority <= levelValue
}

/** Noisy-log filter (verbatim from LogcatService.kt:shouldCaptureLog). */
fun shouldCaptureLog(line: String): Boolean {
    if (line.isEmpty()) {
        return false
    }

    val noisyKeywords =
        listOf(
            "ViewRootImpl",
            "Choreographer",
            "OpenGLRenderer",
            "InputMethodManager",
            "RenderThread",
            "libEGL",
            "EGL_emulation",
            "CompatChangeReporter",
            "vndksupport",
            "ConfigStore",
            "Mono",
            "me.weishu.reflection",
            "chatty",
            "HostConnection",
            "gralloc",
            "GestureDetector",
            "WindowManager",
            "ImeFocusController",
            "Insets"
        )
    if (noisyKeywords.any { line.contains(it, ignoreCase = true) }) {
        return false
    }

    if (!line.contains('/') || !line.contains('(')) {
        return true
    }

    val slashIdx = line.indexOf('/')
    val parenIdx = line.indexOf('(')
    if (slashIdx != 1 || parenIdx <= slashIdx) {
        return true
    }

    val priority = line[0]
    val tag = line.substring(slashIdx + 1, parenIdx).trim()

    if (priority == 'W' || priority == 'E' || priority == 'F' || priority == 'A') {
        return true
    }

    if (tag.startsWith("System.out") || tag.startsWith("System.err")) {
        return true
    }

    return true
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
