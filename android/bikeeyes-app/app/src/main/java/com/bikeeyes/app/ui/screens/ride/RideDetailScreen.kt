package com.bikeeyes.app.ui.screens.ride

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.BikeEyesLocalDatabase
import com.bikeeyes.app.LocalSyncLogEntity
import com.bikeeyes.app.RideResultEntity
import com.bikeeyes.app.RideServerSyncRepository
import com.bikeeyes.app.ServerRideDetail
import com.bikeeyes.app.ServerSensorLog
import com.bikeeyes.app.SensorLogRequest
import com.bikeeyes.app.ui.theme.BikeEyesBorderGray
import com.bikeeyes.app.ui.theme.BikeEyesGreen
import com.bikeeyes.app.NetworkModule
import com.bikeeyes.app.SensorUploadRepository
import com.bikeeyes.app.AuthRepository
import com.bikeeyes.app.AuthSessionStore
import com.bikeeyes.app.ui.components.BikeEyesKakaoMap
import com.bikeeyes.app.ui.components.BikeEyesMapMarker
import com.bikeeyes.app.ui.components.BikeEyesMapPoint
import kotlinx.coroutines.launch
import com.google.gson.Gson
import java.util.Locale

private data class RoutePoint(
    val capturedAt: Long,
    val latitude: Double,
    val longitude: Double
)

@Composable
fun RideDetailScreen(
    sessionId: String,
    onRoadRecordsClick: () -> Unit,
    onAccidentRecordsClick: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val database = BikeEyesLocalDatabase.getInstance(context)
    val scope = rememberCoroutineScope()
    val repository = remember(database) {
        SensorUploadRepository(
            NetworkModule.dynamicSensorApi(context),
            database.localSyncLogDao(),
            database.rideResultDao(),
            database.accidentEventDao(),
            AuthRepository(context),
            AuthSessionStore(context),
            database.rideDeletionDao()
        )
    }
    val serverRepository = remember { RideServerSyncRepository(context) }
    var confirmDelete by remember { mutableStateOf(false) }
    var serverDetail by remember { mutableStateOf<ServerRideDetail?>(null) }
    var serverSensorLogs by remember { mutableStateOf<List<ServerSensorLog>>(emptyList()) }
    var serverLoadError by remember { mutableStateOf<String?>(null) }
    val ride by database.rideResultDao().observeBySessionId(sessionId).collectAsState(initial = null)
    val sensorLogs by database.localSyncLogDao()
        .observeSensorLogsBySession(sessionId)
        .collectAsState(initial = emptyList())
    val accidents by database.accidentEventDao()
        .observeByRideSession(sessionId)
        .collectAsState(initial = emptyList())
    LaunchedEffect(sessionId) {
        serverLoadError = null
        val detailResult = runCatching {
            serverRepository.getRideDetail(sessionId)
        }.onSuccess {
            serverDetail = it
        }
        val sensorResult = runCatching {
            serverRepository.getImageSensorLogs(sessionId)
        }.onSuccess {
            serverSensorLogs = it
        }
        (detailResult.exceptionOrNull() ?: sensorResult.exceptionOrNull())?.let {
            serverLoadError = AuthRepository.userMessage(it)
        }
    }
    val localRoutePoints = remember(sensorLogs) { sensorLogs.toLocalRoutePoints() }
    val serverRoutePoints = remember(serverDetail) { serverDetail.toRoutePoints() }
    val serverSensorRoutePoints = remember(serverSensorLogs) {
        serverSensorLogs.toServerSensorRoutePoints()
    }
    val routePoints = remember(
        localRoutePoints,
        serverRoutePoints,
        serverSensorRoutePoints
    ) {
        serverRoutePoints.ifEmpty {
            localRoutePoints.ifEmpty { serverSensorRoutePoints }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("주행 기록 상세", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        ride?.let {
            RideDetailContent(
                ride = it,
                routePoints = routePoints,
                actualCollectionCount = maxOf(sensorLogs.size, serverSensorLogs.size),
                actualAccidentCount = accidents.size,
                serverSensorLogs = serverSensorLogs,
                onRoadRecordsClick = onRoadRecordsClick,
                onAccidentRecordsClick = onAccidentRecordsClick,
                onDeleteClick = { confirmDelete = true }
            )
        } ?: Text("주행 기록을 불러오는 중입니다.", color = Color(0xFF667085))
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("주행 기록을 삭제할까요?") },
            text = {
                Text("주행 결과와 GPS 경로는 삭제됩니다. 수집 센서·이미지·사고 데이터는 비식별 상태로 유지됩니다.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        scope.launch {
                            repository.deleteRide(sessionId)
                            onDeleted()
                        }
                    }
                ) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("취소") }
            }
        )
    }
}

@Composable
private fun RideDetailContent(
    ride: RideResultEntity,
    routePoints: List<RoutePoint>,
    actualCollectionCount: Int,
    actualAccidentCount: Int,
    serverSensorLogs: List<ServerSensorLog>,
    onRoadRecordsClick: () -> Unit,
    onAccidentRecordsClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Text(formatRideDate(ride.started_at), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(
        "${formatRideDate(ride.started_at)} ~ ${formatRideDate(ride.ended_at)}",
        color = Color(0xFF667085),
        style = MaterialTheme.typography.bodySmall
    )

    RoutePreview(routePoints)

    val values = listOf(
        "주행 거리" to formatDistance(ride.total_distance_meters),
        "전체 이동시간" to formatDuration(ride.elapsed_seconds),
        "평균 속도" to String.format(Locale.KOREA, "%.1f km/h", ride.average_speed_kmh),
        "최고 속도" to String.format(Locale.KOREA, "%.1f km/h", ride.max_speed_kmh),
        "노면 수집" to "${maxOf(ride.collected_frame_count, actualCollectionCount)}개",
        "사고 기록" to "${maxOf(ride.accident_count, actualAccidentCount)}건"
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        values.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (label, value) ->
                    DetailCard(label, value, Modifier.weight(1f))
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF7EF))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(syncStatusText(ride.sync_status), color = BikeEyesGreen, fontWeight = FontWeight.Bold)
        }
    }

    val anomalyCounts = serverSensorLogs
        .flatMap { it.road_anomaly_detections }
        .groupingBy { roadAnomalyClassText(it.class_name) }
        .eachCount()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        border = BorderStroke(1.dp, BikeEyesBorderGray)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("수집된 노면 판독 결과", fontWeight = FontWeight.Bold)
            if (anomalyCounts.isEmpty()) {
                Text(
                    "AI 신뢰도 기준을 충족한 노면 판독 결과가 없습니다.",
                    color = Color(0xFF667085),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                anomalyCounts.toSortedMap().forEach { (name, count) ->
                    Text("$name ${count}건", color = Color(0xFF475467))
                }
            }
            Text(
                "이 결과는 자신의 주행에서 수집된 판독 기록이며 사용자 위험요소 확정 여부와는 별개입니다.",
                color = Color(0xFF667085),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    Button(onClick = onRoadRecordsClick, modifier = Modifier.fillMaxWidth()) {
        Text("수집된 노면 판독 결과 상세 보기 ($actualCollectionCount)")
    }
    OutlinedButton(onClick = onAccidentRecordsClick, modifier = Modifier.fillMaxWidth()) {
        Text("이 주행의 사고 기록 보기 ($actualAccidentCount)")
    }
    TextButton(onClick = onDeleteClick, modifier = Modifier.fillMaxWidth()) {
        Text("주행 기록 삭제", color = Color(0xFFD92D20))
    }
}

private fun roadAnomalyClassText(className: String) = when (className.lowercase()) {
    "pothole" -> "포트홀"
    "manhole" -> "맨홀"
    "flood_area" -> "물웅덩이"
    "bollard" -> "볼라드"
    "linear" -> "선형 균열"
    "alligator" -> "거북등 균열"
    else -> className
}

@Composable
private fun RoutePreview(points: List<RoutePoint>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF7EF)),
        shape = RoundedCornerShape(16.dp)
    ) {
        if (points.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "이 주행에 저장된 GPS 경로가 없습니다.",
                    color = Color(0xFF667085)
                )
            }
        } else {
            val mapPoints = points.map { BikeEyesMapPoint(it.latitude, it.longitude) }
            val markers = buildList {
                points.firstOrNull()?.let {
                    add(BikeEyesMapMarker("start", it.latitude, it.longitude, android.graphics.Color.rgb(37, 99, 235)))
                }
                points.lastOrNull()?.let {
                    add(BikeEyesMapMarker("end", it.latitude, it.longitude, android.graphics.Color.rgb(220, 38, 38)))
                }
            }
            BikeEyesKakaoMap(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp)),
                initialLatitude = points.firstOrNull()?.latitude,
                initialLongitude = points.firstOrNull()?.longitude,
                showCurrentLocation = false,
                markers = markers,
                routePoints = mapPoints
            )
        }
    }
}

@Composable
private fun DetailCard(label: String, value: String, modifier: Modifier) {
    Card(
        modifier = modifier.height(92.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BikeEyesBorderGray),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.Center) {
            Text(label, color = Color(0xFF667085), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(5.dp))
            Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun List<LocalSyncLogEntity>.toLocalRoutePoints(): List<RoutePoint> {
    val gson = Gson()
    return flatMap { log ->
        val request = runCatching {
            gson.fromJson(log.payload, SensorLogRequest::class.java)
        }.getOrNull() ?: return@flatMap emptyList()
        buildList {
            request.sensor_samples
                .filter {
                    it.latitude != null && it.longitude != null &&
                        !(it.latitude == 0.0 && it.longitude == 0.0) &&
                        (it.accuracy == null || it.accuracy <= 50.0)
                }
                .forEach {
                    add(RoutePoint(it.sampled_at, it.latitude!!, it.longitude!!))
                }
            if (
                request.latitude != 0.0 || request.longitude != 0.0
            ) {
                if (request.accuracy <= 50.0) {
                    add(RoutePoint(request.captured_at, request.latitude, request.longitude))
                }
            }
        }
    }.normalizedRoute()
}

private fun ServerRideDetail?.toRoutePoints(): List<RoutePoint> =
    this?.route.orEmpty().mapNotNull { point ->
        val latitude = point.latitude
        val longitude = point.longitude
        if (latitude == null || longitude == null || (latitude == 0.0 && longitude == 0.0)) {
            null
        } else {
            RoutePoint(point.captured_at, latitude, longitude)
        }
    }.normalizedRoute()

private fun List<ServerSensorLog>.toServerSensorRoutePoints(): List<RoutePoint> =
    mapNotNull { point ->
        val latitude = point.latitude
        val longitude = point.longitude
        if (
            latitude == null || longitude == null ||
            (latitude == 0.0 && longitude == 0.0) ||
            (point.accuracy != null && point.accuracy > 50.0)
        ) {
            null
        } else {
            RoutePoint(point.captured_at, latitude, longitude)
        }
    }.normalizedRoute()

private fun List<RoutePoint>.normalizedRoute(): List<RoutePoint> =
    asSequence()
        .sortedBy { it.capturedAt }
        .distinctBy { "${it.capturedAt}:${it.latitude}:${it.longitude}" }
        .toList()
