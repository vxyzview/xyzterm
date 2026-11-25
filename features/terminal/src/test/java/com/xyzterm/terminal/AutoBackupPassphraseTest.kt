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

    @Test
    fun empty_packed_value_is_not_set() {
        assertEquals(PassphraseState.NotSet, passphraseState("") { error("must not unseal") })
    }

    @Test
    fun unsealed_value_is_ready() {
        assertEquals(PassphraseState.Ready("phrase"), passphraseState("packed") { "phrase" })
    }

    @Test
    fun malformed_packed_value_is_error() {
        val key = SecretKeySpec(ByteArray(32), "AES")
        assertEquals(PassphraseState.Error, passphraseState("malformed") { AutoBackupPassphrase.open(key, it) })
    }
}
