package com.rk.crashhandler

import android.content.Intent
import android.os.Looper
import android.util.Log
import com.rk.file.child
import com.rk.file.createFileIfNot
import com.rk.settings.debugOptions.HarmlessException
import com.rk.utils.application
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

object CrashHandler : Thread.UncaughtExceptionHandler {
    // ponytail: Android's SavedState classes contain a literal '$' (e.g. View$BaseSavedState).
    // Build those names via a char literal so no string-template escaping is needed.
    private val VIEW_SAVED_STATE = "android.view.View" + '$' + "BaseSavedState"
    private val SCROLL_SAVED_STATE = "android.widget.HorizontalScrollView" + '$' + "SavedState"

    /**
     * Whether a crash is the framework's own "I cannot restore my saved state" complaint, which
     * is noise rather than a real defect.
     *
     * This used to substring-match `ex.stackTrace.contentToString()`, so *any* uncaught exception
     * whose frames merely passed through view-state save/restore was dropped: no crash screen, no
     * crash.log, and the app continuing on un-restored state. Matching on the type and the
     * message keeps the one case that is genuinely noise and reports everything else.
     */
    internal fun shouldIgnore(ex: Throwable): Boolean {
        if (ex !is IllegalStateException) return false
        val message = ex.message ?: return false
        return message.contains(VIEW_SAVED_STATE) || message.contains(SCROLL_SAVED_STATE)
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        if (shouldIgnore(ex)) {
            // Always log, even when we go on to ignore it — a swallowed crash that leaves
            // no trace anywhere is indistinguishable from one that never happened.
            Log.w("CrashHandler", "Ignoring saved-state restore crash", ex)
            return
        }

        runCatching {
            val app =
                application
                    ?: run {
                        // Crashed before Application.onCreate assigned it. There is no context to
                        // launch CrashActivity with, so write the report directly rather than
                        // dying through the onFailure below with nothing on disk.
                        writeReport(ex)
                        return@runCatching
                    }

            val intent = Intent(app, CrashActivity::class.java)
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

            app.startActivity(intent)
        }
            .onFailure {
                it.printStackTrace()
                writeReport(ex)
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

    /** Best-effort on-disk crash report, for the cases where we cannot show [CrashActivity]. */
    private fun writeReport(ex: Throwable) {
        runCatching {
            application?.filesDir?.child("crash.log")?.createFileIfNot()?.appendText(ex.stackTraceToString())
        }
    }

    fun logErrorOrExit(throwable: Throwable) {
        runCatching {
                application!!.filesDir.child("crash.log").createFileIfNot().appendText(throwable.toString())
            }
            .onFailure {
                it.printStackTrace()
                exitProcess(-1)
            }
    }
}
