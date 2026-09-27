package com.rk.terminal.virtualkeys

/**
 * Centralized ANSI escape sequences for virtual keys.
 * Single source of truth to avoid divergence between [VirtualKeyClient] and [VirtualKeysListener].
 */
object TerminalEscapes {
    const val ESC = "\u001B"
    const val TAB = "\u0009"
    const val ENTER = "\u000D"
    const val HOME = "\u001B[H"
    const val UP = "\u001B[A"
    const val DOWN = "\u001B[B"
    const val LEFT = "\u001B[D"
    const val RIGHT = "\u001B[C"
    const val PGUP = "\u001B[5~"
    const val PGDN = "\u001B[6~"
    // xterm-style END (common in Linux terminals); VT100 uses \u001B[F
    const val END = "\u001B[4~"
}