package com.bikeeyes.app

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ride_results",
    indices = [
        Index(value = ["ride_session_id"], unique = true),
        Index(value = ["ended_at"]),
        Index(value = ["sync_status", "ended_at"])
    ]
)
data class RideResultEntity(
    @PrimaryKey(autoGenerate = true)
    val ride_result_id: Long = 0,
    val ride_session_id: String,
    val started_at: Long,
    val ended_at: Long,
    val elapsed_seconds: Long,
    val total_distance_meters: Double,
    val average_speed_kmh: Double,
    val max_speed_kmh: Double,
    val collected_frame_count: Int,
    val accident_count: Int,
    val sync_status: String
)

fun RideResult.toEntity(): RideResultEntity = RideResultEntity(
    ride_session_id = rideSessionId,
    started_at = startedAt,
    ended_at = endedAt,
    elapsed_seconds = elapsedSeconds,
    total_distance_meters = totalDistanceMeters,
    average_speed_kmh = averageSpeedKmh,
    max_speed_kmh = maxSpeedKmh,
    collected_frame_count = collectedFrameCount,
    accident_count = accidentCount,
    sync_status = syncStatus.name
)

data class RideResultRequest(
    val ride_session_id: String,
    val started_at: Long,
    val ended_at: Long,
    val elapsed_seconds: Long,
    val total_distance_meters: Double,
    val average_speed_kmh: Double,
    val max_speed_kmh: Double,
    val collected_frame_count: Int,
    val accident_count: Int
)

data class ServerRideItem(
    val ride_session_id: String,
    val started_at: Long,
    val ended_at: Long,
    val elapsed_seconds: Long,
    val total_distance_meters: Double,
    val average_speed_kmh: Double,
    val max_speed_kmh: Double,
    val collected_frame_count: Int,
    val accident_count: Int,
    val updated_at: String? = null
)

data class ServerRideListResponse(
    val items: List<ServerRideItem>,
    val total: Int,
    val limit: Int,
    val offset: Int
)

data class ServerRoutePoint(
    val captured_at: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Double?,
    val speed: Double?,
    val direction: Double?
)

data class ServerRideDetail(
    val ride_session_id: String,
    val started_at: Long,
    val ended_at: Long,
    val elapsed_seconds: Long,
    val total_distance_meters: Double,
    val average_speed_kmh: Double,
    val max_speed_kmh: Double,
    val collected_frame_count: Int,
    val accident_count: Int,
    val route: List<ServerRoutePoint>
)

data class RoadAnomalyDetection(
    val class_name: String,
    val confidence: Double,
    val stage: String,
    val model_version: String
)

data class ServerSensorLog(
    val captured_at: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Double?,
    val speed: Double?,
    val direction: Double?,
    val gyro_x: Double,
    val gyro_y: Double,
    val gyro_z: Double,
    val accel_x: Double,
    val accel_y: Double,
    val accel_z: Double,
    val geomagnetic_x: Double,
    val geomagnetic_y: Double,
    val geomagnetic_z: Double,
    val image_path: String?,
    val event_uuid: String? = null,
    val road_anomaly_detections: List<RoadAnomalyDetection> = emptyList()
)

data class ServerSensorListResponse(
    val items: List<ServerSensorLog>,
    val total: Int = 0,
    val limit: Int,
    val offset: Int
)

data class DeletedRideItem(
    val ride_session_id: String,
    val started_at: Long,
    val ended_at: Long,
    val elapsed_seconds: Long,
    val total_distance_meters: Double,
    val deleted_at: String,
    val deletion_due_at: String
)

data class RideDeleteResponse(
    val message: String,
    val recoverable: Boolean,
    val deletion_due_at: String?
)

fun ServerRideItem.toEntity() = RideResultEntity(
    ride_session_id = ride_session_id,
    started_at = started_at,
    ended_at = ended_at,
    elapsed_seconds = elapsed_seconds,
    total_distance_meters = total_distance_meters,
    average_speed_kmh = average_speed_kmh,
    max_speed_kmh = max_speed_kmh,
    collected_frame_count = collected_frame_count,
    accident_count = accident_count,
    sync_status = RideSyncStatus.SYNCED.name
)

fun RideResult.toRequest(): RideResultRequest = RideResultRequest(
    ride_session_id = rideSessionId,
    started_at = startedAt,
    ended_at = endedAt,
    elapsed_seconds = elapsedSeconds,
    total_distance_meters = totalDistanceMeters,
    average_speed_kmh = averageSpeedKmh,
    max_speed_kmh = maxSpeedKmh,
    collected_frame_count = collectedFrameCount,
    accident_count = accidentCount
)

fun RideResultEntity.toRequest(): RideResultRequest = RideResultRequest(
    ride_session_id = ride_session_id,
    started_at = started_at,
    ended_at = ended_at,
    elapsed_seconds = elapsed_seconds,
    total_distance_meters = total_distance_meters,
    average_speed_kmh = average_speed_kmh,
    max_speed_kmh = max_speed_kmh,
    collected_frame_count = collected_frame_count,
    accident_count = accident_count
)
