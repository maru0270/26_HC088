package com.bikeeyes.app.ui.screens.ride

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.RideTrackingState
import com.bikeeyes.app.BuildConfig
import com.bikeeyes.app.FilmingDemoConfig
import com.bikeeyes.app.BikeEyesLocalDatabase
import com.bikeeyes.app.NavigationRouteState
import com.bikeeyes.app.NetworkModule
import com.bikeeyes.app.RiskAwareRoutePlanner
import com.bikeeyes.app.ui.components.BikeEyesKakaoMap
import com.bikeeyes.app.ui.components.BikeEyesMapMarker
import com.bikeeyes.app.ui.components.BikeEyesMapLocation
import com.bikeeyes.app.ui.components.BikeEyesMapPoint
import com.bikeeyes.app.ui.theme.BikeEyesGreen
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun RideInProgressScreen(
    onPauseRideClick: () -> Unit = {},
    onResumeRideClick: () -> Unit = {},
    onFinishRideClick: () -> Unit = {},
    onDiscardRideClick: () -> Unit = {}
) {
    val elapsedSeconds by RideTrackingState.elapsedSeconds.collectAsState()
    val currentSpeedKmh by RideTrackingState.currentSpeedKmh.collectAsState()
    val totalDistanceMeters by RideTrackingState.totalDistanceMeters.collectAsState()
    val isPaused by RideTrackingState.isPaused.collectAsState()
    val frameCount by RideTrackingState.collectedFrameCount.collectAsState()
    val accidentCount by RideTrackingState.accidentCount.collectAsState()
    val gpsAccuracy by RideTrackingState.gpsAccuracyMeters.collectAsState()
    val cameraReady by RideTrackingState.cameraReady.collectAsState()
    val lowLightDetected by RideTrackingState.lowLightDetected.collectAsState()
    val pendingUploads by RideTrackingState.pendingUploadCount.collectAsState()
    val failedUploads by RideTrackingState.failedUploadCount.collectAsState()
    val serverReachable by RideTrackingState.serverReachable.collectAsState()
    val lastSyncError by RideTrackingState.lastSyncError.collectAsState()
    val activeRoute by NavigationRouteState.activeRoute.collectAsState()
    var navigationLatitude by remember { mutableStateOf<Double?>(null) }
    var navigationLongitude by remember { mutableStateOf<Double?>(null) }
    var navigationHeading by remember { mutableStateOf<Float?>(null) }
    var navigationStepIndex by remember { mutableIntStateOf(0) }
    var isOffRoute by remember { mutableStateOf(false) }
    var isRerouting by remember { mutableStateOf(false) }
    var lastRerouteAt by remember { mutableLongStateOf(0L) }
    var regularOffRouteCount by remember { mutableIntStateOf(0) }
    var obviousOffRouteCount by remember { mutableIntStateOf(0) }
    var recentRouteDistances by remember { mutableStateOf<List<Double>>(emptyList()) }
    var rerouteMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showFinishDialog by remember { mutableStateOf(false) }
    var showLowMovementDialog by remember { mutableStateOf(false) }
    var filmingElapsedMs by remember { mutableLongStateOf(0L) }
    val filmingDemoEnabled = BuildConfig.DEBUG && FilmingDemoConfig.enabled
    val filmingRoutePoints = remember {
        FilmingDemoConfig.routePoints.map { BikeEyesMapPoint(it.latitude, it.longitude) }
    }
    val filmingPosition = if (filmingDemoEnabled) {
        interpolateRoutePosition(
            filmingRoutePoints,
            FilmingDemoConfig.distanceMetersAt(filmingElapsedMs)
        )
    } else {
        null
    }

    LaunchedEffect(filmingDemoEnabled) {
        if (!filmingDemoEnabled) return@LaunchedEffect
        val startedAt = android.os.SystemClock.elapsedRealtime()
        while (true) {
            filmingElapsedMs = android.os.SystemClock.elapsedRealtime() - startedAt
            delay(250L)
        }
    }

    val displayedSpeedKmh = if (filmingDemoEnabled) {
        FilmingDemoConfig.displaySpeedKmhAt(filmingElapsedMs)
    } else {
        currentSpeedKmh
    }

    val displayedDistanceMeters = if (filmingDemoEnabled) {
        FilmingDemoConfig.distanceMetersAt(filmingElapsedMs)
    } else {
        totalDistanceMeters
    }

    BackHandler { showFinishDialog = true }

    LaunchedEffect(navigationLatitude, navigationLongitude, activeRoute) {
        val latitude = navigationLatitude ?: return@LaunchedEffect
        val longitude = navigationLongitude ?: return@LaunchedEffect
        val route = activeRoute?.route ?: return@LaunchedEffect
        route.steps.getOrNull(navigationStepIndex)?.let { step ->
            if (distanceMeters(latitude, longitude, step.latitude, step.longitude) <= 30.0) {
                navigationStepIndex =
                    (navigationStepIndex + 1).coerceAtMost(route.steps.lastIndex)
            }
        }

        val routeMatch = closestRouteMatch(latitude, longitude, route.points)
            ?: return@LaunchedEffect
        val currentAccuracy = gpsAccuracy?.toDouble()
        val hasReliableGps = currentAccuracy != null && currentAccuracy <= MAX_REROUTE_GPS_ACCURACY_METERS
        val isMoving = currentSpeedKmh >= MIN_REROUTE_SPEED_KMH
        if (!hasReliableGps || !isMoving) {
            regularOffRouteCount = 0
            obviousOffRouteCount = 0
            recentRouteDistances = emptyList()
            isOffRoute = false
            return@LaunchedEffect
        }

        val regularThreshold = max(
            MIN_REGULAR_OFF_ROUTE_METERS,
            currentAccuracy!! * GPS_ACCURACY_DISTANCE_MULTIPLIER
        )
        val obviousThreshold = max(
            MIN_OBVIOUS_OFF_ROUTE_METERS,
            currentAccuracy * OBVIOUS_GPS_ACCURACY_MULTIPLIER
        )
        val exceedsRegularDistance = routeMatch.distanceMeters >= regularThreshold
        val exceedsObviousDistance = routeMatch.distanceMeters >= obviousThreshold

        recentRouteDistances = if (exceedsRegularDistance) {
            (recentRouteDistances + routeMatch.distanceMeters).takeLast(DISTANCE_TREND_SAMPLE_COUNT)
        } else {
            emptyList()
        }
        regularOffRouteCount = if (exceedsRegularDistance) regularOffRouteCount + 1 else 0
        obviousOffRouteCount = if (exceedsObviousDistance) obviousOffRouteCount + 1 else 0

        val headingMismatch = navigationHeading?.let { heading ->
            angularDifferenceDegrees(heading.toDouble(), routeMatch.segmentBearingDegrees) >=
                HEADING_MISMATCH_DEGREES
        } ?: false
        val movingAway = isDistanceIncreasing(recentRouteDistances)
        val regularReroute = regularOffRouteCount >= REGULAR_OFF_ROUTE_REQUIRED_COUNT &&
            (headingMismatch || movingAway)
        val obviousReroute = obviousOffRouteCount >= OBVIOUS_OFF_ROUTE_REQUIRED_COUNT

        isOffRoute = exceedsRegularDistance
        if (
            (!regularReroute && !obviousReroute) ||
            isRerouting ||
            System.currentTimeMillis() - lastRerouteAt < REROUTE_COOLDOWN_MS
        ) return@LaunchedEffect

        isRerouting = true
        lastRerouteAt = System.currentTimeMillis()
        rerouteMessage = null
        val latestRoute = activeRoute ?: return@LaunchedEffect
        scope.launch {
            runCatching {
                val api = NetworkModule.dynamicSensorApi(context)
                if (latestRoute.strategy == "RISK_AVOID") {
                    RiskAwareRoutePlanner.plan(
                        api = api,
                        riskZoneDao = BikeEyesLocalDatabase.getInstance(context).riskZoneDao(),
                        startLongitude = longitude,
                        startLatitude = latitude,
                        endLongitude = latestRoute.destination.longitude,
                        endLatitude = latestRoute.destination.latitude,
                        destinationName = latestRoute.destination.name
                    )
                } else if (latestRoute.route.route_type == "WALKING_BIKE") {
                    api.getWalkingBikeRoute(
                        startLongitude = longitude,
                        startLatitude = latitude,
                        endLongitude = latestRoute.destination.longitude,
                        endLatitude = latestRoute.destination.latitude,
                        destinationName = latestRoute.destination.name,
                        routeMode = latestRoute.route.route_mode
                    ).let { com.bikeeyes.app.RiskAwareRoutePlan(it, latestRoute.riskAnalysis
                        ?: com.bikeeyes.app.RouteRiskAnalysis(false, false, 0, 0.0, 1, false)) }
                } else {
                    api.getBicycleRoute(
                        startLongitude = longitude,
                        startLatitude = latitude,
                        endLongitude = latestRoute.destination.longitude,
                        endLatitude = latestRoute.destination.latitude,
                        destinationName = latestRoute.destination.name,
                        routeMode = latestRoute.route.route_mode
                    ).let { com.bikeeyes.app.RiskAwareRoutePlan(it, latestRoute.riskAnalysis
                        ?: com.bikeeyes.app.RouteRiskAnalysis(false, false, 0, 0.0, 1, false)) }
                }
            }.onSuccess { plan ->
                NavigationRouteState.set(
                    latestRoute.destination,
                    plan.route,
                    latestRoute.strategy,
                    if (latestRoute.strategy == "RISK_AVOID") plan.analysis else latestRoute.riskAnalysis
                )
                navigationStepIndex = 0
                isOffRoute = false
                regularOffRouteCount = 0
                obviousOffRouteCount = 0
                recentRouteDistances = emptyList()
                rerouteMessage = "현재 위치에서 경로를 다시 찾았습니다."
            }.onFailure {
                rerouteMessage = if (serverReachable) {
                    "경로 재탐색에 실패했습니다. 잠시 후 다시 시도합니다."
                } else {
                    "서버에 연결할 수 없어 경로를 다시 찾지 못했습니다."
                }
            }
            isRerouting = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("주행 중", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (isPaused) "수집이 일시정지되었습니다" else "노면 데이터 수집 중",
                    color = if (isPaused) Color(0xFFF59E0B) else BikeEyesGreen
                )
            }
            StatusBadge(if (isPaused) "일시정지" else "수집 중", !isPaused)
        }

        LiveStatsCard(
            speed = String.format(
                Locale.KOREA,
                "%.1f km/h",
                displayedSpeedKmh
            ),
            distance = String.format(
                Locale.KOREA,
                if (filmingDemoEnabled) "%.3f km" else "%.2f km",
                displayedDistanceMeters / 1000.0
            ),
            time = formatRideTime(elapsedSeconds)
        )

        activeRoute?.route?.steps?.getOrNull(navigationStepIndex)?.let { currentStep ->
            Text(
                text = when {
                    isRerouting -> "경로를 다시 찾는 중..."
                    isOffRoute -> "경로에서 벗어났습니다"
                    else -> currentStep.guidance
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        rerouteMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it.contains("실패") || it.contains("연결할 수 없어")) {
                    MaterialTheme.colorScheme.error
                } else {
                    BikeEyesGreen
                },
                maxLines = 2
            )
        }

        if (activeRoute?.route?.route_type == "WALKING_BIKE") {
            Text(
                text = "보행로 포함 경로 · 통행 제한 구간에서는 내려서 이동",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFB45309),
                maxLines = 1
            )
        }
        if (activeRoute?.strategy == "RISK_AVOID") {
            Text(
                text = if (activeRoute?.riskAnalysis?.isMockData == true) {
                    "Mock 위험 회피 경로 · 실제 안전을 보장하지 않음"
                } else {
                    "위험 회피 경로 안내 중"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFB45309),
                maxLines = 1
            )
        }

        BikeEyesKakaoMap(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .heightIn(min = 140.dp, max = 230.dp)
                .clip(RoundedCornerShape(18.dp)),
            showCurrentLocation = true,
            followCurrentLocation = true,
            // 촬영용 이동도 실제 내비게이션과 동일하게 진행 방향을 화면 위쪽으로 둡니다.
            headingUp = true,
            simulatedLocation = filmingPosition?.let {
                BikeEyesMapLocation(
                    latitude = it.point.latitude,
                    longitude = it.point.longitude,
                    accuracyMeters = 5f,
                    speedMetersPerSecond =
                        FilmingDemoConfig.speedMetersPerSecondAt(filmingElapsedMs),
                    headingDegrees = it.headingDegrees
                )
            },
            markers = if (filmingDemoEnabled) {
                buildList {
                    add(
                        BikeEyesMapMarker(
                            id = "filming-hazard-zone",
                            latitude = FilmingDemoConfig.hazardMarker.latitude,
                            longitude = FilmingDemoConfig.hazardMarker.longitude,
                            color = android.graphics.Color.rgb(220, 38, 38)
                        )
                    )
                    add(
                        BikeEyesMapMarker(
                            id = "filming-accident-zone",
                            latitude = FilmingDemoConfig.accidentMarker.latitude,
                            longitude = FilmingDemoConfig.accidentMarker.longitude,
                            color = android.graphics.Color.rgb(245, 158, 11)
                        )
                    )
                    FilmingDemoConfig.routePoints.lastOrNull()?.let {
                        add(
                            BikeEyesMapMarker(
                                id = "filming-destination",
                                latitude = it.latitude,
                                longitude = it.longitude,
                                color = android.graphics.Color.rgb(37, 99, 235)
                            )
                        )
                    }
                }
            } else activeRoute?.destination?.let {
                listOf(
                    BikeEyesMapMarker(
                        id = "navigation-destination",
                        latitude = it.latitude,
                        longitude = it.longitude,
                        color = android.graphics.Color.rgb(220, 38, 38)
                    )
                )
            }.orEmpty(),
            routePoints = if (filmingDemoEnabled) {
                filmingRoutePoints
            } else {
                activeRoute?.route?.points.orEmpty().map {
                    BikeEyesMapPoint(it.latitude, it.longitude)
                }
            },
            onLocationChanged = { latitude, longitude ->
                navigationLatitude = latitude
                navigationLongitude = longitude
            },
            onLocationDetailsChanged = { location: BikeEyesMapLocation ->
                navigationHeading = location.headingDegrees
            }
        )

        Text("수집 상태", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        StatusGrid(
            gpsAccuracy = gpsAccuracy,
            cameraReady = cameraReady,
            serverReachable = serverReachable,
            pendingUploads = pendingUploads,
            failedUploads = failedUploads
        )

        if (lowLightDetected) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4E5)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "주변이 어두워 노면 이미지 품질이 낮을 수 있습니다. 카메라 방향과 조명을 확인해 주세요.",
                    modifier = Modifier.padding(8.dp),
                    color = Color(0xFF9A5A00)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MiniStat("수집 묶음", "${frameCount}개", Modifier.weight(1f))
            MiniStat("사고 기록", "${accidentCount}건", Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    if (isPaused) onResumeRideClick() else onPauseRideClick()
                }
            ) {
                Text(if (isPaused) "주행 재개" else "일시정지")
            }
            Button(
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = BikeEyesGreen),
                onClick = { showFinishDialog = true }
            ) {
                Text("주행 종료")
            }
        }
    }

    if (showFinishDialog) {
        AlertDialog(
            onDismissRequest = { showFinishDialog = false },
            title = { Text("주행을 종료할까요?") },
            text = { Text("종료하면 현재 주행 결과를 저장하고 요약 화면으로 이동합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    showFinishDialog = false
                    val averageMetersPerSecond =
                        if (elapsedSeconds > 0) totalDistanceMeters / elapsedSeconds else 0.0
                    if (averageMetersPerSecond <= 1.0) {
                        showLowMovementDialog = true
                    } else {
                        onFinishRideClick()
                    }
                }) { Text("종료") }
            },
            dismissButton = {
                TextButton(onClick = { showFinishDialog = false }) { Text("계속 주행") }
            }
        )
    }
    if (showLowMovementDialog) {
        AlertDialog(
            onDismissRequest = { showLowMovementDialog = false },
            title = { Text("이동이 거의 없는 주행입니다") },
            text = { Text("평균 이동속도가 초당 1m 이하입니다. 이 주행 기록을 저장할까요?") },
            confirmButton = {
                TextButton(onClick = {
                    showLowMovementDialog = false
                    onFinishRideClick()
                }) { Text("저장") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLowMovementDialog = false
                    onDiscardRideClick()
                }) { Text("기록 삭제") }
            }
        )
    }
}

@Composable
private fun LiveStatsCard(speed: String, distance: String, time: String) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Stat("현재 속도", speed)
            Stat("이동 거리", distance)
            Stat("이동 시간", time)
        }
    }
}

@Composable
private fun StatusGrid(
    gpsAccuracy: Double?,
    cameraReady: Boolean,
    serverReachable: Boolean,
    pendingUploads: Int,
    failedUploads: Int
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val gpsGood = gpsAccuracy != null && gpsAccuracy <= 50.0
            StatusTile("GPS", gpsAccuracy?.let { "±${it.toInt()}m" } ?: "탐색 중", gpsGood, Modifier.weight(1f))
            StatusTile("카메라", if (cameraReady) "준비됨" else "확인 필요", cameraReady, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusTile("서버", if (serverReachable) "연결됨" else "오프라인", serverReachable, Modifier.weight(1f))
            val queueOk = failedUploads == 0
            StatusTile("전송 대기", "${pendingUploads}건", queueOk, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ErrorCard(
    gpsAccuracy: Double?,
    cameraReady: Boolean,
    serverReachable: Boolean,
    failedUploads: Int,
    lastSyncError: String?
) {
    val messages = buildList {
        if (gpsAccuracy == null) add("GPS 위치를 수신하고 있습니다.")
        else if (gpsAccuracy > 50.0) add("GPS 정확도가 낮습니다. 하늘이 보이는 곳에서 확인하세요.")
        if (!cameraReady) add("카메라를 준비하지 못했습니다.")
        if (!serverReachable) add("서버에 연결할 수 없어 단말에 보관 중입니다.")
        if (failedUploads > 0) add("전송 실패 데이터 ${failedUploads}건을 재시도합니다.")
        if (!serverReachable && !lastSyncError.isNullOrBlank()) add(lastSyncError)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("확인 필요", fontWeight = FontWeight.Bold, color = Color(0xFFC2410C))
            messages.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun StatusTile(title: String, value: String, good: Boolean, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            Text(value, fontWeight = FontWeight.Bold, color = if (good) BikeEyesGreen else Color(0xFFDC2626))
        }
    }
}

@Composable
private fun StatusBadge(text: String, good: Boolean) {
    Text(
        text = text,
        modifier = Modifier
            .background(
                if (good) Color(0xFFE7F7ED) else Color(0xFFFFF3CD),
                RoundedCornerShape(99.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = if (good) BikeEyesGreen else Color(0xFFB45309),
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun Stat(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MiniStat(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
            Text(value, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatRideTime(totalSeconds: Long): String {
    val hours = totalSeconds / 3600L
    val minutes = totalSeconds % 3600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
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
    val a = sin(deltaLat / 2).pow(2) +
        cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
    return earthRadius * 2 * atan2(sqrt(a), sqrt(1 - a))
}

private data class RouteMatch(
    val distanceMeters: Double,
    val segmentBearingDegrees: Double
)

private fun closestRouteMatch(
    latitude: Double,
    longitude: Double,
    routePoints: List<com.bikeeyes.app.BicycleRoutePoint>
): RouteMatch? {
    if (routePoints.size < 2) return null
    val metersPerLatitudeDegree = 111_320.0
    val metersPerLongitudeDegree = metersPerLatitudeDegree * cos(Math.toRadians(latitude))
    var closestDistance = Double.POSITIVE_INFINITY
    var closestBearing = 0.0

    routePoints.zipWithNext().forEach { (start, end) ->
        val startX = (start.longitude - longitude) * metersPerLongitudeDegree
        val startY = (start.latitude - latitude) * metersPerLatitudeDegree
        val endX = (end.longitude - longitude) * metersPerLongitudeDegree
        val endY = (end.latitude - latitude) * metersPerLatitudeDegree
        val segmentX = endX - startX
        val segmentY = endY - startY
        val lengthSquared = segmentX * segmentX + segmentY * segmentY
        val projection = if (lengthSquared <= 0.0001) {
            0.0
        } else {
            (-(startX * segmentX + startY * segmentY) / lengthSquared).coerceIn(0.0, 1.0)
        }
        val closestX = startX + projection * segmentX
        val closestY = startY + projection * segmentY
        val distance = sqrt(closestX * closestX + closestY * closestY)
        if (distance < closestDistance) {
            closestDistance = distance
            closestBearing = bearingDegrees(
                start.latitude,
                start.longitude,
                end.latitude,
                end.longitude
            )
        }
    }
    return RouteMatch(closestDistance, closestBearing)
}

private fun bearingDegrees(
    startLatitude: Double,
    startLongitude: Double,
    endLatitude: Double,
    endLongitude: Double
): Double {
    val startLat = Math.toRadians(startLatitude)
    val endLat = Math.toRadians(endLatitude)
    val deltaLongitude = Math.toRadians(endLongitude - startLongitude)
    val y = sin(deltaLongitude) * cos(endLat)
    val x = cos(startLat) * sin(endLat) -
        sin(startLat) * cos(endLat) * cos(deltaLongitude)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

private data class FilmingRoutePosition(
    val point: BikeEyesMapPoint,
    val headingDegrees: Float
)

private fun interpolateRoutePosition(
    points: List<BikeEyesMapPoint>,
    traveledMeters: Double
): FilmingRoutePosition? {
    if (points.isEmpty()) return null
    if (points.size == 1) return FilmingRoutePosition(points.first(), 0f)
    var remainingMeters = traveledMeters.coerceAtLeast(0.0)
    points.zipWithNext().forEach { (start, end) ->
        val segmentMeters = distanceMeters(
            start.latitude,
            start.longitude,
            end.latitude,
            end.longitude
        )
        if (remainingMeters <= segmentMeters) {
            val segmentProgress = if (segmentMeters <= 0.0) 0.0 else remainingMeters / segmentMeters
            return FilmingRoutePosition(
                point = BikeEyesMapPoint(
                    latitude = start.latitude + (end.latitude - start.latitude) * segmentProgress,
                    longitude = start.longitude + (end.longitude - start.longitude) * segmentProgress
                ),
                headingDegrees = bearingDegrees(
                    start.latitude,
                    start.longitude,
                    end.latitude,
                    end.longitude
                ).toFloat()
            )
        }
        remainingMeters -= segmentMeters
    }
    val last = points.last()
    val previous = points[points.lastIndex - 1]
    return FilmingRoutePosition(
        point = last,
        headingDegrees = bearingDegrees(
            previous.latitude,
            previous.longitude,
            last.latitude,
            last.longitude
        ).toFloat()
    )
}

private fun angularDifferenceDegrees(first: Double, second: Double): Double =
    abs(((first - second + 540.0) % 360.0) - 180.0)

private fun isDistanceIncreasing(distances: List<Double>): Boolean {
    if (distances.size < DISTANCE_TREND_SAMPLE_COUNT) return false
    return distances.zipWithNext().all { (previous, current) ->
        current >= previous + MIN_DISTANCE_INCREASE_METERS
    }
}

private const val MAX_REROUTE_GPS_ACCURACY_METERS = 25.0
private const val MIN_REROUTE_SPEED_KMH = 3.0
private const val MIN_REGULAR_OFF_ROUTE_METERS = 20.0
private const val GPS_ACCURACY_DISTANCE_MULTIPLIER = 1.5
private const val REGULAR_OFF_ROUTE_REQUIRED_COUNT = 3
private const val HEADING_MISMATCH_DEGREES = 60.0
private const val DISTANCE_TREND_SAMPLE_COUNT = 3
private const val MIN_DISTANCE_INCREASE_METERS = 2.0
private const val MIN_OBVIOUS_OFF_ROUTE_METERS = 40.0
private const val OBVIOUS_GPS_ACCURACY_MULTIPLIER = 2.0
private const val OBVIOUS_OFF_ROUTE_REQUIRED_COUNT = 2
private const val REROUTE_COOLDOWN_MS = 20_000L
