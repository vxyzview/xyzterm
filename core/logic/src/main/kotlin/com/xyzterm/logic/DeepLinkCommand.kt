package com.xyzterm.logic

const val MAX_DEEP_LINK_COMMAND_LENGTH = 4096

/** Control characters are rejected so an embedded newline cannot inject a second command. */
fun isValidDeepLinkCommand(command: String): Boolean =
    command.isNotBlank() && command.length <= MAX_DEEP_LINK_COMMAND_LENGTH && command.none { it.isISOControl() }
