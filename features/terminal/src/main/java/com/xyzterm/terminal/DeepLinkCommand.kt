package com.xyzterm.terminal

internal const val MAX_DEEP_LINK_COMMAND_LENGTH = com.xyzterm.logic.MAX_DEEP_LINK_COMMAND_LENGTH

internal fun isValidDeepLinkCommand(command: String): Boolean = rustanything.ValidationNative.call_isValidDeepLinkCommand(command)
