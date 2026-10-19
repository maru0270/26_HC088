package com.bikeeyes.app

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class RouteRiskAnalysis(
    val dataAvailable: Boolean,
    val isMockData: Boolean,
    val intersectingZoneCount: Int,
    val riskScore: Double,
    val evaluatedCandidateCount: Int,
    val fallbackUsed: Boolean
)

data class RiskAwareRoutePlan(
    val route: BicycleRouteResponse,
    val analysis: RouteRiskAnalysis
)

object RiskAwareRoutePlanner {
    private val candidateModes = listOf("BIKE_ONLY", "SHORTEST", "ACCESSIBLE")

    suspend fun plan(
        api: SensorApi,
        riskZoneDao: RiskZoneDao,
        startLongitude: Double,
        startLatitude: Double,
        endLongitude: Double,
        endLatitude: Double,
        destinationName: String
    ): RiskAwareRoutePlan = coroutineScope {
        val zones = runCatching { riskZoneDao.getAll() }.getOrDefault(emptyList())
        val candidates = candidateModes.map { mode ->
            async {
                runCatching {
                    api.getBicycleRoute(
                        startLongitude = startLongitude,
                        startLatitude = startLatitude,
                        endLongitude = endLongitude,
                        endLatitude = endLatitude,
                        destinationName = destinationName,
                        routeMode = mode
                    )
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()

        if (candidates.isEmpty()) {
            error("No bicycle route candidate")
        }

        if (zones.isEmpty()) {
            val fallback = candidates.firstOrNull { it.route_mode == "BIKE_ONLY" }
                ?: candidates.minBy { it.total_distance_meters }
            return@coroutineScope RiskAwareRoutePlan(
                fallback,
                RouteRiskAnalysis(
                    dataAvailable = false,
                    isMockData = false,
                    intersectingZoneCount = 0,
                    riskScore = 0.0,
                    evaluatedCandidateCount = candidates.size,
                    fallbackUsed = true
                )
            )
        }

        val scored = candidates.map { route ->
            val intersections = zones.filter { zone -> routeIntersectsZone(route, zone) }
            val score = intersections.sumOf { zone ->
                severityWeight(zone.severity) *
                    (1.0 + min(zone.detection_count, 20) / 20.0)
            }
            Triple(route, intersections, score)
        }
        val selected = scored.minWithOrNull(
            compareBy<Triple<BicycleRouteResponse, List<RiskZoneEntity>, Double>> { it.third }
                .thenBy { it.first.total_distance_meters }
        ) ?: error("No scored route candidate")

        RiskAwareRoutePlan(
            route = selected.first,
            analysis = RouteRiskAnalysis(
                dataAvailable = true,
                isMockData = zones.all { it.is_mock },
                intersectingZoneCount = selected.second.size,
                riskScore = selected.third,
                evaluatedCandidateCount = candidates.size,
                fallbackUsed = false
            )
        )
    }

    private fun routeIntersectsZone(
        route: BicycleRouteResponse,
        zone: RiskZoneEntity
    ): Boolean {
        if (route.points.isEmpty()) return false
        val threshold = zone.radius_meters + ROUTE_BUFFER_METERS
        if (route.points.any {
                distanceMeters(it.latitude, it.longitude, zone.latitude, zone.longitude) <= threshold
            }
        ) {
            return true
        }
        return route.points.zipWithNext().any { (start, end) ->
            distanceToSegmentMeters(
                zone.latitude,
                zone.longitude,
                start.latitude,
                start.longitude,
                end.latitude,
                end.longitude
            ) <= threshold
        }
    }

    private fun distanceToSegmentMeters(
        pointLatitude: Double,
        pointLongitude: Double,
        startLatitude: Double,
        startLongitude: Double,
        endLatitude: Double,
        endLongitude: Double
    ): Double {
        val referenceLatitude = Math.toRadians(pointLatitude)
        val metersPerLongitudeDegree = 111_320.0 * cos(referenceLatitude)
        val px = (pointLongitude - startLongitude) * metersPerLongitudeDegree
        val py = (pointLatitude - startLatitude) * 110_540.0
        val ex = (endLongitude - startLongitude) * metersPerLongitudeDegree
        val ey = (endLatitude - startLatitude) * 110_540.0
        val lengthSquared = ex * ex + ey * ey
        if (lengthSquared == 0.0) return sqrt(px * px + py * py)
        val projection = ((px * ex + py * ey) / lengthSquared).coerceIn(0.0, 1.0)
        val dx = px - projection * ex
        val dy = py - projection * ey
        return sqrt(dx * dx + dy * dy)
    }

    private fun distanceMeters(
        latitude1: Double,
        longitude1: Double,
        latitude2: Double,
        longitude2: Double
    ): Double {
        val earthRadius = 6_371_000.0
        val lat1 = Math.toRadians(latitude1)
        val lat2 = Math.toRadians(latitude2)
        val deltaLat = Math.toRadians(latitude2 - latitude1)
        val deltaLon = Math.toRadians(longitude2 - longitude1)
        val value = sin(deltaLat / 2).pow(2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
        return earthRadius * 2 * atan2(sqrt(value), sqrt(max(0.0, 1 - value)))
    }

    private fun severityWeight(severity: String): Double = when (severity.uppercase()) {
        "HIGH" -> 5.0
        "MEDIUM" -> 3.0
        else -> 1.0
    }

    private const val ROUTE_BUFFER_METERS = 15.0
}
