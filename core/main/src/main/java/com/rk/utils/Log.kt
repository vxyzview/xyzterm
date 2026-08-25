package com.rk.utils

import android.util.Log
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.debugOptions.LogCollector

fun Any.logError(throwable: Throwable, msg: String = strings.unknown_error.getString()) {
    Log.e(this::class.java.simpleName, msg, throwable)
    LogCollector.reportError("$msg: \n${throwable.stackTraceToString()}")
}
