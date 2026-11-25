package com.xyzterm.terminal

import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM archive encryption for exported backups. Format: magic + salt + iv + ciphertext. */
internal object BackupCrypto {
    private const val MAGIC = "XYZTERM_ENC1"
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    fun isEncrypted(file: File): Boolean =
        runCatching {
            file.inputStream().use { input -> readExact(input, MAGIC.length)?.contentEquals(MAGIC.toByteArray()) == true }
        }
            .getOrDefault(false)

    fun encrypt(plain: File, target: File, passphrase: String) {
        require(passphrase.isNotEmpty()) { "passphrase required" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
            }
        target.outputStream().use { out ->
            out.write(MAGIC.toByteArray())
            out.write(salt)
            out.write(iv)
            CipherOutputStream(out, cipher).use { encrypted -> plain.inputStream().use { it.copyTo(encrypted) } }
        }
    }

    fun decrypt(encrypted: File, target: File, passphrase: String) {
        encrypted.inputStream().use { input ->
            val magic = readExact(input, MAGIC.length)
            check(magic != null && magic.contentEquals(MAGIC.toByteArray())) { "not an encrypted backup" }
            val salt = readExact(input, SALT_BYTES) ?: error("truncated backup")
            val iv = readExact(input, IV_BYTES) ?: error("truncated backup")
            val cipher =
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
                }
            CipherInputStream(input, cipher).use { decrypted -> target.outputStream().use { decrypted.copyTo(it) } }
        }
    }

    private fun key(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    /** InputStream.readNBytes is API 33+; this stays compatible with minSdk 26. */
    private fun readExact(input: InputStream, count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) return null
            offset += read
        }
        return buffer
    }
}
