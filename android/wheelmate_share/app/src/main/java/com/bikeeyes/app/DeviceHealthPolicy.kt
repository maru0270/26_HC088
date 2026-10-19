package com.bikeeyes.app

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs

object DeviceHealthPolicy {
    const val LOW_BATTERY_PERCENT = 20
    const val STORAGE_WARNING_BYTES = 1L * 1024 * 1024 * 1024
    const val IMAGE_STOP_BYTES = 500L * 1024 * 1024

    fun batteryPercent(context: Context): Int =
        (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    fun availableBytes(context: Context): Long = StatFs(context.filesDir.absolutePath).availableBytes

    fun canCaptureImages(context: Context): Boolean = availableBytes(context) >= IMAGE_STOP_BYTES
}
