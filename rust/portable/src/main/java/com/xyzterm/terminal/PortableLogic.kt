package com.xyzterm.terminal

import kotlin.math.roundToInt

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
    if (entries.any { it == "sandbox" || it.startsWith("sandbox/") } && entries.any { it == "home" }) {
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
fun isWellFormedHost(path: String): Boolean =
    path.startsWith("/") &&
        path.length > 1 &&
        ':' !in path &&
        '\\' !in path &&
        path.none { it.isISOControl() }

/** Guest bind path validation (verbatim from UserBindings). */
fun isValidGuestPath(path: String): Boolean =
    isWellFormedHost(path) && path.trim('/').isNotEmpty() && path.split('/').none { it == ".." }

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

/** Compare "vX.Y.Z" (or "X.Y.Z") tags, ignoring suffixes like "-rc1" (verbatim from UpdateManager.isNewer). */
fun isNewer(latest: String, installed: String): Boolean {
    fun parse(v: String): List<Int>? {
        val parts = Regex("""v?(\d+)\.(\d+)\.(\d+)""").find(v.trim())?.groupValues?.drop(1) ?: return null
        return parts.map { it.toIntOrNull() ?: return null }
    }
    val a = parse(latest) ?: return false
    val b = parse(installed) ?: return false
    val diff =
        a.zip(b).firstOrNull { it.first != it.second }
            ?: return installed.trim().contains('-') && !latest.trim().contains('-')
    return diff.first > diff.second
}

/** File size formatting (verbatim from Utils.kt:formatFileSize). */
fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.getDefault(), "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(java.util.Locale.getDefault(), "%.1f GB", gb)
}

/** Compact number formatting (verbatim from Utils.kt:formatNumberCompact). */
fun formatNumberCompact(number: Int): String {
    if (number < 1000) return number.toString()

    val suffix = arrayOf("k", "m", "b")
    var value = number.toDouble()
    var index = -1

    while (value >= 1000 && index < suffix.lastIndex) {
        value /= 1000
        index++
    }

    return String.format("%.1f%s", value, suffix[index]).replace(".0", "")
}

/** Slider rounding (verbatim from ValueSlider.kt:roundedSliderMin). */
fun roundedSliderMin(min: Int): Int {
    if (min <= 0) return 0

    var magnitude = 1
    while (magnitude <= min / 10) {
        magnitude *= 10
    }

    val rounded = (min / magnitude) * magnitude
    return rounded
}

/** Hex colour parsing (verbatim from ColorUtils.kt:parseHex; ARGB int as decimal string). */
fun parseHexArgb(input: String): String {
    val hex = input.trim().removePrefix("#")
    if (hex.isEmpty()) return "null"
    val argb =
        when (hex.length) {
            3 -> {
                val r = hex[0].toString().repeat(2).toInt(16)
                val g = hex[1].toString().repeat(2).toInt(16)
                val b = hex[2].toString().repeat(2).toInt(16)
                (0xFF000000.toInt() or (r shl 16) or (g shl 8) or b)
            }
            6 -> (0xFF000000.toInt() or hex.toInt(16))
            8 -> hex.toLong(16).toInt()
            else -> return "null"
        }
    return argb.toString()
}

/** RGB to HSL label (verbatim from ColorUtils.kt:rgbToHsl + toHsl). */
fun hslLabel(rf: Float, gf: Float, bf: Float): String {
    val max = maxOf(rf, gf, bf)
    val min = minOf(rf, gf, bf)
    val delta = max - min

    var h = 0f
    val l = (max + min) / 2f
    val s = if (delta == 0f) 0f else delta / (1f - kotlin.math.abs(2f * l - 1f))

    if (delta != 0f) {
        h =
            when (max) {
                rf -> ((gf - bf) / delta) % 6f
                gf -> ((bf - rf) / delta) + 2f
                else -> ((rf - gf) / delta) + 4f
            }
        h /= 6f
        if (h < 0f) h += 1f
    }

    val hh = (h * 360f).roundToInt()
    val ss = (s * 100f).roundToInt()
    val ll = (l * 100f).roundToInt()
    return "$hh|$ss|$ll"
}

/** Source host detection (verbatim from SourceCodeProvider.fromUrl). */
fun fromUrlName(url: String): String =
    try {
        val hostName = java.net.URL(url).host
        when (hostName) {
            "github.com" -> "GitHub"
            "gitlab.com" -> "GitLab"
            "bitbucket.org" -> "BitBucket"
            else -> "Other"
        }
    } catch (e: java.net.MalformedURLException) {
        throw RuntimeException(e.message ?: "")
    }
