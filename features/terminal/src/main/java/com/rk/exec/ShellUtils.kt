package com.rk.exec

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ShellUtils {
    data class Result(val exitCode: Int, val output: String, val error: String, val timedOut: Boolean)

    suspend fun run(vararg command: String, timeoutSeconds: Long? = null): Result = withContext(Dispatchers.IO) {
        collect(ProcessBuilder(*command).start(), timeoutSeconds)
    }

    suspend fun runUbuntu(workingDir: String? = null, vararg command: String, timeoutSeconds: Long? = null): Result =
        withContext(Dispatchers.IO) { collect(ubuntuProcess(workingDir = workingDir, command = command.toList()), timeoutSeconds) }

    private fun collect(process: Process, timeoutSeconds: Long?): Result {
        val output = StringBuilder()
        val error = StringBuilder()

        val outputThread = Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
        }
        val errorThread = Thread {
            runCatching { process.errorStream.bufferedReader().forEachLine { error.appendLine(it) } }
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

        if (timedOut) {
            process.destroyForcibly()
        }

        outputThread.join(TimeUnit.SECONDS.toMillis(10))
        errorThread.join(TimeUnit.SECONDS.toMillis(10))

        return Result(
            exitCode = if (timedOut) -1 else process.exitValue(),
            output = output.toString().trim(),
            error = error.toString().trim(),
            timedOut = timedOut,
        )
    }
}
