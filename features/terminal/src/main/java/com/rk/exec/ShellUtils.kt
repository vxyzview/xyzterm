package com.rk.exec

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ShellUtils {
    data class Result(val exitCode: Int, val output: String, val error: String, val timedOut: Boolean)

    suspend fun runUbuntu(vararg command: String, timeoutSeconds: Long? = null): Result =
        withContext(Dispatchers.IO) {
            drain(ubuntuProcess(command = command.toList()), timeoutSeconds)
        }

    // ponytail: concurrent stdout/stderr drain; each reader .join() is bounded to the
    // same timeout as waitFor() so a proot grandchild keeping a pipe fd open past
    // destroyForcibly() can't hang the reader thread past the timeout you asked for.
    // Appends are synchronized: join(timeout) can expire with a reader thread still
    // appending, and StringBuilder.toString() racing an append is a torn/crashing read.
    private fun drain(process: Process, timeoutSeconds: Long?): Result {
        val output = StringBuilder()
        val error = StringBuilder()

        val outputThread = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(output) { output.appendLine(line) }
                }
            }
        }
        val errorThread = Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(error) { error.appendLine(line) }
                }
            }
        }

        outputThread.start()
        errorThread.start()

        val timedOut = if (timeoutSeconds != null) {
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
