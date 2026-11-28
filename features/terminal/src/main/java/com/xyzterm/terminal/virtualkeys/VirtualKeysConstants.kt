package com.xyzterm.terminal.virtualkeys

import android.view.KeyEvent

class VirtualKeysConstants {
    /** HashMap that implements Python's dict.get(key, default). */
    open class CleverMap<K, V> : HashMap<K, V>() {
        fun get(key: K, defaultValue: V): V = if (containsKey(key)) get(key)!! else defaultValue
    }

    /** Map of keys to their display strings. */
    open class VirtualKeyDisplayMap : CleverMap<String, String>()

    /* Multiple maps are available to quickly change the style of the keys. */
    object EXTRA_KEY_DISPLAY_MAPS {
        /** Keys are displayed in a natural looking way, like "→" for "RIGHT". */
        @JvmField val CLASSIC_ARROWS_DISPLAY =
            object : VirtualKeyDisplayMap() {
                init {
                    put("LEFT", "←") // U+2190 LEFTWARDS ARROW
                    put("RIGHT", "→") // U+2192 RIGHTWARDS ARROW
                    put("UP", "↑") // U+2191 UPWARDS ARROW
                    put("DOWN", "↓") // U+2193 DOWNWARDS ARROW
                }
            }

        @JvmField val WELL_KNOWN_CHARACTERS_DISPLAY =
            object : VirtualKeyDisplayMap() {
                init {
                    put("ENTER", "↲") // U+21B2 DOWNWARDS ARROW WITH TIP LEFTWARDS
                    put("TAB", "↹") // U+21B9 LEFTWARDS ARROW TO BAR OVER RIGHTWARDS ARROW TO BAR
                    put("BKSP", "⌫") // U+232B ERASE TO THE LEFT
                    put("DEL", "⌦") // U+2326 ERASE TO THE RIGHT
                    put("DRAWER", "☰") // U+2630 TRIGRAM FOR HEAVEN
                    put("KEYBOARD", "⌨") // U+2328 KEYBOARD
                    put("PASTE", "⎘") // U+2398
                }
            }

        @JvmField val LESS_KNOWN_CHARACTERS_DISPLAY =
            object : VirtualKeyDisplayMap() {
                init {
                    put("HOME", "⇱") // IEC 9995, U+21F1 NORTH WEST ARROW TO CORNER
                    put("END", "⇲") // IEC 9995, U+21F2 SOUTH EAST ARROW TO CORNER
                    put("PGUP", "⇑") // U+21D1 UPWARDS DOUBLE ARROW
                    put("PGDN", "⇓") // U+21D3 DOWNWARDS DOUBLE ARROW
                }
            }

        @JvmField val ARROW_TRIANGLE_VARIATION_DISPLAY =
            object : VirtualKeyDisplayMap() {
                init {
                    put("LEFT", "◀") // U+25C0 BLACK LEFT-POINTING TRIANGLE
                    put("RIGHT", "▶") // U+25B6 BLACK RIGHT-POINTING TRIANGLE
                    put("UP", "▲") // U+25B2 BLACK UP-POINTING TRIANGLE
                    put("DOWN", "▼") // U+25BC BLACK DOWN-POINTING TRIANGLE
                }
            }

        @JvmField val NOT_KNOWN_ISO_CHARACTERS =
            object : VirtualKeyDisplayMap() {
                init {
                    put("CTRL", "⎈") // U+2388 HELM SYMBOL
                    put("ALT", "⎇") // U+2387 ALTERNATIVE KEY SYMBOL
                    put("ESC", "⎋") // U+238B BROKEN CIRCLE WITH NORTHWEST ARROW
                }
            }

        @JvmField val NICER_LOOKING_DISPLAY =
            object : VirtualKeyDisplayMap() {
                init {
                    put("-", "―") // U+2015 HORIZONTAL BAR
                }
            }

        /** Full ISO. */
        @JvmField val FULL_ISO_CHAR_DISPLAY =
            object : VirtualKeyDisplayMap() {
                init {
                    putAll(CLASSIC_ARROWS_DISPLAY)
                    putAll(WELL_KNOWN_CHARACTERS_DISPLAY)
                    putAll(LESS_KNOWN_CHARACTERS_DISPLAY)
                    putAll(NICER_LOOKING_DISPLAY)
                    putAll(NOT_KNOWN_ISO_CHARACTERS)
                }
            }

        /** Only arrows. */
        @JvmField val ARROWS_ONLY_CHAR_DISPLAY =
            object : VirtualKeyDisplayMap() {
                init {
                    putAll(CLASSIC_ARROWS_DISPLAY)
                    putAll(NICER_LOOKING_DISPLAY)
                }
            }

        /** Classic symbols and less known symbols. */
        @JvmField val LOTS_OF_ARROWS_CHAR_DISPLAY =
            object : VirtualKeyDisplayMap() {
                init {
                    putAll(CLASSIC_ARROWS_DISPLAY)
                    putAll(WELL_KNOWN_CHARACTERS_DISPLAY)
                    putAll(LESS_KNOWN_CHARACTERS_DISPLAY)
                    putAll(NICER_LOOKING_DISPLAY)
                }
            }

        /** Some classic symbols everybody knows. */
        @JvmField val DEFAULT_CHAR_DISPLAY =
            object : VirtualKeyDisplayMap() {
                init {
                    putAll(CLASSIC_ARROWS_DISPLAY)
                    putAll(WELL_KNOWN_CHARACTERS_DISPLAY)
                    putAll(NICER_LOOKING_DISPLAY)
                    // all other characters are displayed as themselves
                }
            }
    }

    companion object {
        /** Aliases for the keys. */
        @JvmField val CONTROL_CHARS_ALIASES =
            object : VirtualKeyDisplayMap() {
                init {
                    put("ESCAPE", "ESC")
                    put("CONTROL", "CTRL")
                    put("SHFT", "SHIFT")
                    put("RETURN", "ENTER") // Technically different keys, but most apps won't see it
                    put("FUNCTION", "FN")
                    // no alias for ALT

                    // Directions are sometimes written as first and last letter for brevity
                    put("LT", "LEFT")
                    put("RT", "RIGHT")
                    put("DN", "DOWN")
                    // put("UP", "UP"); "UP" is already two letters

                    put("PAGEUP", "PGUP")
                    put("PAGE_UP", "PGUP")
                    put("PAGE UP", "PGUP")
                    put("PAGE-UP", "PGUP")

                    // no alias for HOME or END

                    put("PAGEDOWN", "PGDN")
                    put("PAGE_DOWN", "PGDN")
                    put("PAGE-DOWN", "PGDN")

                    put("DELETE", "DEL")
                    put("BACKSPACE", "BKSP")

                    // easier for writing in termux.properties
                    put("BACKSLASH", "\\")
                    put("QUOTE", "\"")
                    put("APOSTROPHE", "'")
                }
            }

        /** Repetitive keys that can be passed to [VirtualKeysView.setRepetitiveKeys]. */
        @JvmField var PRIMARY_REPETITIVE_KEYS: MutableList<String> =
            mutableListOf("UP", "DOWN", "LEFT", "RIGHT", "BKSP", "DEL")

        /** [KeyEvent] key codes for common keys. */
        @JvmField var PRIMARY_KEY_CODES_FOR_STRINGS: MutableMap<String, Int> =
            HashMap<String, Int>().apply {
                put("SPACE", KeyEvent.KEYCODE_SPACE)
                put("ESC", KeyEvent.KEYCODE_ESCAPE)
                put("TAB", KeyEvent.KEYCODE_TAB)
                put("HOME", KeyEvent.KEYCODE_MOVE_HOME)
                put("END", KeyEvent.KEYCODE_MOVE_END)
                put("PGUP", KeyEvent.KEYCODE_PAGE_UP)
                put("PGDN", KeyEvent.KEYCODE_PAGE_DOWN)
                put("INS", KeyEvent.KEYCODE_INSERT)
                put("DEL", KeyEvent.KEYCODE_FORWARD_DEL)
                put("BKSP", KeyEvent.KEYCODE_DEL)
                put("UP", KeyEvent.KEYCODE_DPAD_UP)
                put("LEFT", KeyEvent.KEYCODE_DPAD_LEFT)
                put("RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT)
                put("DOWN", KeyEvent.KEYCODE_DPAD_DOWN)
                put("ENTER", KeyEvent.KEYCODE_ENTER)
                put("F1", KeyEvent.KEYCODE_F1)
                put("F2", KeyEvent.KEYCODE_F2)
                put("F3", KeyEvent.KEYCODE_F3)
                put("F4", KeyEvent.KEYCODE_F4)
                put("F5", KeyEvent.KEYCODE_F5)
                put("F6", KeyEvent.KEYCODE_F6)
                put("F7", KeyEvent.KEYCODE_F7)
                put("F8", KeyEvent.KEYCODE_F8)
                put("F9", KeyEvent.KEYCODE_F9)
                put("F10", KeyEvent.KEYCODE_F10)
                put("F11", KeyEvent.KEYCODE_F11)
                put("F12", KeyEvent.KEYCODE_F12)
            }
    }
}
