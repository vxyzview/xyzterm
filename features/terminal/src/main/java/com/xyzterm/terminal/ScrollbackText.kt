package com.xyzterm.terminal

/** End column and row are inclusive in terminal-view v0.118.3. */
internal fun fullScrollbackBounds(
    transcriptRows: Int,
    columns: Int,
    screenRows: Int,
): IntArray = intArrayOf(0, -transcriptRows, columns - 1, screenRows - 1)
