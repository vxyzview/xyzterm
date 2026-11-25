package com.xyzterm.terminal

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.xyzterm.settings.Preference
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Optional passphrase for encrypted auto-backups, sealed with a device-only AES key. */
internal object AutoBackupPassphrase {
    private const val KEY = "auto_backup_passphrase_cipher"
    private const val ALIAS = "xyzterm_auto_backup_passphrase"

    fun get(): String? =
        runCatching {
            val packed = Preference.getString(KEY, "")
            if (packed.isEmpty()) null else open(key(), packed)
        }.getOrNull()

    fun save(phrase: String) {
        Preference.setString(KEY, seal(key(), phrase))
    }

    fun clear() = Preference.removeKey(KEY)

    /** Pure helpers so the packed format can be checked without the Android keystore. */
    internal fun seal(key: SecretKey, phrase: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv = Base64.getEncoder().encodeToString(cipher.iv)
        val data = Base64.getEncoder().encodeToString(cipher.doFinal(phrase.toByteArray(Charsets.UTF_8)))
        return "$iv:$data"
    }

    internal fun open(key: SecretKey, packed: String): String {
        val (iv, data) = packed.split(":", limit = 2)
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.getDecoder().decode(iv)))
            }
        return String(cipher.doFinal(Base64.getDecoder().decode(data)), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val spec =
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }
            .generateKey()
    }
}
