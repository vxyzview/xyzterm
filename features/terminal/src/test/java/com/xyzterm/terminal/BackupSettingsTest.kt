package com.xyzterm.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSettingsTest {
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
        val restored = mutableMapOf<String, Any>()
        BackupSettings.restore(json) { key, value -> restored[key] = value }

        assertEquals(true, restored["a_bool"])
        assertEquals(13, restored["an_int"])
        assertEquals(99L, restored["a_long"])
        assertEquals("hello", restored["a_string"])
        assertEquals(1.5f, restored["a_float"])
    }
}
