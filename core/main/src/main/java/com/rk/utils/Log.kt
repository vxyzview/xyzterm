package com.rk.utils

import android.util.Log
import com.rk.BuildConfig
import com.rk.settings.Settings
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.debugOptions.LogCollector

fun Any.logDebug(msg: String) {
    Log.d(this::class.java.simpleName, msg)
    LogCollector.reportDebug(msg)
}

fun Any.logInfo(msg: String) {
    Log.i(this::class.java.simpleName, msg)
    LogCollector.reportInfo(msg)
}

fun Any.logWarn(msg: String) {
    Log.w(this::class.java.simpleName, msg)
    LogCollector.reportWarn(msg)
}

fun Any.logError(msg: String) {
    Log.e(this::class.java.simpleName, msg)
    LogCollector.reportError(msg)
}

fun Any.logError(throwable: Throwable, msg: String = strings.unknown_error.getString()) {
    // Log.e(..., throwable) already wrote the stack. Rendering it again into a string was
    // done unconditionally, with no DEBUG or verbose gate, purely so the in-app log view could
    // show it -- and up to 1000 such multi-KB strings are retained for the life of the
    // process. In release, the class and message are enough to find the crash in the crash
    // report, which is written separately.
    Log.e(this::class.java.simpleName, msg, throwable)
    val detail =
        if (BuildConfig.DEBUG || Settings.verbose_error) {
            "$msg: \n${throwable.stackTraceToString()}"
        } else {
            msg
        }
    LogCollector.reportError(detail)
}
