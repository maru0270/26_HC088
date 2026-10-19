package com.bikeeyes.app

import android.content.Context

data class RideRecoverySnapshot(
    val sessionId: String,
    val startedAt: Long,
    val elapsedSeconds: Long,
    val distanceMeters: Double
)

class RideRecoveryStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences("bikeeyes_ride_recovery", Context.MODE_PRIVATE)

    fun begin(sessionId: String, startedAt: Long) {
        preferences.edit().putBoolean("active", true).putString("session_id", sessionId)
            .putLong("started_at", startedAt).putLong("elapsed", 0L)
            .putLong("distance_bits", 0.0.toBits()).apply()
    }

    fun update(elapsedSeconds: Long, distanceMeters: Double) {
        if (!preferences.getBoolean("active", false)) return
        preferences.edit().putLong("elapsed", elapsedSeconds)
            .putLong("distance_bits", distanceMeters.toBits()).apply()
    }

    fun recover(): RideRecoverySnapshot? {
        if (!preferences.getBoolean("active", false)) return null
        val sessionId = preferences.getString("session_id", null) ?: return null
        return RideRecoverySnapshot(
            sessionId, preferences.getLong("started_at", 0L),
            preferences.getLong("elapsed", 0L),
            Double.fromBits(preferences.getLong("distance_bits", 0L))
        )
    }

    fun complete() {
        preferences.edit().clear().apply()
    }
}
