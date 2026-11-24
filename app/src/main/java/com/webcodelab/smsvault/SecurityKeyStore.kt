package com.webcodelab.smsvault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Secure storage and constant-time salted PBKDF2 verification for the WCL Security Key.
 * - Never stores the Security Key in plain text on disk.
 * - Encrypts the API key with an AndroidKeyStore AES-256-GCM hardware-backed key (when available)
 *   so existing SMS / Contacts / Remote sync API calls continue to authenticate cleanly.
 * - Stores a separate salted PBKDF2-HMAC-SHA256 verifier for Uninstall Protection authorization.
 */
object SecurityKeyStore {

    private const val SECURE_PREF_NAME = "vault_security_prefs"
    private const val LEGACY_PREF_NAME = "vault_prefs"
    private const val LEGACY_KEY_API_KEY = "api_key"

    private const val KEY_ENC_CIPHERTEXT = "enc_api_key"
    private const val KEY_ENC_IV = "enc_api_iv"
    private const val KEY_VERIFIER_SALT = "key_verifier_salt"
    private const val KEY_VERIFIER_HASH = "key_verifier_hash"
    private const val KEY_PROTECTION_ENABLED = "uninstall_protection_enabled"

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "WclSecurityKeyAes256Gcm"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val PBKDF2_BITS = 256
    private const val GCM_TAG_BITS = 128

    @Synchronized
    fun saveSecurityKey(context: Context, rawKey: String) {
        val trimmed = rawKey.trim()
        val secPrefs = context.getSharedPreferences(SECURE_PREF_NAME, Context.MODE_PRIVATE)

        // Remove any legacy plaintext value
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREF_NAME, Context.MODE_PRIVATE)
        if (legacyPrefs.contains(LEGACY_KEY_API_KEY)) {
            legacyPrefs.edit().remove(LEGACY_KEY_API_KEY).apply()
        }

        if (trimmed.isEmpty()) {
            secPrefs.edit()
                .remove(KEY_ENC_CIPHERTEXT)
                .remove(KEY_ENC_IV)
                .remove(KEY_VERIFIER_SALT)
                .remove(KEY_VERIFIER_HASH)
                .apply()
            return
        }

        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val verifierHash = derivePbkdf2(trimmed, salt)

        val (ciphertextB64, ivB64) = encryptWithKeystore(trimmed)

        secPrefs.edit()
            .putString(KEY_ENC_CIPHERTEXT, ciphertextB64)
            .putString(KEY_ENC_IV, ivB64)
            .putString(KEY_VERIFIER_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_VERIFIER_HASH, Base64.encodeToString(verifierHash, Base64.NO_WRAP))
            .apply()
    }

    @Synchronized
    fun getApiKey(context: Context): String {
        migrateLegacyPlaintextIfPresent(context)

        val secPrefs = context.getSharedPreferences(SECURE_PREF_NAME, Context.MODE_PRIVATE)
        val ciphertextB64 = secPrefs.getString(KEY_ENC_CIPHERTEXT, null) ?: return ""
        val ivB64 = secPrefs.getString(KEY_ENC_IV, null) ?: return ""

        return try {
            decryptWithKeystore(ciphertextB64, ivB64).trim()
        } catch (_: Exception) {
            ""
        }
    }

    fun hasSecurityKey(context: Context): Boolean {
        migrateLegacyPlaintextIfPresent(context)
        val secPrefs = context.getSharedPreferences(SECURE_PREF_NAME, Context.MODE_PRIVATE)
        return !secPrefs.getString(KEY_VERIFIER_HASH, null).isNullOrEmpty() &&
            !secPrefs.getString(KEY_VERIFIER_SALT, null).isNullOrEmpty()
    }

    fun verifyKey(context: Context, candidate: String): Boolean {
        migrateLegacyPlaintextIfPresent(context)
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) return false

        val secPrefs = context.getSharedPreferences(SECURE_PREF_NAME, Context.MODE_PRIVATE)
        val saltB64 = secPrefs.getString(KEY_VERIFIER_SALT, null) ?: return false
        val expectedB64 = secPrefs.getString(KEY_VERIFIER_HASH, null) ?: return false

        return try {
            val salt = Base64.decode(saltB64, Base64.NO_WRAP)
            val expectedHash = Base64.decode(expectedB64, Base64.NO_WRAP)
            val candidateHash = derivePbkdf2(trimmed, salt)
            MessageDigest.isEqual(candidateHash, expectedHash)
        } catch (_: Exception) {
            false
        }
    }

    fun isProtectionFlagEnabled(context: Context): Boolean {
        return context.getSharedPreferences(SECURE_PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PROTECTION_ENABLED, false)
    }

    fun setProtectionFlagEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(SECURE_PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROTECTION_ENABLED, enabled)
            .apply()
    }

    private fun migrateLegacyPlaintextIfPresent(context: Context) {
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREF_NAME, Context.MODE_PRIVATE)
        val legacyValue = legacyPrefs.getString(LEGACY_KEY_API_KEY, null)
        if (!legacyValue.isNullOrBlank()) {
            saveSecurityKey(context, legacyValue)
        } else if (legacyPrefs.contains(LEGACY_KEY_API_KEY)) {
            legacyPrefs.edit().remove(LEGACY_KEY_API_KEY).apply()
        }
    }

    private fun derivePbkdf2(key: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(key.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun getOrCreateAesKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) {
            return existing
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encryptWithKeystore(plainText: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateAesKey())
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val ivBytes = cipher.iv
        return Base64.encodeToString(cipherBytes, Base64.NO_WRAP) to
            Base64.encodeToString(ivBytes, Base64.NO_WRAP)
    }

    private fun decryptWithKeystore(cipherB64: String, ivB64: String): String {
        val cipherBytes = Base64.decode(cipherB64, Base64.NO_WRAP)
        val ivBytes = Base64.decode(ivB64, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_BITS, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateAesKey(), spec)
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }
}
