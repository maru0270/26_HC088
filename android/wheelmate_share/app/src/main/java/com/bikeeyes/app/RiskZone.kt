package com.bikeeyes.app

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow

@Entity(tableName = "risk_zones")
data class RiskZoneEntity(
    @androidx.room.PrimaryKey val zone_id: String,
    val title: String,
    val road_damage_type: String,
    val severity: String,
    val latitude: Double,
    val longitude: Double,
    val radius_meters: Double,
    val detection_count: Int,
    val description: String,
    val last_detected_at: Long,
    val is_mock: Boolean,
    val updated_at: Long
)

@Dao
interface RiskZoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(zones: List<RiskZoneEntity>)

    @Query("SELECT * FROM risk_zones ORDER BY last_detected_at DESC")
    fun observeAll(): Flow<List<RiskZoneEntity>>

    @Query("SELECT * FROM risk_zones ORDER BY last_detected_at DESC")
    suspend fun getAll(): List<RiskZoneEntity>

    @Query("SELECT * FROM risk_zones WHERE zone_id = :zoneId LIMIT 1")
    fun observeById(zoneId: String): Flow<RiskZoneEntity?>

    @Query("SELECT COUNT(*) FROM risk_zones")
    suspend fun count(): Int

    @Query("DELETE FROM risk_zones WHERE is_mock = 1")
    suspend fun deleteMockZones()

    @Query("DELETE FROM risk_zones WHERE is_mock = 0")
    suspend fun deleteServerZones()
}

interface RiskZoneRepository {
    fun observeZones(): Flow<List<RiskZoneEntity>>
    fun observeZone(zoneId: String): Flow<RiskZoneEntity?>
    suspend fun refresh(): Result<Unit>
}

class MockRiskZoneRepository(
    private val dao: RiskZoneDao,
    private val api: SensorApi? = null
) : RiskZoneRepository {
    override fun observeZones(): Flow<List<RiskZoneEntity>> = dao.observeAll()
    override fun observeZone(zoneId: String): Flow<RiskZoneEntity?> = dao.observeById(zoneId)

    override suspend fun refresh(): Result<Unit> = runCatching {
        val serverResponse = api?.getHazardZones(
            includeCandidates = false,
            includeTestData = false,
        )
        if (serverResponse != null) {
            // The server is the authority for whether a DB-marked demo batch
            // is publicly visible. Keep the safety/status checks locally, but
            // do not discard an explicitly published batch only because its
            // audit row remains marked as test data in PostgreSQL.
            val serverZones = serverResponse.zones.filter { zone ->
                zone.status.equals("CONFIRMED", ignoreCase = true) &&
                    zone.zone_category.equals("USER_HAZARD", ignoreCase = true) &&
                    zone.is_user_visible &&
                    (zone.expires_at == null || zone.expires_at > System.currentTimeMillis())
            }
            dao.deleteServerZones()
            dao.deleteMockZones()
            if (serverZones.isNotEmpty()) {
                dao.upsertAll(serverZones.map { it.toEntity() })
            }
        }
        RiskZoneApproachMonitor.updateZones(dao.getAll())
    }

    private fun HazardZoneItem.toEntity() = RiskZoneEntity(
        zone_id = zone_id,
        title = title,
        road_damage_type = road_damage_type,
        severity = severity,
        latitude = latitude,
        longitude = longitude,
        radius_meters = radius_meters,
        detection_count = detection_count,
        description = description,
        last_detected_at = last_detected_at,
        is_mock = false,
        updated_at = updated_at
    )

}

data class RiskZoneApproachAlert(
    val zoneId: String,
    val title: String,
    val roadDamageType: String,
    val severity: String,
    val distanceMeters: Float
) {
    val voiceType: ApproachVoiceType
        get() = if (
            roadDamageType.contains("ACCIDENT", ignoreCase = true) ||
            title.contains("사고")
        ) ApproachVoiceType.ACCIDENT_ZONE else ApproachVoiceType.HAZARD_ZONE
}

object RiskZoneApproachMonitor {
    private val _activeAlert =
        kotlinx.coroutines.flow.MutableStateFlow<RiskZoneApproachAlert?>(null)
    val activeAlert = _activeAlert.asStateFlow()

    private var zones: List<RiskZoneEntity> = emptyList()
    private val lastAlertedAt = mutableMapOf<String, Long>()

    fun updateZones(newZones: List<RiskZoneEntity>) {
        zones = newZones
    }

    fun onLocation(latitude: Double, longitude: Double, accuracyMeters: Float) {
        if (accuracyMeters > 50f || _activeAlert.value != null) return
        val now = System.currentTimeMillis()
        zones.forEach { zone ->
            if (now - (lastAlertedAt[zone.zone_id] ?: 0L) < ALERT_COOLDOWN_MS) return@forEach
            val result = FloatArray(1)
            android.location.Location.distanceBetween(
                latitude, longitude, zone.latitude, zone.longitude, result
            )
            if (result[0] <= APPROACH_BUFFER_METERS) {
                lastAlertedAt[zone.zone_id] = now
                _activeAlert.value = RiskZoneApproachAlert(
                    zone.zone_id,
                    zone.title,
                    zone.road_damage_type,
                    zone.severity,
                    result[0]
                )
                return
            }
        }
    }

    fun dismiss(zoneId: String) {
        if (_activeAlert.value?.zoneId == zoneId) _activeAlert.value = null
    }

    fun showFilmingDemo(type: ApproachVoiceType) {
        val isAccident = type == ApproachVoiceType.ACCIDENT_ZONE
        _activeAlert.value = RiskZoneApproachAlert(
            zoneId = if (isAccident) "filming-demo-accident" else "filming-demo-hazard",
            title = if (isAccident) "사고 기록 구간 접근 중" else "위험구간 접근 중",
            roadDamageType = if (isAccident) "ACCIDENT_HISTORY" else "ROAD_HAZARD",
            severity = "HIGH",
            distanceMeters = FilmingDemoConfig.alertDistanceMeters
        )
    }

    private const val ALERT_COOLDOWN_MS = 30 * 60 * 1000L
    private const val APPROACH_BUFFER_METERS = 15.0
}
