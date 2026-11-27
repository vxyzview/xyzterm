package com.xyzterm.terminal

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
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
    // V2 records the iteration count so archives made with the old 120k setting stay readable.
    private const val MAGIC_V2 = "XYZTERM_ENC2"
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val ITERATIONS = 120_000
    private const val ITERATIONS_V2 = 600_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    fun isEncrypted(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            readExact(input, MAGIC.length)?.let {
                it.contentEquals(MAGIC.toByteArray()) || it.contentEquals(MAGIC_V2.toByteArray())
            } == true
        }
    }
        .getOrDefault(false)

    fun encrypt(plain: File, target: File, passphrase: String) {
        require(passphrase.isNotEmpty()) { "passphrase required" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key(passphrase, salt, ITERATIONS_V2), GCMParameterSpec(TAG_BITS, iv))
            }
        target.outputStream().use { out ->
            out.write(MAGIC_V2.toByteArray())
            out.write(ByteBuffer.allocate(4).putInt(ITERATIONS_V2).array())
            out.write(salt)
            out.write(iv)
            CipherOutputStream(out, cipher).use { encrypted -> plain.inputStream().use { it.copyTo(encrypted) } }
        }
    }

    fun decrypt(encrypted: File, target: File, passphrase: String) {
        encrypted.inputStream().use { input ->
            val magic = readExact(input, MAGIC.length) ?: error("not an encrypted backup")
            val iterations =
                when {
                    magic.contentEquals(MAGIC_V2.toByteArray()) ->
                        ByteBuffer.wrap(readExact(input, 4) ?: error("truncated backup")).int
                    magic.contentEquals(MAGIC.toByteArray()) -> ITERATIONS
                    else -> error("not an encrypted backup")
                }
            check(iterations == ITERATIONS || iterations == ITERATIONS_V2) { "unsupported backup iterations" }
            val salt = readExact(input, SALT_BYTES) ?: error("truncated backup")
            val iv = readExact(input, IV_BYTES) ?: error("truncated backup")
            val cipher =
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
                }
            CipherInputStream(input, cipher).use { decrypted -> target.outputStream().use { decrypted.copyTo(it) } }
        }
    }

    private fun key(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
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
