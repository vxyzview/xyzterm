package com.xyzterm.terminal.virtualkeys

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Defines the info needed by [VirtualKeysView] to display the extra key views.
 *
 * The `propertiesInfo` passed to the constructors must be a JSON array of arrays. Each element is
 * a row of keys; each key is either a string naming the key or a JSON object with `key`, `macro`,
 * `display` and/or `popup` entries. See https://wiki.termux.com/wiki/Touch_Keyboard.
 */
class VirtualKeysInfo private constructor(private val mButtons: Array<Array<VirtualKeyButton>>) {

    @Throws(JSONException::class)
    constructor(
        propertiesInfo: String,
        style: String,
        extraKeyAliasMap: VirtualKeysConstants.VirtualKeyDisplayMap,
    ) : this(initVirtualKeysInfo(propertiesInfo, getCharDisplayMapForStyle(style), extraKeyAliasMap))

    @Throws(JSONException::class)
    constructor(
        propertiesInfo: String,
        extraKeyDisplayMap: VirtualKeysConstants.VirtualKeyDisplayMap,
        extraKeyAliasMap: VirtualKeysConstants.VirtualKeyDisplayMap,
    ) : this(initVirtualKeysInfo(propertiesInfo, extraKeyDisplayMap, extraKeyAliasMap))

    /** Matrix of buttons to be displayed in [VirtualKeysView]. */
    fun getMatrix(): Array<Array<VirtualKeyButton>> = mButtons

    companion object {
        private fun initVirtualKeysInfo(
            propertiesInfo: String,
            extraKeyDisplayMap: VirtualKeysConstants.VirtualKeyDisplayMap,
            extraKeyAliasMap: VirtualKeysConstants.VirtualKeyDisplayMap,
        ): Array<Array<VirtualKeyButton>> {
            // Convert String propertiesInfo to an array of arrays.
            val arr = JSONArray(propertiesInfo)
            val matrix = Array(arr.length()) { i ->
                val line = arr.getJSONArray(i)
                Array(line.length()) { j -> line.get(j) }
            }

            // Convert matrix to buttons.
            return Array(matrix.size) { i ->
                Array(matrix[i].size) { j ->
                    val jobject = normalizeKeyConfig(matrix[i][j])
                    if (!jobject.has(VirtualKeyButton.KEY_POPUP)) {
                        VirtualKeyButton(jobject, extraKeyDisplayMap, extraKeyAliasMap)
                    } else {
                        val popupJobject = normalizeKeyConfig(jobject.get(VirtualKeyButton.KEY_POPUP))
                        val popup = VirtualKeyButton(popupJobject, extraKeyDisplayMap, extraKeyAliasMap)
                        VirtualKeyButton(jobject, popup, extraKeyDisplayMap, extraKeyAliasMap)
                    }
                }
            }
        }

        /** Convert "value" to {"key": "value"}. */
        @Throws(JSONException::class)
        private fun normalizeKeyConfig(key: Any?): JSONObject =
            when (key) {
                is String -> JSONObject().put(VirtualKeyButton.KEY_KEY_NAME, key)
                is JSONObject -> key
                else -> throw JSONException("An key in the extra-key matrix must be a string or an object")
            }

        @JvmStatic
        fun getCharDisplayMapForStyle(style: String): VirtualKeysConstants.VirtualKeyDisplayMap =
            when (style) {
                "arrows-only" -> VirtualKeysConstants.EXTRA_KEY_DISPLAY_MAPS.ARROWS_ONLY_CHAR_DISPLAY
                "arrows-all" -> VirtualKeysConstants.EXTRA_KEY_DISPLAY_MAPS.LOTS_OF_ARROWS_CHAR_DISPLAY
                "all" -> VirtualKeysConstants.EXTRA_KEY_DISPLAY_MAPS.FULL_ISO_CHAR_DISPLAY
                "none" -> VirtualKeysConstants.VirtualKeyDisplayMap()
                else -> VirtualKeysConstants.EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY
            }
    }
}
