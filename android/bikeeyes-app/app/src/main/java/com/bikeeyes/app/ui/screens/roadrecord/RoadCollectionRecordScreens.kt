package com.bikeeyes.app.ui.screens.roadrecord

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.BikeEyesLocalDatabase
import com.bikeeyes.app.AuthRepository
import com.bikeeyes.app.LocalSyncLogEntity
import com.bikeeyes.app.NetworkModule
import com.bikeeyes.app.RideServerSyncRepository
import com.bikeeyes.app.RoadAnomalyDetection
import com.bikeeyes.app.SensorLogRequest
import com.bikeeyes.app.SensorSample
import com.bikeeyes.app.ServerSensorLog
import com.bikeeyes.app.ui.components.BikeEyesKakaoMap
import com.bikeeyes.app.ui.components.BikeEyesMapMarker
import com.bikeeyes.app.ui.theme.BikeEyesBorderGray
import com.bikeeyes.app.ui.theme.BikeEyesGreen
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

private val gson = Gson()

private data class RoadCollectionRecord(
    val stableKey: String,
    val localRecordId: Long? = null,
    val serverRecordKey: String? = null,
    val eventUuid: String? = null,
    val rideSessionId: String,
    val capturedAt: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val speed: Double,
    val direction: Double,
    val gyroX: Double,
    val gyroY: Double,
    val gyroZ: Double,
    val accelX: Double,
    val accelY: Double,
    val accelZ: Double,
    val geomagneticX: Double,
    val geomagneticY: Double,
    val geomagneticZ: Double,
    val imagePath: String?,
    val sensorSamples: List<SensorSample> = emptyList(),
    val detections: List<RoadAnomalyDetection> = emptyList(),
    val syncStatus: String,
    val retryCount: Int = 0,
    val lastErrorMessage: String? = null
)

@Composable
fun RoadCollectionRecordListScreen(
    sessionId: String? = null,
    onRecordClick: (localRecordId: Long?, serverRecordKey: String?) -> Unit
) {
    val context = LocalContext.current
    val dao = BikeEyesLocalDatabase.getInstance(context).localSyncLogDao()
    val serverRepository = remember(context) { RideServerSyncRepository(context) }
    val logs by (if (sessionId.isNullOrBlank()) {
        dao.observeSensorLogs()
    } else {
        dao.observeSensorLogsBySession(sessionId)
    }).collectAsState(initial = emptyList())
    var serverLogs by remember(sessionId) { mutableStateOf<List<ServerSensorLog>>(emptyList()) }
    var serverLoading by remember(sessionId) { mutableStateOf(!sessionId.isNullOrBlank()) }
    var serverLoadError by remember(sessionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId) {
        if (sessionId.isNullOrBlank()) {
            serverLoading = false
            return@LaunchedEffect
        }
        serverLoading = true
        serverLoadError = null
        runCatching { serverRepository.getImageSensorLogs(sessionId) }
            .onSuccess { serverLogs = it }
            .onFailure { serverLoadError = AuthRepository.userMessage(it) }
        serverLoading = false
    }

    val records = remember(logs, serverLogs, sessionId) {
        mergeRoadCollectionRecords(logs, serverLogs, sessionId.orEmpty())
    }
    val imageLogs = records.filter { !it.imagePath.isNullOrBlank() }
    var selectedImageIndex by remember(sessionId) { mutableIntStateOf(0) }

    LaunchedEffect(imageLogs.size, imageLogs.firstOrNull()?.stableKey) {
        selectedImageIndex = selectedImageIndex.coerceIn(
            0,
            (imageLogs.size - 1).coerceAtLeast(0)
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                if (sessionId.isNullOrBlank()) "노면 수집 기록" else "수집된 노면 판독 결과",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        item {
            Text(
                if (sessionId.isNullOrBlank()) {
                    "주행 중 저장된 센서·이미지 수집 데이터입니다."
                } else {
                    "자신의 주행에서 수집된 이미지를 이전·다음 버튼으로 확인할 수 있습니다."
                },
                color = Color(0xFF667085),
                style = MaterialTheme.typography.bodySmall
            )
        }
        serverLoadError?.let { message ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED))) {
                    Text(
                        "서버 기록을 불러오지 못해 휴대폰에 남은 기록만 표시합니다. $message",
                        Modifier.fillMaxWidth().padding(14.dp),
                        color = Color(0xFFB54708),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (serverLoading && records.isEmpty()) {
            item { LoadingRecordCard() }
        } else if (records.isEmpty() && serverLoadError == null) {
            item { EmptyRecordCard() }
        } else if (records.isNotEmpty()) {
            item {
                if (imageLogs.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
                        Text(
                            "이 주행에 저장된 이미지가 없습니다.",
                            Modifier.fillMaxWidth().padding(20.dp),
                            color = Color(0xFF667085)
                        )
                    }
                } else {
                    val safeIndex = selectedImageIndex.coerceIn(0, imageLogs.lastIndex)
                    val selectedLog = imageLogs[safeIndex]
                    key(selectedLog.stableKey) {
                        RideCollectionImagePager(
                            record = selectedLog,
                            position = safeIndex,
                            total = imageLogs.size,
                            onPrevious = {
                                if (selectedImageIndex > 0) selectedImageIndex--
                            },
                            onNext = {
                                if (selectedImageIndex < imageLogs.lastIndex) selectedImageIndex++
                            },
                            onDetailClick = {
                                onRecordClick(selectedLog.localRecordId, selectedLog.serverRecordKey)
                            }
                        )
                    }
                }
            }
            item {
                Text("수집 데이터 목록", fontWeight = FontWeight.Bold)
            }
            items(records, key = { it.stableKey }) { record ->
                RoadRecordCard(
                    record = record,
                    onClick = { onRecordClick(record.localRecordId, record.serverRecordKey) }
                )
            }
        }
    }
}

@Composable
private fun RideCollectionImagePager(
    record: RoadCollectionRecord,
    position: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDetailClick: () -> Unit
) {
    val imageIdentity = "${record.stableKey}:${record.eventUuid.orEmpty()}:${record.imagePath.orEmpty()}"
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BikeEyesBorderGray),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("수집 이미지", fontWeight = FontWeight.Bold)
                Text("${position + 1} / $total", color = Color(0xFF667085))
            }
            CaptureImage(
                path = record.imagePath,
                eventUuid = record.eventUuid,
                syncStatus = record.syncStatus,
                imageIdentity = imageIdentity
            )
            Text(
                formatDate(record.capturedAt),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF667085)
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = position > 0,
                    onClick = onPrevious
                ) { Text("‹ 이전") }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = position < total - 1,
                    onClick = onNext
                ) { Text("다음 ›") }
            }
            Button(modifier = Modifier.fillMaxWidth(), onClick = onDetailClick) {
                Text("이 이미지의 센서 상세 보기")
            }
        }
    }
}

@Composable
private fun RoadRecordCard(record: RoadCollectionRecord, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BikeEyesBorderGray),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("노면 데이터 묶음", fontWeight = FontWeight.Bold)
                SyncChip(record.syncStatus)
            }
            Text(formatDate(record.capturedAt))
            Text(
                locationText(record.latitude, record.longitude),
                color = Color(0xFF667085),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "센서 샘플 ${record.sensorSamples.size.coerceAtLeast(1)}개 · 이미지 ${
                    if (record.imagePath.isNullOrBlank()) "없음" else "있음"
                }",
                color = Color(0xFF667085),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun RoadCollectionRecordDetailScreen(recordId: Long) {
    val dao = BikeEyesLocalDatabase.getInstance(LocalContext.current).localSyncLogDao()
    val log by dao.observeSensorLog(recordId).collectAsState(initial = null)
    val record = remember(log) { log?.toRoadCollectionRecord() }

    RoadCollectionRecordDetailLayout(
        loading = log == null,
        errorMessage = if (log != null && record == null) "저장된 데이터 형식을 해석할 수 없습니다." else null,
        record = record
    )
}

@Composable
fun ServerRoadCollectionRecordDetailScreen(
    sessionId: String,
    serverRecordKey: String
) {
    val context = LocalContext.current
    val serverRepository = remember(context) { RideServerSyncRepository(context) }
    var record by remember(sessionId, serverRecordKey) {
        mutableStateOf<RoadCollectionRecord?>(null)
    }
    var loading by remember(sessionId, serverRecordKey) { mutableStateOf(true) }
    var errorMessage by remember(sessionId, serverRecordKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId, serverRecordKey) {
        loading = true
        errorMessage = null
        runCatching { serverRepository.getImageSensorLogs(sessionId) }
            .onSuccess { logs ->
                record = logs
                    .map { it.toRoadCollectionRecord(sessionId) }
                    .firstOrNull { it.serverRecordKey == serverRecordKey }
                if (record == null) {
                    errorMessage = "서버에서 해당 노면 수집 기록을 찾을 수 없습니다."
                }
            }
            .onFailure { errorMessage = AuthRepository.userMessage(it) }
        loading = false
    }

    RoadCollectionRecordDetailLayout(
        loading = loading,
        errorMessage = errorMessage,
        record = record
    )
}

@Composable
private fun RoadCollectionRecordDetailLayout(
    loading: Boolean,
    errorMessage: String?,
    record: RoadCollectionRecord?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("노면 수집 기록 상세", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "이 화면은 수집 원본을 보여주며 위험 여부를 자체 판정하지 않습니다.",
            color = Color(0xFF667085),
            style = MaterialTheme.typography.bodySmall
        )
        when {
            loading -> Text("기록을 불러오는 중입니다.", color = Color(0xFF667085))
            errorMessage != null -> Text(errorMessage, color = Color(0xFFD92D20))
            record != null -> RoadRecordDetail(record)
            else -> Text("기록을 찾을 수 없습니다.", color = Color(0xFFD92D20))
        }
    }
}

@Composable
private fun RoadRecordDetail(record: RoadCollectionRecord) {
    var showDetectionDetails by remember(record.stableKey) { mutableStateOf(false) }
    CaptureImage(
        path = record.imagePath,
        eventUuid = record.eventUuid,
        syncStatus = record.syncStatus
    )
    SectionTitle("수집 정보")
    DetailCard(
        listOf(
            "수집 시각" to formatDate(record.capturedAt),
            "서버 동기화" to syncText(record.syncStatus),
            "재시도 횟수" to "${record.retryCount}회",
            "센서 샘플" to "${record.sensorSamples.size.coerceAtLeast(1)}개",
            "마지막 오류" to (record.lastErrorMessage ?: "없음")
        )
    )

    SectionTitle("위치")
    if (record.latitude != 0.0 || record.longitude != 0.0) {
        BikeEyesKakaoMap(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(16.dp)),
            initialLatitude = record.latitude,
            initialLongitude = record.longitude,
            showCurrentLocation = false,
            markers = listOf(
                BikeEyesMapMarker(
                    id = record.stableKey,
                    latitude = record.latitude,
                    longitude = record.longitude
                )
            )
        )
    }
    DetailCard(
        listOf(
            "좌표" to locationText(record.latitude, record.longitude),
            "GPS 정확도" to String.format(Locale.KOREA, "%.1f m", record.accuracy),
            "속도" to String.format(Locale.KOREA, "%.1f km/h", record.speed * 3.6),
            "방향" to String.format(Locale.KOREA, "%.1f°", record.direction)
        )
    )

    SectionTitle("대표 센서값")
    SensorVectorCard("가속도", record.accelX, record.accelY, record.accelZ, "m/s²")
    SensorVectorCard("자이로", record.gyroX, record.gyroY, record.gyroZ, "rad/s")
    SensorVectorCard(
        "지자기",
        record.geomagneticX,
        record.geomagneticY,
        record.geomagneticZ,
        "μT"
    )

    if (record.sensorSamples.isNotEmpty()) {
        SectionTitle("묶음 센서 범위")
        RangeCard("가속도 크기", record.sensorSamples.map { it.accelerationMagnitude() }, "m/s²")
        RangeCard("자이로 크기", record.sensorSamples.map { it.gyroMagnitude() }, "rad/s")
        RangeCard("지자기 크기", record.sensorSamples.map { it.magneticMagnitude() }, "μT")
    }

    if (record.detections.isNotEmpty()) {
        val highestConfidenceByClass = record.detections
            .groupBy { it.class_name.lowercase() }
            .values
            .mapNotNull { detections -> detections.maxByOrNull { it.confidence } }
        val allDetectionDetails = record.detections
            .groupBy { it.class_name.lowercase() }
            .flatMap { (_, detections) ->
                val sortedDetections = detections.sortedByDescending { it.confidence }
                sortedDetections.mapIndexed { index, detection ->
                    val className = roadAnomalyClassText(detection.class_name)
                    val label = if (sortedDetections.size > 1) "$className ${index + 1}" else className
                    label to String.format(
                        Locale.KOREA,
                        "%s · 신뢰도 %.1f%%",
                        roadAnomalyMeaning(detection.class_name),
                        detection.confidence * 100.0,
                    )
                }
            }
        SectionTitle("AI 노면 판독 결과")
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("이 결과는 무엇을 뜻하나요?", fontWeight = FontWeight.Bold, color = BikeEyesGreen)
                Text(
                    "AI가 사진 속 노면 모양을 살펴 포트홀·맨홀·물웅덩이·볼라드·균열과 비슷한 부분을 찾은 결과입니다. " +
                        "신뢰도는 해당 유형과 닮았다고 AI가 판단한 정도이며, 사고가 날 확률을 뜻하지 않습니다.",
                    color = Color(0xFF475467),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        DetailCard(
            highestConfidenceByClass.map {
                roadAnomalyClassText(it.class_name) to
                    String.format(
                        Locale.KOREA,
                        "%s · 신뢰도 %.1f%%",
                        roadAnomalyMeaning(it.class_name),
                        it.confidence * 100.0,
                    )
            }
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = { showDetectionDetails = !showDetectionDetails }
        ) {
            Text(if (showDetectionDetails) "신뢰도 상세 닫기" else "신뢰도 상세 보기")
        }
        if (showDetectionDetails) {
            Text(
                "탐지된 전체 객체 ${record.detections.size}건",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
            DetailCard(allDetectionDetails)
        }
    }

    record.localRecordId?.let {
        Text(
            "로컬 기록 ID: $it",
            color = Color(0xFF667085),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private sealed interface CaptureImageState {
    data object Loading : CaptureImageState
    data class Available(val bitmap: android.graphics.Bitmap) : CaptureImageState
    data class Unavailable(val message: String) : CaptureImageState
}

@Composable
private fun CaptureImage(
    path: String?,
    eventUuid: String?,
    syncStatus: String,
    imageIdentity: String = "${eventUuid.orEmpty()}:${path.orEmpty()}"
) {
    val context = LocalContext.current
    val imageState by produceState<CaptureImageState>(
        initialValue = CaptureImageState.Loading,
        key1 = imageIdentity,
        key2 = syncStatus
    ) {
        value = withContext(Dispatchers.IO) {
            if (path.isNullOrBlank()) {
                return@withContext CaptureImageState.Unavailable("촬영 이미지 없음")
            }
            if (syncStatus != "SYNCED") {
                File(path).takeIf { it.isFile }?.let { localFile ->
                    BitmapFactory.decodeFile(localFile.absolutePath)?.let {
                        return@withContext CaptureImageState.Available(it)
                    }
                }
                val message = if (syncStatus == "FAILED") {
                    "이미지 전송 실패 · 재시도 상태를 확인해 주세요"
                } else {
                    "이미지 전송 대기 중"
                }
                return@withContext CaptureImageState.Unavailable(message)
            }
            if (eventUuid.isNullOrBlank()) {
                return@withContext CaptureImageState.Unavailable(
                    "이전 형식의 기록이라 서버 이미지를 조회할 수 없습니다"
                )
            }
            runCatching {
                NetworkModule.dynamicSensorApi(context)
                    .downloadSensorImage(eventUuid)
                    .use { body ->
                        val bytes = body.bytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
            }.getOrNull()?.let {
                CaptureImageState.Available(it)
            } ?: File(path).takeIf { it.isFile }?.let { localFile ->
                BitmapFactory.decodeFile(localFile.absolutePath)?.let {
                    CaptureImageState.Available(it)
                }
            } ?: CaptureImageState.Unavailable("서버 이미지 없음 또는 보존 기간 만료")
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937))
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (val state = imageState) {
                CaptureImageState.Loading -> Text("이미지를 불러오는 중입니다.", color = Color.White)
                is CaptureImageState.Available -> {
                Image(
                    bitmap = state.bitmap.asImageBitmap(),
                    contentDescription = "수집된 노면 이미지",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                }
                is CaptureImageState.Unavailable -> Text(state.message, color = Color.White)
            }
        }
    }
}

private fun roadAnomalyMeaning(className: String): String = when (className.lowercase()) {
    "pothole" -> "노면이 움푹 파인 부분으로 인식"
    "manhole" -> "맨홀 또는 맨홀 주변으로 인식"
    "flood_area" -> "물이 고이거나 젖은 구간으로 인식"
    "bollard" -> "주행로 위 볼라드로 인식"
    "linear", "crack_linear" -> "길게 이어진 선형 균열로 인식"
    "alligator", "crack_alligator" -> "거북등처럼 퍼진 균열로 인식"
    else -> "노면에서 확인이 필요한 특징으로 인식"
}

@Composable
private fun SensorVectorCard(
    title: String,
    x: Double,
    y: Double,
    z: Double,
    unit: String
) {
    val magnitude = sqrt(x * x + y * y + z * z)
    DetailCard(
        listOf(
            title to String.format(Locale.KOREA, "크기 %.3f %s", magnitude, unit),
            "X / Y / Z" to String.format(Locale.KOREA, "%.3f / %.3f / %.3f", x, y, z)
        )
    )
}

@Composable
private fun RangeCard(title: String, values: List<Double>, unit: String) {
    DetailCard(
        listOf(
            title to String.format(Locale.KOREA, "평균 %.3f %s", values.average(), unit),
            "최소 / 최대" to String.format(
                Locale.KOREA,
                "%.3f / %.3f %s",
                values.minOrNull() ?: 0.0,
                values.maxOrNull() ?: 0.0,
                unit
            )
        )
    )
}

@Composable
private fun DetailCard(items: List<Pair<String, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, BikeEyesBorderGray),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { (label, value) ->
                Text(label, color = Color(0xFF667085), style = MaterialTheme.typography.bodySmall)
                Text(value, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun SyncChip(status: String) {
    FilterChip(
        selected = status == "SYNCED",
        onClick = {},
        label = { Text(syncText(status)) }
    )
}

@Composable
private fun EmptyRecordCard() {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("저장된 노면 수집 기록이 없습니다.", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("주행을 시작하면 3초 단위 수집 묶음이 표시됩니다.", color = Color(0xFF667085))
        }
    }
}

@Composable
private fun LoadingRecordCard() {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
        Text(
            "서버에 보관된 노면 수집 기록을 불러오는 중입니다.",
            Modifier.fillMaxWidth().padding(20.dp),
            color = Color(0xFF667085)
        )
    }
}

private fun mergeRoadCollectionRecords(
    localLogs: List<LocalSyncLogEntity>,
    serverLogs: List<ServerSensorLog>,
    sessionId: String
): List<RoadCollectionRecord> {
    val serverRecords = serverLogs.map { log ->
        log.toRoadCollectionRecord(sessionId)
    }
    val serverEventUuids = serverRecords.mapNotNull { it.eventUuid }.toHashSet()
    val serverCapturedAt = serverRecords.map { it.capturedAt }.toHashSet()
    val localOnlyRecords = localLogs
        .mapNotNull { it.toRoadCollectionRecord() }
        .filterNot { local ->
            (!local.eventUuid.isNullOrBlank() && local.eventUuid in serverEventUuids) ||
                (local.syncStatus == "SYNCED" && local.capturedAt in serverCapturedAt)
        }
    return (serverRecords + localOnlyRecords).sortedByDescending { it.capturedAt }
}

private fun LocalSyncLogEntity.toRoadCollectionRecord(): RoadCollectionRecord? {
    val request = parseRequest() ?: return null
    return RoadCollectionRecord(
        stableKey = "local-$local_sync_id",
        localRecordId = local_sync_id,
        eventUuid = request.event_uuid ?: event_uuid,
        rideSessionId = request.ride_session_id,
        capturedAt = request.captured_at,
        latitude = request.latitude,
        longitude = request.longitude,
        accuracy = request.accuracy,
        speed = request.speed,
        direction = request.direction,
        gyroX = request.gyro_x,
        gyroY = request.gyro_y,
        gyroZ = request.gyro_z,
        accelX = request.accel_x,
        accelY = request.accel_y,
        accelZ = request.accel_z,
        geomagneticX = request.geomagnetic_x,
        geomagneticY = request.geomagnetic_y,
        geomagneticZ = request.geomagnetic_z,
        imagePath = request.image_path,
        sensorSamples = request.sensor_samples,
        syncStatus = sync_status,
        retryCount = retry_count,
        lastErrorMessage = last_error_message
    )
}

private fun ServerSensorLog.toRoadCollectionRecord(sessionId: String): RoadCollectionRecord {
    val key = event_uuid ?: "time-$captured_at"
    return RoadCollectionRecord(
        stableKey = "server-$key",
        serverRecordKey = key,
        eventUuid = event_uuid,
        rideSessionId = sessionId,
        capturedAt = captured_at,
        latitude = latitude ?: 0.0,
        longitude = longitude ?: 0.0,
        accuracy = accuracy ?: 0.0,
        speed = speed ?: 0.0,
        direction = direction ?: 0.0,
        gyroX = gyro_x,
        gyroY = gyro_y,
        gyroZ = gyro_z,
        accelX = accel_x,
        accelY = accel_y,
        accelZ = accel_z,
        geomagneticX = geomagnetic_x,
        geomagneticY = geomagnetic_y,
        geomagneticZ = geomagnetic_z,
        imagePath = image_path,
        detections = road_anomaly_detections,
        syncStatus = "SYNCED"
    )
}

private fun LocalSyncLogEntity.parseRequest(): SensorLogRequest? =
    runCatching { gson.fromJson(payload, SensorLogRequest::class.java) }.getOrNull()

private fun roadAnomalyClassText(className: String) = when (className.lowercase()) {
    "pothole" -> "포트홀"
    "manhole" -> "맨홀"
    "flood_area" -> "물웅덩이"
    "bollard" -> "볼라드"
    "linear" -> "선형 균열"
    "alligator" -> "거북등 균열"
    else -> className
}

private fun SensorSample.accelerationMagnitude() =
    sqrt(accel_x * accel_x + accel_y * accel_y + accel_z * accel_z)

private fun SensorSample.gyroMagnitude() =
    sqrt(gyro_x * gyro_x + gyro_y * gyro_y + gyro_z * gyro_z)

private fun SensorSample.magneticMagnitude() =
    sqrt(
        geomagnetic_x * geomagnetic_x +
            geomagnetic_y * geomagnetic_y +
            geomagnetic_z * geomagnetic_z
    )

private fun syncText(status: String) = when (status) {
    "SYNCED" -> "동기화 완료"
    "RETRYING" -> "재전송 중"
    "FAILED" -> "전송 실패"
    else -> "전송 대기"
}

private fun locationText(latitude: Double, longitude: Double) =
    if (latitude == 0.0 && longitude == 0.0) "위치 확인 전"
    else String.format(Locale.US, "%.6f, %.6f", latitude, longitude)

private fun formatDate(timestamp: Long) =
    SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA).format(Date(timestamp))
