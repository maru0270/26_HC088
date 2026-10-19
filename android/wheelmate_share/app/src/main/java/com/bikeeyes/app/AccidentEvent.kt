package com.bikeeyes.app

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class AccidentConfirmation { ACCIDENT, NO_RESPONSE, SAFE }

data class AccidentAlert(
    val event_uuid: String = UUID.randomUUID().toString(),
    val ride_session_id: String?,
    val detected_at: Long,
    val latitude: Double?, val longitude: Double?, val accuracy: Double?, val speed: Double?,
    val accel_x: Double, val accel_y: Double, val accel_z: Double,
    val gyro_x: Double, val gyro_y: Double, val gyro_z: Double,
    val acceleration_magnitude: Double, val gyro_magnitude: Double
)

data class AccidentEventRequest(
    val event_uuid: String, val ride_session_id: String?, val detected_at: Long,
    val latitude: Double?, val longitude: Double?, val accuracy: Double?, val speed: Double?,
    val accel_x: Double, val accel_y: Double, val accel_z: Double,
    val gyro_x: Double, val gyro_y: Double, val gyro_z: Double,
    val acceleration_magnitude: Double, val gyro_magnitude: Double,
    val confirmation_status: String, val is_false_positive: Boolean
)

data class AccidentImageItem(
    val event_uuid: String,
    val captured_at: Long,
    val peak_sample_at: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Double? = null,
    val speed: Double? = null,
    val direction: Double? = null,
    val accel_x: Double? = null,
    val accel_y: Double? = null,
    val accel_z: Double? = null,
    val gyro_x: Double? = null,
    val gyro_y: Double? = null,
    val gyro_z: Double? = null,
    val geomagnetic_x: Double? = null,
    val geomagnetic_y: Double? = null,
    val geomagnetic_z: Double? = null,
    val sensor_samples: List<SensorSample> = emptyList()
)

data class AccidentImageListResponse(
    val accident_event_uuid: String,
    val lookback_seconds: Int,
    val items: List<AccidentImageItem>
)

fun AccidentAlert.toRequest(result: AccidentConfirmation) = AccidentEventRequest(
    event_uuid, ride_session_id, detected_at, latitude, longitude, accuracy, speed,
    accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z,
    acceleration_magnitude, gyro_magnitude, result.name, result == AccidentConfirmation.SAFE
)

@Entity(
    tableName = "accident_events",
    indices = [
        Index("event_uuid", unique = true),
        Index("ride_session_id"),
        Index("detected_at"),
        Index(value = ["sync_status", "detected_at"])
    ]
)
data class AccidentEventEntity(
    @PrimaryKey(autoGenerate = true) val accident_event_id: Long = 0,
    val event_uuid: String, val ride_session_id: String?, val detected_at: Long,
    val latitude: Double?, val longitude: Double?, val accuracy: Double?, val speed: Double?,
    val accel_x: Double, val accel_y: Double, val accel_z: Double,
    val gyro_x: Double, val gyro_y: Double, val gyro_z: Double,
    val acceleration_magnitude: Double, val gyro_magnitude: Double,
    val confirmation_status: String, val is_false_positive: Boolean,
    val sync_status: String = "PENDING", val created_at: Long
)

@Dao
interface AccidentEventDao {
    @Insert suspend fun insert(event: AccidentEventEntity): Long

    @Query("SELECT * FROM accident_events ORDER BY detected_at DESC")
    fun observeAll(): Flow<List<AccidentEventEntity>>

    @Query("SELECT * FROM accident_events WHERE event_uuid = :eventUuid LIMIT 1")
    fun observeByEventUuid(eventUuid: String): Flow<AccidentEventEntity?>

    @Query("SELECT * FROM accident_events WHERE ride_session_id = :sessionId ORDER BY detected_at DESC")
    fun observeByRideSession(sessionId: String): Flow<List<AccidentEventEntity>>

    @Query("UPDATE accident_events SET sync_status = :status WHERE event_uuid = :eventUuid")
    suspend fun updateSyncStatus(eventUuid: String, status: String)
}

object AccidentAlertState {
    private val _active = MutableStateFlow<AccidentAlert?>(null)
    val active = _active.asStateFlow()
    @Synchronized fun offer(alert: AccidentAlert) { if (_active.value == null) _active.value = alert }
    @Synchronized fun resolve(eventUuid: String): AccidentAlert? {
        val current = _active.value ?: return null
        if (current.event_uuid != eventUuid) return null
        _active.value = null
        return current
    }
}

class AccidentDetector(
    private val onDetected: () -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private var impactAt = 0L
    private var rotationAt = 0L
    private var candidateAt = 0L
    private var stationarySince = 0L
    private var lastDetectedAt = 0L
    private var candidateLatitude: Double? = null
    private var candidateLongitude: Double? = null
    private var stableGravityX: Double? = null
    private var stableGravityY: Double? = null
    private var stableGravityZ: Double? = null
    private var preImpactGravity: Triple<Double, Double, Double>? = null
    private var orientationChanged = false

    fun update(accelX: Double, accelY: Double, accelZ: Double, gyroX: Double, gyroY: Double, gyroZ: Double) {
        val now = nowMillis()
        val acceleration = sqrt(accelX*accelX + accelY*accelY + accelZ*accelZ)
        val rotation = sqrt(gyroX*gyroX + gyroY*gyroY + gyroZ*gyroZ)

        if (candidateAt == 0L &&
            acceleration in STABLE_ACCELERATION_MIN..STABLE_ACCELERATION_MAX &&
            rotation <= STABLE_ROTATION_MAX
        ) {
            updateStableGravity(accelX, accelY, accelZ)
        } else if (candidateAt != 0L &&
            acceleration in STABLE_ACCELERATION_MIN..STABLE_ACCELERATION_MAX &&
            rotation <= STABLE_ROTATION_MAX
        ) {
            val before = preImpactGravity
            if (before != null && orientationAngleDegrees(before, accelX, accelY, accelZ) >= ORIENTATION_CHANGE_THRESHOLD_DEGREES) {
                orientationChanged = true
            }
        }

        if (kotlin.math.abs(acceleration - EARTH_GRAVITY) >= IMPACT_DELTA_THRESHOLD) impactAt = now
        if (rotation >= ROTATION_THRESHOLD) rotationAt = now

        if (candidateAt == 0L &&
            now - impactAt <= IMPACT_ROTATION_WINDOW_MS &&
            now - rotationAt <= IMPACT_ROTATION_WINDOW_MS &&
            now - lastDetectedAt >= DETECTION_COOLDOWN_MS
        ) {
            candidateAt = now
            stationarySince = 0L
            candidateLatitude = null
            candidateLongitude = null
            preImpactGravity = normalizedStableGravity()
            orientationChanged = false
        }
    }

    fun updateLocation(
        latitude: Double,
        longitude: Double,
        accuracyMeters: Double,
        speedMetersPerSecond: Double
    ) {
        val now = nowMillis()
        if (candidateAt == 0L) return
        if (now - candidateAt > CANDIDATE_TIMEOUT_MS) {
            clearCandidate()
            return
        }
        if (accuracyMeters > MAX_ACCIDENT_GPS_ACCURACY_M) return

        // A bicycle crash should lead to a stop shortly after the impact.  Discard
        // candidates that keep moving so an ordinary bump followed by a later
        // traffic-light stop cannot produce an alert.
        if (speedMetersPerSecond >= CANDIDATE_CANCEL_SPEED_MPS) {
            clearCandidate()
            return
        }

        val anchorLatitude = candidateLatitude
        val anchorLongitude = candidateLongitude
        if (anchorLatitude == null || anchorLongitude == null) {
            if (speedMetersPerSecond > STATIONARY_SPEED_MPS &&
                now - candidateAt >= MAX_STATIONARY_ONSET_MS
            ) {
                clearCandidate()
                return
            }
            candidateLatitude = latitude
            candidateLongitude = longitude
            stationarySince = if (speedMetersPerSecond <= STATIONARY_SPEED_MPS) now else 0L
            return
        }

        val distanceMeters = distanceMeters(anchorLatitude, anchorLongitude, latitude, longitude)
        if (distanceMeters >= CANDIDATE_CANCEL_DISTANCE_M) {
            clearCandidate()
            return
        }
        val stationary = speedMetersPerSecond <= STATIONARY_SPEED_MPS &&
            distanceMeters <= STATIONARY_DISTANCE_M

        if (!stationary) {
            // 충격 직후 관성으로 조금 더 이동할 수 있으므로 후보는 유지하되,
            // 정지 판정 기준점과 타이머를 현재 위치로 갱신한다.
            candidateLatitude = latitude
            candidateLongitude = longitude
            stationarySince = 0L
            if (now - candidateAt >= MAX_STATIONARY_ONSET_MS) clearCandidate()
            return
        }

        if (stationarySince == 0L && now - candidateAt > MAX_STATIONARY_ONSET_MS) {
            clearCandidate()
            return
        }

        if (stationarySince == 0L) stationarySince = now
        if (now - stationarySince >= REQUIRED_STATIONARY_MS && orientationChanged) {
            lastDetectedAt = now
            clearCandidate()
            onDetected()
        }
    }

    fun reset() {
        impactAt = 0L
        rotationAt = 0L
        stableGravityX = null
        stableGravityY = null
        stableGravityZ = null
        clearCandidate()
    }

    private fun clearCandidate() {
        candidateAt = 0L
        stationarySince = 0L
        candidateLatitude = null
        candidateLongitude = null
        preImpactGravity = null
        orientationChanged = false
    }

    private fun updateStableGravity(x: Double, y: Double, z: Double) {
        stableGravityX = lowPass(stableGravityX, x)
        stableGravityY = lowPass(stableGravityY, y)
        stableGravityZ = lowPass(stableGravityZ, z)
    }

    private fun lowPass(previous: Double?, measured: Double): Double =
        previous?.let { it + (measured - it) * GRAVITY_SMOOTHING_FACTOR } ?: measured

    private fun normalizedStableGravity(): Triple<Double, Double, Double>? {
        val x = stableGravityX ?: return null
        val y = stableGravityY ?: return null
        val z = stableGravityZ ?: return null
        val magnitude = sqrt(x * x + y * y + z * z)
        if (magnitude < MIN_VECTOR_MAGNITUDE) return null
        return Triple(x / magnitude, y / magnitude, z / magnitude)
    }

    private fun orientationAngleDegrees(
        before: Triple<Double, Double, Double>,
        x: Double,
        y: Double,
        z: Double
    ): Double {
        val magnitude = sqrt(x * x + y * y + z * z)
        if (magnitude < MIN_VECTOR_MAGNITUDE) return 0.0
        val dot = (before.first * x + before.second * y + before.third * z) / magnitude
        return Math.toDegrees(kotlin.math.acos(dot.coerceIn(-1.0, 1.0)))
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val latitudeDelta = Math.toRadians(lat2 - lat1)
        val longitudeDelta = Math.toRadians(lon2 - lon1)
        val firstLatitude = Math.toRadians(lat1)
        val secondLatitude = Math.toRadians(lat2)
        val a = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(firstLatitude) * cos(secondLatitude) *
            sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    companion object {
        private const val EARTH_GRAVITY = 9.80665
        private const val EARTH_RADIUS_M = 6_371_000.0
        private const val IMPACT_DELTA_THRESHOLD = 5.0
        private const val ROTATION_THRESHOLD = 3.8
        private const val IMPACT_ROTATION_WINDOW_MS = 1_000L
        private const val ORIENTATION_CHANGE_THRESHOLD_DEGREES = 60.0
        private const val STABLE_ACCELERATION_MIN = 7.0
        private const val STABLE_ACCELERATION_MAX = 13.0
        private const val STABLE_ROTATION_MAX = 1.5
        private const val GRAVITY_SMOOTHING_FACTOR = 0.15
        private const val MIN_VECTOR_MAGNITUDE = 1.0
        private const val MAX_STATIONARY_ONSET_MS = 4_000L
        // 충격 후 4초 안에 정지가 시작된 경우 30초 동안 정지 상태를 확인한다.
        // 위치 갱신 지연까지 수용하도록 후보 만료시간은 여유 있게 40초로 둔다.
        private const val CANDIDATE_TIMEOUT_MS = 40_000L
        private const val REQUIRED_STATIONARY_MS = 30_000L
        private const val STATIONARY_SPEED_MPS = 0.8
        private const val STATIONARY_DISTANCE_M = 5.0
        private const val CANDIDATE_CANCEL_SPEED_MPS = 1.5
        private const val CANDIDATE_CANCEL_DISTANCE_M = 8.0
        private const val MAX_ACCIDENT_GPS_ACCURACY_M = 15.0
        private const val DETECTION_COOLDOWN_MS = 60_000L
    }
}
