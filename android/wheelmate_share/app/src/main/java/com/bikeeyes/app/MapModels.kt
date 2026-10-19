package com.bikeeyes.app

data class KakaoPlaceItem(
    val id: String,
    val name: String,
    val category_name: String,
    val phone: String,
    val address: String,
    val road_address: String,
    val longitude: Double,
    val latitude: Double,
    val distance_meters: Int?
)

data class KakaoPlaceSearchResponse(
    val query: String,
    val places: List<KakaoPlaceItem>,
    val has_more: Boolean
)

data class KakaoCurrentLocationResponse(
    val name: String,
    val address: String,
    val road_address: String,
    val longitude: Double,
    val latitude: Double
)

data class BicycleRoutePoint(
    val longitude: Double,
    val latitude: Double
)

data class BicycleRouteStep(
    val guidance: String,
    val distance_meters: Int,
    val duration_seconds: Int,
    val longitude: Double,
    val latitude: Double
)

data class BicycleRouteResponse(
    val route_type: String,
    val route_mode: String,
    val total_distance_meters: Int,
    val total_duration_seconds: Int,
    val landing_url: String,
    val points: List<BicycleRoutePoint>,
    val steps: List<BicycleRouteStep>
)

data class HazardZoneItem(
    val zone_id: String,
    val title: String,
    val road_damage_type: String,
    val severity: String,
    val latitude: Double,
    val longitude: Double,
    val radius_meters: Double,
    val detection_count: Int,
    val description: String,
    val last_detected_at: Long,
    val status: String,
    val is_test_data: Boolean,
    val updated_at: Long,
    val zone_category: String = "USER_HAZARD",
    val is_user_visible: Boolean = true,
    val expires_at: Long? = null
)

data class HazardZoneListResponse(
    val zones: List<HazardZoneItem>,
    val data_available: Boolean
)
