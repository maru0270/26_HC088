package com.bikeeyes.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class RideResult(
    val rideSessionId: String,
    val startedAt: Long,
    val endedAt: Long,
    val elapsedSeconds: Long,
    val totalDistanceMeters: Double,
    val averageSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val collectedFrameCount: Int,
    val accidentCount: Int,
    val syncStatus: RideSyncStatus
)

enum class RideSyncStatus {
    SYNCED,
    UPLOADING,
    PENDING
}

object RideTrackingState {

    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> =
        _elapsedSeconds.asStateFlow()

    private val _currentSpeedKmh = MutableStateFlow(0.0)
    val currentSpeedKmh: StateFlow<Double> =
        _currentSpeedKmh.asStateFlow()

    private val _totalDistanceMeters = MutableStateFlow(0.0)
    val totalDistanceMeters: StateFlow<Double> =
        _totalDistanceMeters.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _collectedFrameCount = MutableStateFlow(0)
    val collectedFrameCount: StateFlow<Int> = _collectedFrameCount.asStateFlow()

    private val _accidentCount = MutableStateFlow(0)
    val accidentCount: StateFlow<Int> = _accidentCount.asStateFlow()

    private val _gpsAccuracyMeters = MutableStateFlow<Double?>(null)
    val gpsAccuracyMeters: StateFlow<Double?> = _gpsAccuracyMeters.asStateFlow()

    private val _cameraReady = MutableStateFlow(false)
    val cameraReady: StateFlow<Boolean> = _cameraReady.asStateFlow()

    private val _lowLightDetected = MutableStateFlow(false)
    val lowLightDetected: StateFlow<Boolean> = _lowLightDetected.asStateFlow()

    private val _pendingUploadCount = MutableStateFlow(0)
    val pendingUploadCount: StateFlow<Int> = _pendingUploadCount.asStateFlow()

    private val _failedUploadCount = MutableStateFlow(0)
    val failedUploadCount: StateFlow<Int> = _failedUploadCount.asStateFlow()

    private val _serverReachable = MutableStateFlow(true)
    val serverReachable: StateFlow<Boolean> = _serverReachable.asStateFlow()

    private val _lastSyncError = MutableStateFlow<String?>(null)
    val lastSyncError: StateFlow<String?> = _lastSyncError.asStateFlow()

    private var startedAt = 0L
    private var rideSessionId = ""
    private var maxSpeedKmh = 0.0
    private var collectedFrameCountValue = 0
    private var accidentCountValue = 0
    private var rideFinished = false

    private val _lastRideResult =
        MutableStateFlow<RideResult?>(null)

    val lastRideResult: StateFlow<RideResult?> =
        _lastRideResult.asStateFlow()

    fun updateElapsedSeconds(seconds: Long) {
        _elapsedSeconds.value = seconds
    }

    fun updateCurrentSpeedKmh(
        speedKmh: Double,
        accuracyMeters: Float
    ) {
        _currentSpeedKmh.value = speedKmh

        if (accuracyMeters <= MAX_SPEED_ACCURACY_METERS &&
            speedKmh in 0.0..MAX_VALID_SPEED_KMH
        ) {
            maxSpeedKmh = maxOf(maxSpeedKmh, speedKmh)
        }
    }

    fun updateTotalDistanceMeters(distanceMeters: Double) {
        _totalDistanceMeters.value = distanceMeters
    }

    fun incrementCollectedFrameCount() {
        collectedFrameCountValue += 1
        _collectedFrameCount.value = collectedFrameCountValue
    }

    fun incrementAccidentCount() {
        accidentCountValue += 1
        _accidentCount.value = accidentCountValue
    }

    fun setPaused(paused: Boolean) {
        _isPaused.value = paused
        if (paused) _currentSpeedKmh.value = 0.0
    }

    fun updateGpsAccuracy(accuracyMeters: Double) {
        _gpsAccuracyMeters.value = accuracyMeters
    }

    fun updateCameraReady(ready: Boolean) {
        _cameraReady.value = ready
    }

    fun updateLowLightDetected(detected: Boolean) {
        _lowLightDetected.value = detected
    }

    fun updateQueueStatus(pending: Int, retrying: Int, failed: Int) {
        _pendingUploadCount.value = pending + retrying + failed
        _failedUploadCount.value = failed
    }

    fun reportServerSuccess() {
        _serverReachable.value = true
        _lastSyncError.value = null
    }

    fun reportServerFailure(message: String) {
        _serverReachable.value = false
        _lastSyncError.value = message
    }

    fun finishRide(syncStatus: RideSyncStatus): RideResult {
        _lastRideResult.value?.takeIf { rideFinished }?.let {
            return it
        }

        val elapsed = _elapsedSeconds.value
        val distance = _totalDistanceMeters.value
        val averageSpeed = if (elapsed > 0L) {
            (distance / 1000.0) / (elapsed / 3600.0)
        } else {
            0.0
        }

        val result = RideResult(
            rideSessionId = rideSessionId,
            startedAt = startedAt,
            endedAt = System.currentTimeMillis(),
            elapsedSeconds = elapsed,
            totalDistanceMeters = distance,
            averageSpeedKmh = averageSpeed,
            maxSpeedKmh = maxSpeedKmh,
            collectedFrameCount = collectedFrameCountValue,
            accidentCount = accidentCountValue,
            syncStatus = syncStatus
        )

        rideFinished = true
        _lastRideResult.value = result
        return result
    }

    fun updateLastRideSyncStatus(
        rideSessionId: String,
        syncStatus: RideSyncStatus
    ) {
        val current = _lastRideResult.value ?: return
        if (current.rideSessionId != rideSessionId || current.syncStatus == syncStatus) return
        _lastRideResult.value = current.copy(syncStatus = syncStatus)
    }

    fun startRide(startedAtMillis: Long = System.currentTimeMillis()) {
        rideSessionId = UUID.randomUUID().toString()
        startedAt = startedAtMillis
        maxSpeedKmh = 0.0
        collectedFrameCountValue = 0
        accidentCountValue = 0
        rideFinished = false
        _lastRideResult.value = null
        _elapsedSeconds.value = 0L
        _currentSpeedKmh.value = 0.0
        _totalDistanceMeters.value = 0.0
        _isPaused.value = false
        _collectedFrameCount.value = 0
        _accidentCount.value = 0
        _gpsAccuracyMeters.value = null
        _cameraReady.value = false
        _lowLightDetected.value = false
        _lastSyncError.value = null
    }

    fun resetLiveValues() {
        _elapsedSeconds.value = 0L
        _currentSpeedKmh.value = 0.0
        _totalDistanceMeters.value = 0.0
        _isPaused.value = false
    }

    fun abandonRide() {
        rideFinished = true
        _lastRideResult.value = null
        resetLiveValues()
        _collectedFrameCount.value = 0
        _accidentCount.value = 0
        _cameraReady.value = false
        _lowLightDetected.value = false
    }

    fun currentRideSessionId(): String = rideSessionId

    private const val MAX_SPEED_ACCURACY_METERS = 20f
    private const val MAX_VALID_SPEED_KMH = 60.0
}
