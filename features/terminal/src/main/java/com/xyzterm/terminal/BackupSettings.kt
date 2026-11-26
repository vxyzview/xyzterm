package com.xyzterm.terminal

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Settings travel with a backup as a typed JSON map. The type tag is stored next to each value because Gson erases the
 * difference between Int, Long and Float once the archive is read back.
 *
 * This object is deliberately free of Android references so it can be unit-tested on the JVM; the caller supplies the
 * preference map on snapshot and a `put` callback on restore.
 */
internal object BackupSettings {
    // Sealed with the device's AndroidKeyStore key; useless and harmful on another device.
    private val DEVICE_BOUND_KEYS = setOf("auto_backup_passphrase_cipher")

    fun snapshotSource(settings: Map<String, Any?>): Map<String, Any?> = settings.filterKeys {
        it !in DEVICE_BOUND_KEYS
    }

    fun snapshot(settings: Map<String, Any?>): String {
        val root = JsonObject()
        settings.forEach { (key, value) ->
            val entry = JsonObject()
            when (value) {
                is String -> {
                    entry.addProperty("type", "String")
                    entry.addProperty("value", value)
                }
                is Boolean -> {
                    entry.addProperty("type", "Boolean")
                    entry.addProperty("value", value)
                }
                is Int -> {
                    entry.addProperty("type", "Int")
                    entry.addProperty("value", value)
                }
                is Long -> {
                    entry.addProperty("type", "Long")
                    entry.addProperty("value", value)
                }
                is Float -> {
                    entry.addProperty("type", "Float")
                    entry.addProperty("value", value)
                }
                else -> return@forEach
            }
            root.add(key, entry)
        }
        return root.toString()
    }

    /**
     * [schema] maps a key to the type the *installed app* declares. The archive's tag is only a hint for Gson — the
     * declared type always wins, so a crafted or stale tag cannot put a wrong type into SharedPreferences.
     */
    fun restore(
        json: String,
        schema: Map<String, String>,
        put: (String, Any) -> Unit,
    ) {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return
        root.entrySet().forEach { (key, element) ->
            val type = schema[key] ?: return@forEach
            val value = element.takeIf { it.isJsonObject }?.asJsonObject?.get("value") ?: return@forEach
            runCatching {
                when (type) {
                    "String" -> put(key, value.asString)
                    "Boolean" -> put(key, value.asBoolean)
                    "Int" -> put(key, value.asInt)
                    "Long" -> put(key, value.asLong)
                    "Float" -> put(key, value.asFloat)
                    else -> Unit
                }
            }
        }
    }
}
