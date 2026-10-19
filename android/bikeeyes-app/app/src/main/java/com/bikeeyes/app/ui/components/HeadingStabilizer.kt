package com.bikeeyes.app.ui.components

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin


internal class HeadingStabilizer {
    private val gpsBearings = ArrayDeque<Float>(GPS_BEARING_WINDOW_SIZE)
    private var lastGpsSampleId: Long? = null
    private var smoothedSensorHeading: Float? = null
    private var acceptedHeading: Float? = null
    private var lastAcceptedAtMs: Long = Long.MIN_VALUE
    private var pendingAbruptHeading: Float? = null
    private var pendingAbruptSinceMs: Long = 0L

    val currentHeading: Float?
        get() = acceptedHeading

    /** 새 방향이 실제로 채택된 경우에만 값을 반환. */
    fun update(
        nowMs: Long,
        speedMetersPerSecond: Float,
        accuracyMeters: Float,
        gpsBearingDegrees: Float?,
        gpsSampleId: Long?,
        sensorBearingDegrees: Float?
    ): Float? {
        sensorBearingDegrees?.let { measured ->
            smoothedSensorHeading = circularSmooth(
                previous = smoothedSensorHeading,
                measured = normalize(measured),
                factor = SENSOR_SMOOTHING_FACTOR
            )
        }

        // A bicycle mount and nearby metal can make magnetic orientation rotate
        // continuously. Course-up navigation therefore changes direction only from
        // a valid GPS travel bearing; while stopped or moving very slowly, keep the
        // last accepted direction instead of rotating the map from compass noise.
        if (speedMetersPerSecond < GPS_PRIORITY_SPEED_METERS_PER_SECOND) {
            clearAbruptCandidate()
            return null
        }
        if (accuracyMeters > MAX_GPS_ACCURACY_METERS || gpsBearingDegrees == null) {
            clearAbruptCandidate()
            return null
        }
        if (gpsSampleId != null && gpsSampleId != lastGpsSampleId) {
            lastGpsSampleId = gpsSampleId
            if (gpsBearings.size == GPS_BEARING_WINDOW_SIZE) gpsBearings.removeFirst()
            gpsBearings.addLast(normalize(gpsBearingDegrees))
        }
        if (gpsBearings.isEmpty()) return null
        val source = HeadingSource.GPS
        val candidate = circularMean(gpsBearings)

        val previous = acceptedHeading
        if (previous == null) return accept(candidate, nowMs)
        if (nowMs - lastAcceptedAtMs < MAP_UPDATE_INTERVAL_MS) return null

        val change = angularDistance(previous, candidate)
        val deadband = if (source == HeadingSource.GPS) GPS_DEADBAND_DEGREES else SENSOR_DEADBAND_DEGREES
        if (change < deadband) {
            clearAbruptCandidate()
            return null
        }

        if (change > ABRUPT_CHANGE_DEGREES) {
            val pending = pendingAbruptHeading
            if (pending == null || angularDistance(pending, candidate) > ABRUPT_RESET_DEGREES) {
                pendingAbruptHeading = candidate
                pendingAbruptSinceMs = nowMs
                return null
            }
            pendingAbruptHeading = candidate
            if (nowMs - pendingAbruptSinceMs < ABRUPT_CONFIRM_DURATION_MS) return null
        }

        return accept(candidate, nowMs)
    }

    private fun accept(heading: Float, nowMs: Long): Float {
        val normalized = normalize(heading)
        acceptedHeading = normalized
        lastAcceptedAtMs = nowMs
        clearAbruptCandidate()
        return normalized
    }

    private fun clearAbruptCandidate() {
        pendingAbruptHeading = null
        pendingAbruptSinceMs = 0L
    }

    private enum class HeadingSource { GPS, SENSOR }

    internal companion object {
        const val STATIONARY_SPEED_METERS_PER_SECOND = 0.5f
        const val GPS_PRIORITY_SPEED_METERS_PER_SECOND = 1.5f
        const val MAX_GPS_ACCURACY_METERS = 15f
        const val GPS_DEADBAND_DEGREES = 6f
        const val SENSOR_DEADBAND_DEGREES = 7f
        const val MAP_UPDATE_INTERVAL_MS = 250L
        const val ABRUPT_CHANGE_DEGREES = 45f
        const val ABRUPT_CONFIRM_DURATION_MS = 400L
        const val ABRUPT_RESET_DEGREES = 60f
        const val SENSOR_SMOOTHING_FACTOR = 0.25f
        const val GPS_BEARING_WINDOW_SIZE = 3

        internal fun circularMean(values: Collection<Float>): Float {
            val x = values.sumOf { cos(Math.toRadians(it.toDouble())) }
            val y = values.sumOf { sin(Math.toRadians(it.toDouble())) }
            return normalize(Math.toDegrees(atan2(y, x)).toFloat())
        }

        internal fun angularDistance(first: Float, second: Float): Float =
            kotlin.math.abs(((second - first + 540f) % 360f) - 180f)

        private fun circularSmooth(previous: Float?, measured: Float, factor: Float): Float {
            if (previous == null) return measured
            val delta = ((measured - previous + 540f) % 360f) - 180f
            return normalize(previous + delta * factor)
        }

        private fun normalize(value: Float): Float = (value % 360f + 360f) % 360f
    }
}
