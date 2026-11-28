package com.xyzterm.terminal.virtualkeys

import androidx.annotation.NonNull

/**
 * The class that implements special buttons for [VirtualKeysView].
 *
 * @param key The special button key.
 */
data class SpecialButton(val key: String) {
    @NonNull
    override fun toString(): String = key

    /** Get [key] for this [SpecialButton]. */
    fun key(): String = key

    init {
        map[key] = this
    }

    companion object {
        private val map = HashMap<String, SpecialButton>()
        @JvmField val CTRL = SpecialButton("CTRL")
        @JvmField val ALT = SpecialButton("ALT")
        @JvmField val SHIFT = SpecialButton("SHIFT")
        @JvmField val FN = SpecialButton("FN")

        /** Get the [SpecialButton] for [key]. */
        @JvmStatic
        fun valueOf(key: String): SpecialButton? = map[key]
    }
}
