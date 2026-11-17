package com.xyzterm.crashhandler

import android.content.Intent
import android.os.Looper
import android.util.Log
import com.xyzterm.file.child
import com.xyzterm.file.createFileIfNot
import com.xyzterm.settings.debugOptions.HarmlessException
import com.xyzterm.utils.application
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

object CrashHandler : Thread.UncaughtExceptionHandler {
    // ponytail: Android's SavedState classes contain a literal '$' (e.g. View$BaseSavedState).
    // Build those names via a char literal so no string-template escaping is needed.
    private val VIEW_SAVED_STATE = "android.view.View" + '$' + "BaseSavedState"
    private val SCROLL_SAVED_STATE = "android.widget.HorizontalScrollView" + '$' + "SavedState"

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        runCatching {
            if (
                ex.message.toString().contains(VIEW_SAVED_STATE) || ex.message.toString().contains(SCROLL_SAVED_STATE)
            ) {
                Log.w("CrashHandler", "Ignoring crash")
                return@runCatching
            }

            if (
                ex.stackTrace.contentToString().contains(VIEW_SAVED_STATE) ||
                    ex.stackTrace.contentToString().contains(SCROLL_SAVED_STATE)
            ) {
                Log.w("CrashHandler", "Ignoring crash")
                ex.printStackTrace()
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

        // Do not re-enter Looper.loop() in a retry loop: that keeps a corrupted
        // process alive and can spin a thread per repeated failure. Show the crash
        // screen and let the process die; the user can relaunch from there.
        if (Looper.myLooper() != null) {
            try {
                Looper.loop()
            } catch (t: Throwable) {
                Thread {
                    t.printStackTrace()
                    logErrorOrExit(t)
                }
                    .start()
            }
        }
    }

    fun logErrorOrExit(throwable: Throwable) {
        runCatching { application!!.filesDir.child("crash.log").createFileIfNot().appendText(throwable.toString()) }
            .onFailure {
                it.printStackTrace()
                exitProcess(-1)
            }
    }
}
