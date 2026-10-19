package com.bikeeyes.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

enum class AppAlertType { HAZARD, ACCIDENT, RIDE_SUMMARY }

object AppAlertManager {
    private const val CHANNEL_ID = "bikeeyes_safety_alerts"

    @SuppressLint("MissingPermission")
    fun signal(context: Context, type: AppAlertType) {
        val settings = UserSettingsStore(context).loadNotifications()
        val enabled = when (type) {
            AppAlertType.HAZARD -> settings.hazardAlerts
            AppAlertType.ACCIDENT -> settings.accidentAlerts
            AppAlertType.RIDE_SUMMARY -> settings.rideSummaryAlerts
        }
        if (!enabled) return

        if (settings.vibration) {
            val vibrator = context.getSystemService(Vibrator::class.java)
            vibrator?.vibrate(
                VibrationEffect.createOneShot(
                    if (type == AppAlertType.ACCIDENT) 800L else 350L,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        }
        if (settings.sound) {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80).apply {
                startTone(
                    if (type == AppAlertType.ACCIDENT) {
                        ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
                    } else {
                        ToneGenerator.TONE_PROP_BEEP
                    },
                    500
                )
            }
        }
    }

    fun showRideSummary(context: Context, result: RideResult) {
        val settings = UserSettingsStore(context).loadNotifications()
        if (!settings.rideSummaryAlerts) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "BikeEyes 안전 및 주행 알림",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                enableVibration(settings.vibration)
                setSound(if (settings.sound) android.provider.Settings.System.DEFAULT_NOTIFICATION_URI else null, null)
            }
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("주행이 종료되었습니다")
            .setContentText(
                "거리 %.2fkm · 시간 %d분 · 수집 %d건".format(
                    result.totalDistanceMeters / 1000.0,
                    result.elapsedSeconds / 60,
                    result.collectedFrameCount
                )
            )
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(!settings.sound)
            .build()
        manager.notify(result.rideSessionId.hashCode(), notification)
        signal(context, AppAlertType.RIDE_SUMMARY)
    }

    @SuppressLint("MissingPermission")
    fun showStatusNotification(context: Context, title: String, message: String, id: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "BikeEyes 상태 알림", NotificationManager.IMPORTANCE_DEFAULT)
        )
        manager.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title).setContentText(message)
                .setAutoCancel(true).build()
        )
    }
}
