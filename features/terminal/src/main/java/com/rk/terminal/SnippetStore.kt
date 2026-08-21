package com.rk.terminal

import org.json.JSONArray
import org.json.JSONObject

/** A one-tap command shown as a chip above the keyboard. */
data class Snippet(val label: String, val command: String)

object SnippetStore {
    fun decode(json: String): List<Snippet> =
        runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val label = obj.optString("label").trim()
                val command = obj.optString("command")
                if (label.isEmpty() || command.isEmpty()) null else Snippet(label, command)
            }
        }.getOrDefault(emptyList())

    fun encode(snippets: List<Snippet>): String {
        val array = JSONArray()
        snippets.forEach { snippet ->
            array.put(JSONObject().put("label", snippet.label).put("command", snippet.command))
        }
        return array.toString()
    }
}
