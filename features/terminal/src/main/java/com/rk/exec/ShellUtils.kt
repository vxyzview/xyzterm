package com.rk.exec

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ShellUtils {
    data class Result(val exitCode: Int, val output: String, val error: String, val timedOut: Boolean)

    /**
     * Cap on captured output per stream.
     *
     * These are the app's generic "run a command and read its output" primitives. With no cap,
     * `apt-get` — or `cat /dev/urandom` — grows the builder until the process is killed, and
     * `largeHeap="true"` means the JVM will allow it to get a long way there first. Nothing
     * downstream needs more than a few KB of `apt` output to report a failure.
     */
    const val MAX_CAPTURED_BYTES = 256 * 1024

    suspend fun runUbuntu(vararg command: String, timeoutSeconds: Long? = null): Result =
        withContext(Dispatchers.IO) { drain(ubuntuProcess(command = command.toList()), timeoutSeconds) }

    // ponytail: concurrent stdout/stderr drain; each reader .join() is bounded to the
    // same timeout as waitFor() so a proot grandchild keeping a pipe fd open past
    // destroyForcibly() can't hang the reader thread past the timeout you asked for.
    // Appends are synchronized: join(timeout) can expire with a reader thread still
    // appending, and StringBuilder.toString() racing an append is a torn/crashing read.
    private fun drain(process: Process, timeoutSeconds: Long?): Result {
        val output = StringBuilder()
        val error = StringBuilder()

        // The readers also leaked the underlying fd: no use {}, and the streams were never
        // closed. Truncation is marked so a caller can tell a capped read from a complete one.
        val outputThread = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        synchronized(output) {
                            if (output.length < MAX_CAPTURED_BYTES) {
                                output.append(line).append('\n')
                                if (output.length >= MAX_CAPTURED_BYTES) output.append("\n[output truncated]\n")
                            }
                        }
                    }
                }
            }
        }
        val errorThread = Thread {
            runCatching {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        synchronized(error) {
                            if (error.length < MAX_CAPTURED_BYTES) {
                                error.append(line).append('\n')
                                if (error.length >= MAX_CAPTURED_BYTES) error.append("\n[output truncated]\n")
                            }
                        }
                    }
                }
            }
        }

        outputThread.start()
        errorThread.start()

        val timedOut =
            if (timeoutSeconds != null) {
                !process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            } else {
                process.waitFor()
                false
            }

        if (timedOut) process.destroyForcibly()

        val joinMs = (timeoutSeconds ?: 0) * 1000 // join(0) = wait forever
        outputThread.join(joinMs)
        errorThread.join(joinMs)

        return Result(
            exitCode = if (timedOut) -1 else process.exitValue(),
            output = synchronized(output) { output.toString() }.trim(),
            error = synchronized(error) { error.toString() }.trim(),
            timedOut = timedOut,
        )
    }
}
