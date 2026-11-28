package com.xyzterm.terminal

internal fun isValidSessionName(name: String): Boolean = rustanything.ValidationNative.call_isValidSessionName(name)
