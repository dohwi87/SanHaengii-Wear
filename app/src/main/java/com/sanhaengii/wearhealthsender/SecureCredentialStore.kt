package com.sanhaengii.wearhealthsender

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun save(credentials: WatchCredentials) {
        val json = JSONObject()
            .put("token", credentials.token)
            .put("user_id", credentials.userId)
            .toString()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val payload = cipher.iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_ENCRYPTED_CREDENTIALS, Base64.encodeToString(payload, Base64.NO_WRAP))
            .remove(LEGACY_TOKEN_KEY)
            .remove(LEGACY_USER_ID_KEY)
            .apply()
    }

    @Synchronized
    fun load(): WatchCredentials? {
        val encoded = preferences.getString(KEY_ENCRYPTED_CREDENTIALS, null)
        if (!encoded.isNullOrBlank()) {
            return runCatching { decrypt(encoded) }.getOrNull()
        }

        // 이전 버전의 평문 SharedPreferences 값을 한 번만 암호화 형식으로 이전한다.
        val legacyToken = preferences.getString(LEGACY_TOKEN_KEY, null)?.trim().orEmpty()
        val legacyUserId = preferences.getString(LEGACY_USER_ID_KEY, null)?.toLongOrNull()
        if (legacyToken.isNotBlank() && legacyUserId != null && legacyUserId > 0) {
            return WatchCredentials(legacyToken, legacyUserId).also(::save)
        }
        return null
    }

    fun clear() {
        preferences.edit()
            .remove(KEY_ENCRYPTED_CREDENTIALS)
            .remove(LEGACY_TOKEN_KEY)
            .remove(LEGACY_USER_ID_KEY)
            .apply()
    }

    private fun decrypt(encoded: String): WatchCredentials {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > GCM_IV_LENGTH_BYTES) { "Invalid encrypted credential payload" }
        val iv = payload.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_LENGTH_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        return WatchCredentials(
            token = json.getString("token"),
            userId = json.getLong("user_id"),
        )
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFS_NAME = "sanhaengii_watch_prefs"
        private const val KEY_ENCRYPTED_CREDENTIALS = "encrypted_watch_credentials"
        private const val LEGACY_TOKEN_KEY = "health_api_token"
        private const val LEGACY_USER_ID_KEY = "health_api_user_id"
        private const val KEY_ALIAS = "sanhaengii_watch_credentials_v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
