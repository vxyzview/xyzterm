package com.xyzterm.terminal

/** Session ids become path segments and deep-link targets; reject names that could escape either. */
internal fun isValidSessionName(name: String): Boolean =
    name.isNotEmpty() &&
        name != "." &&
        name != ".." &&
        !name.contains("/") &&
        !name.contains("\\") &&
        name.none { it.isISOControl() }
