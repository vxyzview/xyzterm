package com.rk.file

/**
 * Rate-limits progress reporting for a long tree walk.
 *
 * `calculateContent` fires `onProgress` for every entry, and the Properties dialog assigns two
 * `mutableStateOf` values per call. A 5,000-file directory therefore caused 10,000 snapshot
 * writes and 10,000 recompositions of the dialog, for a number that only changes meaningfully a
 * handful of times while someone watches it count up.
 *
 * The walk itself is untouched — only the reporting is throttled, and [flush] guarantees the
 * final total is always published, so what the user sees is never stale.
 */
internal const val PROGRESS_EVERY_N_ITEMS = 25L

class ProgressThrottle(
    private val everyN: Long = PROGRESS_EVERY_N_ITEMS,
    private val onPublish: () -> Unit,
) {
    private var seen = 0L
    private var published = false

    /** Call with the running item count. Reports when a threshold is crossed. */
    fun onItem(count: Long) {
        seen = count
        if (seen % everyN == 0L) {
            onPublish()
            published = true
        }
    }

    /** Publish the final count if it was never published. Idempotent. */
    fun flush() {
        if (!published) {
            onPublish()
            published = true
        }
    }
}