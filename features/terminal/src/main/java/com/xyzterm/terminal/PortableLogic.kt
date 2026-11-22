package com.xyzterm.terminal

import com.xyzterm.exec.UserBindings

/**
 * Adapters between the real app's pure logic and the generated rust-anything JNI shadow.
 *
 * These carry the exact names/signatures the generated `PortableLogicKtShadow` calls. They
 * delegate to the original Kotlin functions, so the shadow's default mode returns the original
 * result while comparing it with the Rust port. `rust-anything.mode=new` switches to Rust.
 *
 * `matchesLogLevel` and `shouldCaptureLog` are verbatim extracts of private functions in
 * core/main; keep them in sync with AppLogs.kt and LogcatService.kt.
 */

fun detectBackupLayoutName(entriesJoined: String): String {
    val entries = if (entriesJoined.isEmpty()) emptyList() else entriesJoined.split('\n')
    return detectBackupLayout(entries).name
}

fun isWellFormedHost(path: String): Boolean = UserBindings.isWellFormedHost(path)

fun isValidGuestPath(path: String): Boolean = UserBindings.isValidGuestPath(path)

fun nameSafe(id: String): String = MkSession.nameSafe(id)

fun hasBinaryChars(text: String): Boolean = com.xyzterm.utils.hasBinaryChars(text)

fun parseExtensions(input: String): List<String> = com.xyzterm.utils.parseExtensions(input)

fun parseExtensionsJoined(input: String): String = parseExtensions(input).joinToString("\n")

fun isSafeFileName(fileName: String): Boolean = com.xyzterm.file.isSafeFileName(fileName)

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

fun isNewer(latest: String, installed: String): Boolean {
    // Verbatim from core/main/.../UpdateManager.kt; that function is private in its own module.
    val parse = { v: String ->
        Regex("""v?(\d+)\.(\d+)\.(\d+)""").matchEntire(v.trim())?.groupValues?.drop(1)?.map { it.toInt() }
    }
    val a = parse(latest) ?: return false
    val b = parse(installed) ?: return true
    return a.zip(b).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
}

fun detectBackupLayoutViaRust(entries: List<String>): BackupLayout =
    BackupLayout.valueOf(PortableLogicKtShadow.detectBackupLayoutName(entries.joinToString("\n")))

fun nameSafeViaRust(id: String): String = PortableLogicKtShadow.nameSafe(id)

fun isNewerViaRust(latest: String, installed: String): Boolean =
    PortableLogicKtShadow.isNewer(latest, installed)
