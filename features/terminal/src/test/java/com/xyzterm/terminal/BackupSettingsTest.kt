package com.xyzterm.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSettingsTest {
    @Test
    fun `schema maps declared Kotlin classes to tags`() {
        val schema =
            BackupSettings.schemaFromClasses(
                mapOf("flag" to Boolean::class, "count" to Int::class, "unknown" to StringBuilder::class),
            )
        assertEquals("Boolean", schema["flag"])
        assertEquals("Int", schema["count"])
        assertEquals("", schema["unknown"])
    }

    @Test
    fun `snapshot records each preference type`() {
        val json =
            BackupSettings.snapshot(
                mapOf(
                    "a_bool" to true,
                    "an_int" to 13,
                    "a_long" to 1L,
                    "a_string" to "hello",
                    "a_float" to 1.5f,
                ),
            )
        assertTrue(json.contains("\"type\":\"Boolean\""))
        assertTrue(json.contains("\"type\":\"Int\""))
        assertTrue(json.contains("\"type\":\"Long\""))
        assertTrue(json.contains("\"type\":\"String\""))
        assertTrue(json.contains("\"type\":\"Float\""))
    }

    @Test
    fun `settings snapshot round trip keeps boolean int long string and float types`() {
        val json =
            """
            {
              "a_bool": {"type":"Boolean","value":true},
              "an_int": {"type":"Int","value":13},
              "a_long": {"type":"Long","value":99},
              "a_string": {"type":"String","value":"hello"},
              "a_float": {"type":"Float","value":1.5}
            }
            """
                .trimIndent()
        val schema =
            mapOf(
                "a_bool" to "Boolean",
                "an_int" to "Int",
                "a_long" to "Long",
                "a_string" to "String",
                "a_float" to "Float",
            )
        val restored = mutableMapOf<String, Any>()
        BackupSettings.restore(json, schema) { key, value -> restored[key] = value }

        assertEquals(true, restored["a_bool"])
        assertEquals(13, restored["an_int"])
        assertEquals(99L, restored["a_long"])
        assertEquals("hello", restored["a_string"])
        assertEquals(1.5f, restored["a_float"])
    }

    @Test
    fun `declared preference type wins over a mismatched archive tag`() {
        val json = """{"an_int":{"type":"String","value":"13"}}"""
        val restored = mutableMapOf<String, Any>()
        BackupSettings.restore(json, mapOf("an_int" to "Int")) { key, value -> restored[key] = value }
        assertEquals(13, restored["an_int"])
    }

    @Test
    fun `device bound passphrase is excluded from the snapshot`() {
        val source =
            BackupSettings.withoutDeviceBoundKeys(
                mapOf("theme" to "omcolor", "auto_backup_passphrase_cipher" to "sealed-with-old-device-key"),
            )
        assertTrue(source.containsKey("theme"))
        assertFalse(source.containsKey("auto_backup_passphrase_cipher"))
    }
}
