package com.bikeeyes.app

import android.content.Context
import java.security.MessageDigest

data class UserProfile(
    val nickname: String,
    val email: String,
    val phone: String,
    val role: String = "USER"
)

data class NotificationSettings(
    val hazardAlerts: Boolean,
    val accidentAlerts: Boolean,
    val rideSummaryAlerts: Boolean,
    val vibration: Boolean,
    val sound: Boolean
)

class UserSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "bikeeyes_user_settings",
        Context.MODE_PRIVATE
    )

    fun loadProfile() = UserProfile(
        nickname = preferences.getString(KEY_NAME, "BikeEyes 사용자").orEmpty(),
        email = preferences.getString(KEY_EMAIL, "").orEmpty(),
        phone = preferences.getString(KEY_PHONE, "").orEmpty(),
        role = preferences.getString(KEY_ROLE, "USER").orEmpty()
    )

    fun saveProfile(profile: UserProfile) {
        preferences.edit()
            .putString(KEY_NAME, profile.nickname.trim())
            .putString(KEY_EMAIL, profile.email.trim())
            .putString(KEY_PHONE, profile.phone.trim())
            .putString(KEY_ROLE, profile.role)
            .apply()
    }

    fun clearProfile() {
        preferences.edit()
            .remove(KEY_NAME)
            .remove(KEY_EMAIL)
            .remove(KEY_PHONE)
            .remove(KEY_ROLE)
            .remove(KEY_PASSWORD_HASH)
            .apply()
    }

    fun loadNotifications() = NotificationSettings(
        hazardAlerts = preferences.getBoolean(KEY_HAZARD_ALERTS, true),
        accidentAlerts = preferences.getBoolean(KEY_ACCIDENT_ALERTS, true),
        rideSummaryAlerts = preferences.getBoolean(KEY_RIDE_SUMMARY_ALERTS, true),
        vibration = preferences.getBoolean(KEY_VIBRATION, true),
        sound = preferences.getBoolean(KEY_SOUND, false)
    )

    fun saveNotifications(settings: NotificationSettings) {
        preferences.edit()
            .putBoolean(KEY_HAZARD_ALERTS, settings.hazardAlerts)
            .putBoolean(KEY_ACCIDENT_ALERTS, settings.accidentAlerts)
            .putBoolean(KEY_RIDE_SUMMARY_ALERTS, settings.rideSummaryAlerts)
            .putBoolean(KEY_VIBRATION, settings.vibration)
            .putBoolean(KEY_SOUND, settings.sound)
            .apply()
    }

    fun hasPassword(): Boolean = preferences.contains(KEY_PASSWORD_HASH)

    fun verifyPassword(password: String): Boolean =
        preferences.getString(KEY_PASSWORD_HASH, null) == password.sha256()

    fun changePassword(newPassword: String) {
        preferences.edit().putString(KEY_PASSWORD_HASH, newPassword.sha256()).apply()
    }

    fun loadServerBaseUrl(): String =
        preferences.getString(KEY_SERVER_BASE_URL, DEFAULT_SERVER_BASE_URL)
            ?: DEFAULT_SERVER_BASE_URL

    fun saveServerBaseUrl(baseUrl: String) {
        preferences.edit().putString(KEY_SERVER_BASE_URL, normalizeServerBaseUrl(baseUrl)).apply()
    }

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val KEY_NAME = "name"
        private const val KEY_EMAIL = "email"
        private const val KEY_PHONE = "phone"
        private const val KEY_ROLE = "role"
        private const val KEY_PASSWORD_HASH = "password_hash"
        private const val KEY_HAZARD_ALERTS = "hazard_alerts"
        private const val KEY_ACCIDENT_ALERTS = "accident_alerts"
        private const val KEY_RIDE_SUMMARY_ALERTS = "ride_summary_alerts"
        private const val KEY_VIBRATION = "vibration"
        private const val KEY_SOUND = "sound"
        private const val KEY_SERVER_BASE_URL = "server_base_url"

        // 내부 테스트 서버용 url(이더넷)
//        const val DEFAULT_SERVER_BASE_URL = "http://192.168.10.111:8000/"

        // 내부 테스트 서버용 url(와이파이)
//        const val DEFAULT_SERVER_BASE_URL = "http://192.168.100.7:8000/"

        // 외부 배포용 url
        const val DEFAULT_SERVER_BASE_URL = "https://flail-viable-handsaw.ngrok-free.dev/"

        fun normalizeServerBaseUrl(input: String): String {
            var value = input.trim()
            if (!value.startsWith("http://") && !value.startsWith("https://")) {
                value = "http://$value"
            }
            return value.trimEnd('/') + "/"
        }

        fun isValidServerBaseUrl(input: String): Boolean = runCatching {
            val uri = java.net.URI(normalizeServerBaseUrl(input))
            (uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http")) &&
                !uri.host.isNullOrBlank() &&
                uri.port in -1..65535
        }.getOrDefault(false)
    }
}
