package com.xyzterm.terminal

import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {
    @Test
    fun encrypted_backup_round_trips() {
        val dir = File.createTempFile("backup-crypto-", "").apply { delete(); mkdirs() }
        val plain = File(dir, "plain.tar.gz").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val encrypted = File(dir, "backup.tar.gz.enc")
        val restored = File(dir, "restored.tar.gz")

        BackupCrypto.encrypt(plain, encrypted, "correct horse battery staple")
        assertTrue(BackupCrypto.isEncrypted(encrypted))
        BackupCrypto.decrypt(encrypted, restored, "correct horse battery staple")

        assertArrayEquals(plain.readBytes(), restored.readBytes())
        dir.deleteRecursively()
    }

    @Test
    fun legacy_120k_backup_is_still_readable() {
        val dir = File.createTempFile("backup-legacy-", "").apply { delete(); mkdirs() }
        val salt = ByteArray(16) { it.toByte() }
        val iv = ByteArray(12) { it.toByte() }
        val payload = byteArrayOf(9, 8, 7, 6)
        val spec = javax.crypto.spec.PBEKeySpec("legacy".toCharArray(), salt, 120_000, 256)
        val raw = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(raw, "AES"), GCMParameterSpec(128, iv))
            }
        val legacy = File(dir, "legacy.tar.gz.enc")
        legacy.outputStream().use { out ->
            out.write("XYZTERM_ENC1".toByteArray())
            out.write(salt)
            out.write(iv)
            out.write(cipher.doFinal(payload))
        }
        val restored = File(dir, "restored")
        BackupCrypto.decrypt(legacy, restored, "legacy")
        assertArrayEquals(payload, restored.readBytes())
        dir.deleteRecursively()
    }
}
