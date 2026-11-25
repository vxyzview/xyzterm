package com.xyzterm.terminal

import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoBackupPassphraseTest {
    @Test
    fun sealed_phrase_round_trips() {
        val key = SecretKeySpec(ByteArray(32), "AES")
        val packed = AutoBackupPassphrase.seal(key, "correct horse battery staple")
        assertEquals(2, packed.split(":").size)
        assertEquals("correct horse battery staple", AutoBackupPassphrase.open(key, packed))
    }
}
