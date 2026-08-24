package com.rk.crashhandler

import android.content.Intent
import android.os.Looper
import android.os.Process
import android.util.Log
import com.rk.file.child
import com.rk.file.createFileIfNot
import com.rk.settings.debugOptions.HarmlessException
import com.rk.utils.application
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

object CrashHandler : Thread.UncaughtExceptionHandler {
    private val handlingCrash = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        if (!handlingCrash.compareAndSet(false, true)) {
            Process.killProcess(Process.myPid())
            exitProcess(1)
        }

        runCatching {
                if (
                    ex.message.toString().contains("android.view.View${"$"}BaseSavedState") ||
                        ex.message.toString().contains("android.widget.HorizontalScrollView${"$"}SavedState")
                ) {
                    Log.w("CrashHandler", "Ignoring crash")
                    handlingCrash.set(false)
                    return@runCatching
                }

                if (
                    ex.stackTrace.contentToString().contains($$"android.view.View$BaseSavedState") ||
                        ex.stackTrace.contentToString().contains($$"android.widget.HorizontalScrollView$SavedState")
                ) {
                    Log.w("CrashHandler", "Ignoring crash")
                    ex.printStackTrace()
                    handlingCrash.set(false)
                    return@runCatching
                }

                val intent = Intent(application!!, CrashActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

                // Top-level exceptions often carry a null cause; toString() on it
                // would throw inside the handler and fall through to a silent
                // exitProcess(1) with no crash report.
                var cause = ex.cause?.toString() ?: ex.toString()
                val prefix = "java.lang.Throwable:"
                if (cause.startsWith(prefix)) {
                    cause = cause.removePrefix(prefix)
                }

                intent.putExtra("force_crash", ex is HarmlessException)
                intent.putExtra("error_cause", cause)
                intent.putExtra("msg", ex.message)

                val stringWriter = StringWriter()
                val printWriter = PrintWriter(stringWriter)
                ex.printStackTrace(printWriter)
                val stackTraceString = stringWriter.toString()

                intent.putExtra("stacktrace", stackTraceString)
                intent.putExtra("thread", thread.name)

                application!!.startActivity(intent)
            }
            .onFailure {
                it.printStackTrace()
                exitProcess(1)
            }

        // Try to keep main thread alive
        if (Looper.myLooper() != null) {
            while (true) {
                try {
                    Looper.loop()
                    return
                } catch (t: Throwable) {
                    Thread {
                            t.printStackTrace()
                            logErrorOrExit(t)
                        }
                        .start()
                }
            }
        }
    }

    fun logErrorOrExit(throwable: Throwable) {
        runCatching {
                val logFile = application!!.filesDir.child("crash.log").createFileIfNot()
                if (logFile.length() < MAX_LOG_BYTES) {
                    logFile.appendText(throwable.toString())
                }
            }
            .onFailure {
                it.printStackTrace()
                exitProcess(-1)
            }
    }

    private const val MAX_LOG_BYTES = 512 * 1024
}
