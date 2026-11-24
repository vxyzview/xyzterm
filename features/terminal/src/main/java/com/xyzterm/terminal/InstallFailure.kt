package com.xyzterm.terminal

/** Actionable categories for a failed first-launch install. */
internal enum class InstallFailure {
    NoSpace,
    MissingAsset,
    Other,
}

/** Map an installer error to the fix the user can actually perform. */
internal fun installFailureFor(message: String?): InstallFailure {
    val text = message.orEmpty()
    return when {
        text.contains("No space left", ignoreCase = true) || text.contains("ENOSPC", ignoreCase = true) ->
            InstallFailure.NoSpace

        text.contains("missing from this APK", ignoreCase = true) -> InstallFailure.MissingAsset
        else -> InstallFailure.Other
    }
}
