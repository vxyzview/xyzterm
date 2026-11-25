package com.xyzterm.terminal

import com.xyzterm.exec.UserBindings
import kotlin.math.roundToInt

/**
 * Adapters between the real app's pure logic and the generated rust-anything JNI shadow.
 *
 * These carry the exact names/signatures the generated `PortableLogicKtShadow` calls. They delegate to the original
 * Kotlin functions, so the shadow's default mode returns the original result while comparing it with the Rust port.
 * `rust-anything.mode=new` switches to Rust.
 *
 * `matchesLogLevel` and `shouldCaptureLog` are verbatim extracts of private functions in core/main; keep them in sync
 * with AppLogs.kt and LogcatService.kt.
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
            "Insets",
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
    // Verbatim from core/main/.../UpdateManager.kt, which is internal in its own module.
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

fun nameSafeViaRust(id: String): String = PortableLogicKtShadow.nameSafe(id)

fun isNewerViaRust(latest: String, installed: String): Boolean = PortableLogicKtShadow.isNewer(latest, installed)

// Wave 2 adapters. Bodies are verbatim from core/main where the original is private or
// returns an Android/Compose type; keep them in sync with Utils.kt, ValueSlider.kt,
// ColorUtils.kt and SourceCodeProvider.kt.

fun formatFileSize(bytes: Long): String = com.xyzterm.utils.formatFileSize(bytes)

fun formatNumberCompact(number: Int): String = com.xyzterm.utils.formatNumberCompact(number)

fun roundedSliderMin(min: Int): Int {
    if (min <= 0) return 0
    var magnitude = 1
    while (magnitude <= min / 10) {
        magnitude *= 10
    }
    return (min / magnitude) * magnitude
}

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
    return "${(h * 360f).roundToInt()}|${(s * 100f).roundToInt()}|${(l * 100f).roundToInt()}"
}

fun fromUrlName(url: String): String =
    try {
        when (java.net.URL(url).host) {
            "github.com" -> "GitHub"
            "gitlab.com" -> "GitLab"
            "bitbucket.org" -> "BitBucket"
            else -> "Other"
        }
    } catch (e: java.net.MalformedURLException) {
        throw RuntimeException(e.message ?: "")
    }
