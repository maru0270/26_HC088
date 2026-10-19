package com.bikeeyes.app.ui.screens.route

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.ui.components.BikeEyesLocationCard
import com.bikeeyes.app.ui.components.BikeEyesPrimaryButton
import com.bikeeyes.app.ui.components.BikeEyesKakaoMap
import com.bikeeyes.app.ui.components.BikeEyesMapMarker
import com.bikeeyes.app.ui.components.BikeEyesMapPoint
import com.bikeeyes.app.ui.theme.BikeEyesGreen
import com.bikeeyes.app.NavigationRouteState

@Composable
fun RouteSearchResultScreen(
    onBackClick: () -> Unit = {},
    onLocationClick: () -> Unit = {},
    onStartNavigationClick: () -> Unit = {}
) {
    val activeRoute by NavigationRouteState.activeRoute.collectAsState()
    val destination = activeRoute?.destination
    val route = activeRoute?.route
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp)
    ) {
        RouteResultTopArea(
            onBackClick = onBackClick
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "경로 검색 결과",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(18.dp))

        RouteResultMap()

        Spacer(modifier = Modifier.height(18.dp))

        if (destination != null && route != null) {
            BikeEyesLocationCard(
                title = destination.name,
                subtitle = "${formatRouteDistance(route.total_distance_meters)} · ${
                    formatRouteDuration(route.total_duration_seconds)
                } · ${routeTypeLabel(route.route_type)} · ${routeModeLabel(route.route_mode)}",
                onClick = onLocationClick
            )
            if (route.route_type == "WALKING_BIKE") {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = androidx.compose.ui.graphics.Color(0xFFFFF4E5)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            "보행로·계단·자전거 통행 제한 구간을 포함할 수 있습니다. " +
                                "통행이 허용되지 않은 구간에서는 자전거에서 내려 이동하세요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = androidx.compose.ui.graphics.Color(0xFF9A5A00)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "예상 시간은 자전거 평균속도 15km/h 기준이며, 끌기 구간에 따라 더 걸릴 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = androidx.compose.ui.graphics.Color(0xFF9A5A00)
                        )
                    }
                }
            }
            activeRoute?.riskAnalysis?.let { analysis ->
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = androidx.compose.ui.graphics.Color(0xFFEFF6FF)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            if (analysis.fallbackUsed) {
                                "위험 데이터가 없어 기본 자전거 경로를 안내합니다."
                            } else {
                                "위험 회피 경로 · 후보 ${analysis.evaluatedCandidateCount}개 비교"
                            },
                            fontWeight = FontWeight.Bold,
                            color = androidx.compose.ui.graphics.Color(0xFF1D4ED8)
                        )
                        if (!analysis.fallbackUsed) {
                            Text(
                                "경로와 겹치는 위험구간 ${analysis.intersectingZoneCount}개 · " +
                                    "위험 점수 ${String.format("%.1f", analysis.riskScore)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (analysis.isMockData) {
                            Text(
                                "현재 결과는 개발용 Mock 위험구간 기준이며 실제 안전을 보장하지 않습니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = androidx.compose.ui.graphics.Color(0xFFB45309)
                            )
                        }
                    }
                }
            }
        } else {
            Text("선택된 경로가 없습니다.", color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(24.dp))

        BikeEyesPrimaryButton(
            text = "경로 안내 시작",
            onClick = onStartNavigationClick
        )
    }
}

@Composable
private fun RouteResultTopArea(
    onBackClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBackClick
        ) {
            Text(
                text = "‹",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "BikeEyes",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = BikeEyesGreen
        )

        Spacer(modifier = Modifier.weight(1f))

        Spacer(modifier = Modifier.size(48.dp))
    }
}

@Composable
private fun RouteResultMap() {
    val activeRoute by NavigationRouteState.activeRoute.collectAsState()
    val points = activeRoute?.route?.points.orEmpty()
    val destination = activeRoute?.destination
    BikeEyesKakaoMap(
        modifier = Modifier
            .fillMaxWidth()
            .height(290.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp)),
        initialLatitude = points.firstOrNull()?.latitude,
        initialLongitude = points.firstOrNull()?.longitude,
        markers = buildList {
            points.firstOrNull()?.let {
                add(BikeEyesMapMarker("route-start", it.latitude, it.longitude, android.graphics.Color.rgb(37, 99, 235)))
            }
            destination?.let {
                add(BikeEyesMapMarker("route-end", it.latitude, it.longitude, android.graphics.Color.rgb(220, 38, 38)))
            }
        },
        routePoints = points.map { BikeEyesMapPoint(it.latitude, it.longitude) }
    )
}

private fun formatRouteDistance(meters: Int): String =
    if (meters < 1000) "${meters}m" else String.format("%.1fkm", meters / 1000.0)

private fun formatRouteDuration(seconds: Int): String {
    val minutes = (seconds / 60).coerceAtLeast(1)
    return if (minutes < 60) "약 ${minutes}분" else "약 ${minutes / 60}시간 ${minutes % 60}분"
}

private fun routeModeLabel(mode: String): String = when (mode) {
    "SHORTEST" -> "최단거리"
    "ACCESSIBLE" -> "평지 우선"
    "BROAD_FIRST" -> "넓은 길 우선"
    else -> "자전거도로 우선"
}

private fun routeTypeLabel(type: String): String =
    if (type == "WALKING_BIKE") "보행로 포함" else "자전거 경로"
