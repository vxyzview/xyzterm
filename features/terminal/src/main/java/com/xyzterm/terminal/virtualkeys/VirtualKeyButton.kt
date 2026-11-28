package com.xyzterm.terminal.virtualkeys

import org.json.JSONException
import org.json.JSONObject

class VirtualKeyButton @Throws(JSONException::class) constructor(
    config: JSONObject,
    popup: VirtualKeyButton?,
    extraKeyDisplayMap: VirtualKeysConstants.VirtualKeyDisplayMap,
    extraKeyAliasMap: VirtualKeysConstants.VirtualKeyDisplayMap,
) {
    /** The key sent to the terminal: a control character, macro, or literal text. */
    val key: String

    /** If the key is a macro, i.e. a sequence of keys separated by space. */
    val isMacro: Boolean

    /** The text displayed on the button. */
    val display: String

    /** The [VirtualKeyButton] for the popup button (triggered by swipe up). */
    val popup: VirtualKeyButton? = popup

    @Throws(JSONException::class)
    constructor(
        config: JSONObject,
        extraKeyDisplayMap: VirtualKeysConstants.VirtualKeyDisplayMap,
        extraKeyAliasMap: VirtualKeysConstants.VirtualKeyDisplayMap,
    ) : this(config, null, extraKeyDisplayMap, extraKeyAliasMap)

    init {
        val keyFromConfig = getStringFromJson(config, KEY_KEY_NAME)
        val macroFromConfig = getStringFromJson(config, KEY_MACRO)
        val keys: Array<String>
        if (keyFromConfig != null && macroFromConfig != null) {
            throw JSONException(
                "Both key and macro can't be set for the same key. key: \"$keyFromConfig\", macro: \"$macroFromConfig\"",
            )
        } else if (keyFromConfig != null) {
            keys = arrayOf(keyFromConfig)
            isMacro = false
        } else if (macroFromConfig != null) {
            // Java String.split drops trailing empty strings; mirror that here.
            keys = macroFromConfig.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            isMacro = true
        } else {
            throw JSONException("All keys have to specify either key or macro")
        }

        for (i in keys.indices) {
            keys[i] = replaceAlias(extraKeyAliasMap, keys[i])
        }

        key = keys.joinToString(" ")
        display =
            getStringFromJson(config, KEY_DISPLAY_NAME)
                ?: keys.joinToString(" ") { extraKeyDisplayMap.get(it, it) }
    }

    fun getStringFromJson(config: JSONObject, key: String): String? =
        try {
            config.getString(key)
        } catch (e: JSONException) {
            null
        }

    /** Replace the alias with its actual key name if found in [extraKeyAliasMap]. */
    companion object {
        /** Name of the key defining the button's key. */
        const val KEY_KEY_NAME = "key"

        /** Name of the key defining the button's macro. */
        const val KEY_MACRO = "macro"

        /** Name of the key defining the button's display name. */
        const val KEY_DISPLAY_NAME = "display"

        /** Name of the key defining the button's popup. */
        const val KEY_POPUP = "popup"

        @JvmStatic
        fun replaceAlias(
            extraKeyAliasMap: VirtualKeysConstants.VirtualKeyDisplayMap,
            key: String,
        ): String = extraKeyAliasMap.get(key, key)
    }
}
