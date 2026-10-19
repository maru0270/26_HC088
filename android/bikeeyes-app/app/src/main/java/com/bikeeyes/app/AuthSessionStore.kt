package com.bikeeyes.app

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AuthSessionStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            preferences.getString(KEY_DEVICE_ID, null)?.let { return it }
            val androidId = Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ANDROID_ID
            )
            val generated = if (androidId.isNullOrBlank()) UUID.randomUUID().toString()
            else "android-$androidId"
            preferences.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }

    val deviceName: String
        get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun accessToken(): String? = decrypt(preferences.getString(KEY_ACCESS_TOKEN, null))
    fun refreshToken(): String? = decrypt(preferences.getString(KEY_REFRESH_TOKEN, null))
    fun hasSession(): Boolean = !refreshToken().isNullOrBlank()

    fun save(response: AuthTokenResponse) {
        val now = System.currentTimeMillis()
        preferences.edit()
            .putString(KEY_ACCESS_TOKEN, encrypt(response.access_token))
            .putString(KEY_REFRESH_TOKEN, encrypt(response.refresh_token))
            .putLong(KEY_ACCESS_EXPIRES_AT, now + response.access_expires_in * 1000)
            .putLong(KEY_REFRESH_EXPIRES_AT, now + response.refresh_expires_in * 1000)
            .putLong(KEY_USER_ID, response.user_id)
            .putBoolean(KEY_MUST_CHANGE_PASSWORD, response.must_change_password)
            .apply()
    }

    fun accessTokenNeedsRefresh(): Boolean =
        System.currentTimeMillis() + 60_000 >= preferences.getLong(KEY_ACCESS_EXPIRES_AT, 0)

    fun refreshTokenExpired(): Boolean =
        System.currentTimeMillis() >= preferences.getLong(KEY_REFRESH_EXPIRES_AT, 0)

    fun clear() {
        preferences.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_ACCESS_EXPIRES_AT)
            .remove(KEY_REFRESH_EXPIRES_AT)
            .remove(KEY_USER_ID)
            .remove(KEY_MUST_CHANGE_PASSWORD)
            .apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encoded = Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray()))
        val iv = Base64.getEncoder().encodeToString(cipher.iv)
        return "$iv:$encoded"
    }

    private fun decrypt(value: String?): String? = runCatching {
        if (value.isNullOrBlank()) return null
        val parts = value.split(":", limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.getDecoder().decode(parts[0]))
        )
        String(
            cipher.doFinal(Base64.getDecoder().decode(parts[1])),
            StandardCharsets.UTF_8
        )
    }.getOrNull()

    companion object {
        private const val PREFERENCES = "bikeeyes_auth_session"
        private const val KEY_ALIAS = "bikeeyes_auth_tokens"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_ACCESS_EXPIRES_AT = "access_expires_at"
        private const val KEY_REFRESH_EXPIRES_AT = "refresh_expires_at"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_MUST_CHANGE_PASSWORD = "must_change_password"
    }
}
