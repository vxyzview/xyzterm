package com.rk.crashhandler

import java.io.File

/**
 * The on-disk crash report.
 *
 * `CrashHandler` only wrote `crash.log` on the path where it could not show `CrashActivity` —
 * so on a normal crash a release build produced *no file at all*, and a user reporting a crash
 * had nothing to attach. It is now written unconditionally.
 *
 * Kept as a capped, rotating set rather than one appended file: `appendText` on an unbounded
 * file means the second crash in a session costs more I/O than the first, and a device that
 * crash-loops fills its own storage.
 */
object CrashLog {
    const val MAX_BYTES = 256L * 1024

    /** Oldest first; the newest is [dir] itself. */
    fun files(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && (f.name == FILE_NAME || f.name.startsWith("$FILE_NAME.")) }
            ?.sortedBy { it.name }
            .orEmpty()

    fun newest(dir: File): File = File(dir, FILE_NAME)

    /**
     * Appends [text], rotating [newest] aside first if it would exceed [maxBytes].
     *
     * Returns true when the text reached disk. Never throws: this runs inside the uncaught
     * exception handler, where a failure to write must not become a second crash.
     */
    fun append(dir: File, text: String, maxBytes: Long = MAX_BYTES): Boolean =
        runCatching {
                dir.mkdirs()
                val current = newest(dir)
                if (current.exists() && current.length() + text.length > maxBytes) {
                    // Drop the oldest, shift the rest down, keep one generation of history.
                    files(dir).filter { it != current }.forEach { it.delete() }
                    File(dir, "$FILE_NAME.1").takeIf { it.exists() }?.delete()
                    if (current.exists()) current.renameTo(File(dir, "$FILE_NAME.1"))
                }
                current.appendText(text)
                true
            }
            .getOrDefault(false)

    /** The most recent report, or empty when there is none. */
    fun read(dir: File, maxBytes: Long = MAX_BYTES): String =
        runCatching {
                val parts = files(dir).reversed()
                parts.joinToString("\n\n") { it.readText() }.takeLast(maxBytes.toInt())
            }
            .getOrDefault("")

    /** Header prepended to a shared report, so the reader knows what they are looking at. */
    fun shareHeader(appLabel: String, versionName: String, buildType: String): String =
        buildString {
            appendLine("=== $appLabel $versionName ($buildType) ===")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("Abi: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            appendLine()
        }

    const val FILE_NAME = "crash.log"
}