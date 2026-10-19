package com.bikeeyes.app.ui.screens.mypage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.AdminHazardZoneDetailResponse
import com.bikeeyes.app.AdminHazardZoneItem
import com.bikeeyes.app.AuthRepository
import com.bikeeyes.app.UserSettingsStore
import com.bikeeyes.app.ui.components.BikeEyesKakaoMap
import com.bikeeyes.app.ui.components.BikeEyesMapMarker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

@Composable
fun AdminHazardZonesScreen(onBack: () -> Unit = {}, onDashboard: () -> Unit = {}) {
    AdminZoneAnalysisScreen(
        zoneCategory = "USER_HAZARD",
        screenTitle = "위험구간 분석",
        screenDescription = "지도와 수집 근거를 검토해 후보·확정·제외 상태를 관리합니다.",
        zoneLabel = "위험구간",
        requirePublicConfirmedZone = true,
        onBack = onBack,
        onDashboard = onDashboard
    )
}

@Composable
fun AdminRoadAnomalyZonesScreen(onBack: () -> Unit = {}, onDashboard: () -> Unit = {}) {
    AdminZoneAnalysisScreen(
        zoneCategory = "ROAD_ANOMALY",
        screenTitle = "노면이상 구간 분석",
        screenDescription = "AI 판독 기반 노면이상 후보·확정 상태와 수집 근거를 관리합니다.",
        zoneLabel = "노면이상 구간",
        requirePublicConfirmedZone = false,
        onBack = onBack,
        onDashboard = onDashboard
    )
}

@Composable
private fun AdminZoneAnalysisScreen(
    zoneCategory: String,
    screenTitle: String,
    screenDescription: String,
    zoneLabel: String,
    requirePublicConfirmedZone: Boolean,
    onBack: () -> Unit,
    onDashboard: () -> Unit
) {
    val context = LocalContext.current
    val profile = remember { UserSettingsStore(context).loadProfile() }
    val repository = remember { AuthRepository(context) }
    val scope = rememberCoroutineScope()
    val pageScrollState = rememberScrollState()
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("ALL") }
    var severity by remember { mutableStateOf("ALL") }
    var includeTestData by remember { mutableStateOf(false) }
    var zones by remember { mutableStateOf<List<AdminHazardZoneItem>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var zoneIndex by remember { mutableIntStateOf(0) }
    var selectedZoneId by remember { mutableStateOf<String?>(null) }
    var detailRequestGeneration by remember { mutableIntStateOf(0) }
    var evidenceIndex by remember { mutableIntStateOf(0) }
    var showZoneDetail by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<AdminHazardZoneDetailResponse?>(null) }
    var pendingStatus by remember { mutableStateOf<String?>(null) }
    var selectedImageEventUuid by remember { mutableStateOf<String?>(null) }
    var selectedImage by remember { mutableStateOf<Bitmap?>(null) }
    var imageLoading by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var imageRequestGeneration by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var pendingMarkerScrollZoneId by remember { mutableStateOf<String?>(null) }
    var preferredZoneIdAfterRefresh by remember { mutableStateOf<String?>(null) }

    fun loadEvidenceImage(eventUuid: String) {
        val requestGeneration = imageRequestGeneration + 1
        imageRequestGeneration = requestGeneration
        selectedImageEventUuid = eventUuid
        selectedImage = null
        imageError = null
        imageLoading = true
        scope.launch {
            val result = runCatching {
                val bytes = repository.adminSensorImage(eventUuid)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalStateException("image decode failed")
            }
            if (
                imageRequestGeneration != requestGeneration ||
                selectedImageEventUuid != eventUuid
            ) return@launch
            result.onSuccess { selectedImage = it }
                .onFailure {
                    imageError = "근거 이미지를 불러오지 못했습니다. 보존 상태를 확인해 주세요."
                }
            imageLoading = false
        }
    }

    fun clearZoneSelection() {
        detailRequestGeneration++
        imageRequestGeneration++
        zones = emptyList()
        total = 0
        selectedZoneId = null
        selected = null
        zoneIndex = 0
        evidenceIndex = 0
        showZoneDetail = false
        selectedImageEventUuid = null
        selectedImage = null
        imageError = null
        imageLoading = false
        pendingMarkerScrollZoneId = null
    }

    fun loadDetail(zoneId: String, revealDetail: Boolean = false) {
        val requestGeneration = detailRequestGeneration + 1
        detailRequestGeneration = requestGeneration
        selectedZoneId = zoneId
        imageRequestGeneration++
        selectedImageEventUuid = null
        selectedImage = null
        imageError = null
        imageLoading = false
        loading = true
        error = null
        scope.launch {
            val result = runCatching { repository.adminHazardZoneDetail(zoneId) }
            if (
                detailRequestGeneration != requestGeneration ||
                selectedZoneId != zoneId
            ) return@launch
            result
                .onSuccess {
                    selected = it
                    evidenceIndex = 0
                    showZoneDetail = revealDetail
                    val firstEvidence = it.evidence.firstOrNull()
                    val firstEventUuid = firstEvidence?.event_uuid
                    if (firstEvidence?.has_image == true && !firstEventUuid.isNullOrBlank()) {
                        loadEvidenceImage(firstEventUuid)
                    }
                }
                .onFailure {
                    error = AuthRepository.userMessage(it)
                    if (pendingMarkerScrollZoneId == zoneId) {
                        pendingMarkerScrollZoneId = null
                    }
                }
            loading = false
        }
    }

    fun selectZone(zoneId: String, revealDetail: Boolean = false) {
        val matchingIndex = zones.indexOfFirst { it.zone_id == zoneId }
        if (matchingIndex >= 0) zoneIndex = matchingIndex
        loadDetail(zoneId, revealDetail)
    }

    LaunchedEffect(refreshKey) {
        if (profile.role != "ADMIN") {
            error = "관리자 계정만 진입할 수 있습니다."
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching {
            repository.adminHazardZones(
                query = query,
                status = status,
                severity = severity,
                zoneCategory = zoneCategory,
                includeTestData = includeTestData,
                limit = 500
            )
        }.onSuccess {
            val filteredZones = it.items.filter { zone ->
                zone.status == "CANDIDATE" ||
                    (zone.status == "CONFIRMED" &&
                        (!requirePublicConfirmedZone || zone.isPublicConfirmedHazard()))
            }
            zones = filteredZones
            total = filteredZones.size

            if (filteredZones.isEmpty()) {
                preferredZoneIdAfterRefresh = null
                clearZoneSelection()
            } else {
                val preferredIndex = preferredZoneIdAfterRefresh
                    ?.let { preferredId -> filteredZones.indexOfFirst { it.zone_id == preferredId } }
                    ?.takeIf { it >= 0 }
                val retainedIndex = selectedZoneId
                    ?.let { selectedId -> filteredZones.indexOfFirst { it.zone_id == selectedId } }
                    ?.takeIf { it >= 0 }
                zoneIndex = preferredIndex ?: retainedIndex ?: 0
                preferredZoneIdAfterRefresh = null

                val currentZone = filteredZones[zoneIndex]
                if (selected?.zone?.zone_id != currentZone.zone_id) {
                    loadDetail(currentZone.zone_id)
                }
            }
        }.onFailure { error = AuthRepository.userMessage(it) }
        loading = false
    }

    LaunchedEffect(
        pendingMarkerScrollZoneId,
        selected?.zone?.zone_id,
        imageLoading,
        pageScrollState.maxValue
    ) {
        val pendingZoneId = pendingMarkerScrollZoneId ?: return@LaunchedEffect
        if (selected?.zone?.zone_id == pendingZoneId && !imageLoading) {
            // Wait for the selected evidence card and its image to finish layout
            // before using the page's new maximum scroll position.
            delay(120)
            pageScrollState.animateScrollTo(pageScrollState.maxValue)
            pendingMarkerScrollZoneId = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(pageScrollState)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AdminScreenHeader(
            screenTitle,
            screenDescription,
            onBack,
            onDashboard
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            HazardCountCard(
                Modifier.weight(1f), "전체",
                zones.size.toString()
            )
            HazardCountCard(
                Modifier.weight(1f), "확정",
                zones.count { it.status == "CONFIRMED" }.toString()
            )
            HazardCountCard(
                Modifier.weight(1f), "후보",
                zones.count { it.status == "CANDIDATE" }.toString()
            )
        }
//        Text("카테고리", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("상태", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        FilterRow(
            values = listOf(
                "ALL" to "전체",
                "CONFIRMED" to "확정",
                "CANDIDATE" to "후보"
            ),
            selected = status,
            onSelected = {
                if (status != it) {
                    status = it
                    preferredZoneIdAfterRefresh = null
                    clearZoneSelection()
                    refreshKey++
                }
            }
        )
        Text("위험도", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        FilterRow(
            values = listOf(
                "ALL" to "전체",
                "HIGH" to "높음",
                "MEDIUM" to "보통",
                "LOW" to "낮음"
            ),
            selected = severity,
            onSelected = {
                if (severity != it) {
                    severity = it
                    preferredZoneIdAfterRefresh = null
                    clearZoneSelection()
                    refreshKey++
                }
            }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("● 높음", color = androidx.compose.ui.graphics.Color(0xFFDC2626), style = MaterialTheme.typography.bodySmall)
            Text("● 보통", color = androidx.compose.ui.graphics.Color(0xFFF59E0B), style = MaterialTheme.typography.bodySmall)
            Text("● 낮음", color = androidx.compose.ui.graphics.Color(0xFF059669), style = MaterialTheme.typography.bodySmall)
        }
        BikeEyesKakaoMap(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .clip(RoundedCornerShape(16.dp)),
            showCurrentLocation = true,
            blockParentScrollGestures = true,
            markers = zones.map {
                BikeEyesMapMarker(
                    id = it.zone_id,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    color = hazardMarkerColor(it.severity)
                )
            },
            onMarkerClick = { zoneId ->
                pendingMarkerScrollZoneId = zoneId
                selectZone(zoneId)
            }
        )
        if (zones.isEmpty()) {
            Text(
                "선택한 조건에 맞는 ${zoneLabel}이 없습니다. 지도는 현재 위치를 유지합니다.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text("분석 조건 검색", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
//        Text(
//            "Mock 데이터는 실제 위험 안내와 통계에서 기본 제외됩니다.",
//            style = MaterialTheme.typography.bodySmall
//        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("제목·파손 유형·설명 검색") },
            singleLine = true
        )
//        Row(
//            modifier = Modifier.fillMaxWidth(),
//            horizontalArrangement = Arrangement.SpaceBetween
//        )
//        {
//            Column {
//                Text("Mock 데이터 포함", fontWeight = FontWeight.SemiBold)
//                Text("끄면 실제 데이터만 조회", style = MaterialTheme.typography.bodySmall)
//            }
//            Switch(
//                checked = includeTestData,
//                onCheckedChange = {
//                    includeTestData = it
//                    selected = null
//                    refreshKey++
//                }
//            )
//        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && profile.role == "ADMIN",
            onClick = {
                preferredZoneIdAfterRefresh = null
                clearZoneSelection()
                refreshKey++
            }
        ) {
            Text("${zoneLabel} 검색")
        }
        if (loading) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("검색 결과 ${total}건", fontWeight = FontWeight.Bold)

        Text(
            when (status) {
                "CONFIRMED" -> "확정 $zoneLabel"
                "CANDIDATE" -> "$zoneLabel 후보"
                else -> zoneLabel
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        if (zones.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "선택한 조건에 맞는 ${zoneLabel}과 근거 기록이 없습니다.",
                    Modifier.padding(16.dp)
                )
            }
        } else {
            HazardCarouselNavigation(
                current = zoneIndex,
                total = zones.size,
                previousLabel = "이전",
                nextLabel = "다음",
                onPrevious = {
                    zoneIndex = if (zoneIndex == 0) zones.lastIndex else zoneIndex - 1
                    selectZone(zones[zoneIndex].zone_id)
                },
                onNext = {
                    zoneIndex = if (zoneIndex == zones.lastIndex) 0 else zoneIndex + 1
                    selectZone(zones[zoneIndex].zone_id)
                }
            )
            HazardAdminCard(
                zone = zones[zoneIndex],
                onClick = { selectZone(zones[zoneIndex].zone_id, revealDetail = true) }
            )
        }
        selected
            ?.takeIf { detail -> zones.any { zone -> zone.zone_id == detail.zone.zone_id } }
            ?.let { detail ->
            HazardAdminDetail(
                detail = detail,
                zoneLabel = zoneLabel,
                showZoneDetail = showZoneDetail,
                evidenceIndex = evidenceIndex,
                onEvidencePrevious = {
                    if (detail.evidence.isNotEmpty()) {
                        evidenceIndex = if (evidenceIndex == 0) detail.evidence.lastIndex else evidenceIndex - 1
                        val evidence = detail.evidence[evidenceIndex]
                        imageRequestGeneration++
                        selectedImageEventUuid = null
                        selectedImage = null
                        imageError = null
                        imageLoading = false
                        val eventUuid = evidence.event_uuid
                        if (evidence.has_image && !eventUuid.isNullOrBlank()) {
                            loadEvidenceImage(eventUuid)
                        }
                    }
                },
                onEvidenceNext = {
                    if (detail.evidence.isNotEmpty()) {
                        evidenceIndex = if (evidenceIndex == detail.evidence.lastIndex) 0 else evidenceIndex + 1
                        val evidence = detail.evidence[evidenceIndex]
                        imageRequestGeneration++
                        selectedImageEventUuid = null
                        selectedImage = null
                        imageError = null
                        imageLoading = false
                        val eventUuid = evidence.event_uuid
                        if (evidence.has_image && !eventUuid.isNullOrBlank()) {
                            loadEvidenceImage(eventUuid)
                        }
                    }
                },
                onStatusClick = { pendingStatus = it },
                selectedImageEventUuid = selectedImageEventUuid,
                selectedImage = selectedImage,
                imageLoading = imageLoading,
                imageError = imageError,
                onImageClick = ::loadEvidenceImage
            )
        }
    }

    pendingStatus?.let { targetStatus ->
        val zone = selected?.zone
        AlertDialog(
            onDismissRequest = { pendingStatus = null },
            title = { Text("$zoneLabel 상태 변경") },
            text = {
                Text(
                    "${zone?.title.orEmpty()}을(를) ‘${hazardStatusText(targetStatus)}’ 상태로 변경할까요?\n" +
                        "변경 작업은 관리자 감사 로그에 기록됩니다."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingStatus = null
                        val zoneId = zone?.zone_id ?: return@TextButton
                        loading = true
                        scope.launch {
                            runCatching {
                                repository.updateAdminHazardZoneStatus(zoneId, targetStatus)
                            }.onSuccess {
                                preferredZoneIdAfterRefresh = zoneId
                                clearZoneSelection()
                                refreshKey++
                            }.onFailure { error = AuthRepository.userMessage(it) }
                            loading = false
                        }
                    }
                ) { Text("변경") }
            },
            dismissButton = {
                TextButton(onClick = { pendingStatus = null }) { Text("취소") }
            }
        )
    }
}

@Composable
private fun FilterRow(
    values: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        values.forEach {
            FilterChip(
                selected = selected == it.first,
                onClick = { onSelected(it.first) },
                label = { Text(it.second) }
            )
        }
    }
}

@Composable
private fun HazardCountCard(modifier: Modifier, title: String, value: String) {
    Card(modifier) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HazardAdminCard(zone: AdminHazardZoneItem, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${zone.title} · ${hazardStatusText(zone.status)}",
                fontWeight = FontWeight.Bold
            )
            Text(
                "${hazardCategoryText(zone.zone_category)} · ${damageTypeText(zone.road_damage_type)} · " +
                    "위험도 ${severityText(zone.severity)} · " +
                    "감지 ${zone.detection_count}회",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
//                "${if (zone.is_test_data) "Mock/테스트" else "실제 데이터"} · " +
                    "${"위도 : %.5f".format(zone.latitude)}, ${"경도 : %.5f".format(zone.longitude)}",
                style = MaterialTheme.typography.bodySmall
            )
            Button(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
                Text("상세 검토")
            }
        }
    }
}

@Composable
private fun HazardAdminDetail(
    detail: AdminHazardZoneDetailResponse,
    zoneLabel: String,
    showZoneDetail: Boolean,
    evidenceIndex: Int,
    onEvidencePrevious: () -> Unit,
    onEvidenceNext: () -> Unit,
    onStatusClick: (String) -> Unit,
    selectedImageEventUuid: String?,
    selectedImage: Bitmap?,
    imageLoading: Boolean,
    imageError: String?,
    onImageClick: (String) -> Unit
) {
    val zone = detail.zone
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    val firstDetected = LocalDateTime.parse(zone.first_detected_at).format(formatter)
    val lastDetected = LocalDateTime.parse(zone.last_detected_at).format(formatter)

    if (showZoneDetail) {
        Text("선택한 $zoneLabel 상세", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        HazardDetailCard(
            zone.title,
            buildString {
                appendLine("${damageTypeText(zone.road_damage_type)}")
                appendLine("위험도 : ${severityText(zone.severity)}")
                appendLine("$zoneLabel 상태 : ${hazardStatusText(zone.status)}")
//                appendLine(if (zone.is_test_data) "Mock/테스트" else "실제 데이터")
                appendLine("반경 : ${zone.radius_meters.toInt()}m")
                appendLine("누적 감지 : ${zone.detection_count}회")
                appendLine("최초 : $firstDetected")
                appendLine("최근 : $lastDetected")
//                append(
//                    "AI 분석 ${zone.source_model?.let { "과거 참고값 $it" } ?: "미분석"}" +
//                        (zone.confidence?.let { " · 신뢰도 ${"%.2f".format(it)}" } ?: "")
//                )
            }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "CANDIDATE" to "후보",
                "CONFIRMED" to "확정",
                "REJECTED" to "제외"
            ).forEach {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = zone.status != it.first,
                    onClick = { onStatusClick(it.first) }
                ) {
                    Text(
                        if (it.first == "REJECTED" && zone.status == "CONFIRMED") {
                            "확정 취소"
                        } else it.second
                    )
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text("라이더 현장 의견", fontWeight = FontWeight.Bold)
                Text(
                    "위험요소 유지 ${detail.feedback_summary.danger} · " +
                        "주의 필요 ${detail.feedback_summary.caution} · " +
                        "개선됨 ${detail.feedback_summary.resolved}"
                )
                if (detail.recent_feedback.isEmpty()) {
                    Text(
                        "아직 등록된 현장 의견이 없습니다.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    detail.recent_feedback.take(5).forEach { feedback ->
                        Text(
                            "${feedback.updated_at.replace('T', ' ')} · " +
                                "${feedback.nickname} · ${feedbackTypeText(feedback.feedback_type)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Text(
                    "현장 의견은 참고 정보이며 AI 근거와 자동 확정 조건을 변경하지 않습니다.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
    Text(
        "근거 노면 수집 기록 ${detail.evidence.size}건",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Text(
        "각 이미지에 배정된 대표 Peak 센서값 1건과 촬영 이미지를 확인해 ${zoneLabel} 판정 근거를 검토할 수 있습니다.",
        style = MaterialTheme.typography.bodySmall
    )
    if (detail.evidence.isEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Text(
                "현재 반경 내 연결 가능한 센서 기록이 없습니다.",
                modifier = Modifier.padding(16.dp)
            )
        }
    }
    val safeEvidenceIndex = evidenceIndex.coerceIn(0, (detail.evidence.size - 1).coerceAtLeast(0))
    if (detail.evidence.isNotEmpty()) {
        HazardCarouselNavigation(
            current = safeEvidenceIndex,
            total = detail.evidence.size,
            previousLabel = "이전",
            nextLabel = "다음",
            onPrevious = onEvidencePrevious,
            onNext = onEvidenceNext
        )
        val evidence = detail.evidence[safeEvidenceIndex]
        val acceleration = sqrt(
            evidence.accel_x * evidence.accel_x +
                evidence.accel_y * evidence.accel_y +
                evidence.accel_z * evidence.accel_z
        )
        val gyro = sqrt(
            evidence.gyro_x * evidence.gyro_x +
                evidence.gyro_y * evidence.gyro_y +
                evidence.gyro_z * evidence.gyro_z
        )
        val eventUuid = evidence.event_uuid
        val imageSelected = !eventUuid.isNullOrBlank() && selectedImageEventUuid == eventUuid
        Card(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("근거 ${safeEvidenceIndex + 1}", fontWeight = FontWeight.Bold)
                    Text(
                        formatHazardTimestamp(evidence.captured_at),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "대표 Peak 센서값 1건",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                evidence.peak_sample_at?.let { peakSampleAt ->
                    Text(
                        "Peak 시각 ${formatHazardTimestamp(peakSampleAt)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                HazardEvidenceMetricRow(
                    "중심 거리",
                    "약 ${evidence.distance_meters.toInt()}m",
                    "Peak 가속도 크기",
                    "${"%.2f".format(acceleration)}"
                )
                HazardEvidenceMetricRow(
                    "Peak 자이로 크기",
                    "${"%.2f".format(gyro)}",
                    "촬영 이미지",
                    if (evidence.has_image) "있음" else "없음"
                )
                if (evidence.has_image && !eventUuid.isNullOrBlank()) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onImageClick(eventUuid) }
                    ) {
                        Text(if (imageSelected && selectedImage != null) "이미지 다시 불러오기" else "이미지 보기")
                    }
                    if (imageSelected) {
                        if (imageLoading) CircularProgressIndicator()
                        imageError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                        selectedImage?.let { bitmap ->
                            Text("수집 이미지", fontWeight = FontWeight.SemiBold)
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "근거 ${safeEvidenceIndex + 1} 수집 이미지",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }
                } else {
                    Text(
                        "이 기록에는 보관 중인 이미지가 없습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun feedbackTypeText(value: String) = when (value) {
    "DANGER" -> "위험요소 유지"
    "CAUTION" -> "주의 필요"
    "RESOLVED" -> "개선됨"
    else -> value
}

@Composable
private fun HazardCarouselNavigation(
    current: Int,
    total: Int,
    previousLabel: String,
    nextLabel: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(modifier = Modifier.weight(1f), onClick = onPrevious) {
            Text("‹ $previousLabel")
        }
        Card(Modifier.weight(0.7f)) {
            Text(
                "${current + 1} / $total",
                modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                fontWeight = FontWeight.Bold
            )
        }
        Button(modifier = Modifier.weight(1f), onClick = onNext) {
            Text("$nextLabel ›")
        }
    }
}

@Composable
private fun HazardEvidenceMetricRow(
    firstLabel: String,
    firstValue: String,
    secondLabel: String,
    secondValue: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HazardEvidenceMetric(
            modifier = Modifier.weight(1f),
            label = firstLabel,
            value = firstValue
        )
        HazardEvidenceMetric(
            modifier = Modifier.weight(1f),
            label = secondLabel,
            value = secondValue
        )
    }
}

@Composable
private fun HazardEvidenceMetric(modifier: Modifier, label: String, value: String) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HazardDetailCard(title: String, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun hazardStatusText(value: String) = when (value) {
    "CANDIDATE" -> "후보"
    "REVIEW_REQUIRED" -> "관리자 승인 대기"
    "CONFIRMED" -> "확정"
    "STALE" -> "장기 미감지"
    "ARCHIVED" -> "보관"
    "REJECTED" -> "제외"
    "EXPIRED" -> "만료"
    else -> value
}

private fun hazardCategoryText(value: String) = when (value) {
    "ROAD_ANOMALY" -> "노면 이상 구간"
    "USER_HAZARD" -> "사용자 위험요소"
    else -> value
}

private fun severityText(value: String) = when (value) {
    "HIGH" -> "높음"
    "MEDIUM" -> "보통"
    else -> "낮음"
}

private fun damageTypeText(value: String) = when (value) {
    "POTHOLE" -> "포트홀"
    "CRACK" -> "노면 균열"
    "UNEVEN" -> "노면 불균형"
    else -> "노면 파손"
}

private fun AdminHazardZoneItem.isPublicConfirmedHazard(): Boolean {
    if (!status.equals("CONFIRMED", ignoreCase = true)) return false
    if (!zone_category.equals("USER_HAZARD", ignoreCase = true)) return false
    if (is_test_data || !is_user_visible) return false
    val expiresAt = expires_at ?: return true
    return runCatching { LocalDateTime.parse(expiresAt).isAfter(LocalDateTime.now()) }
        .getOrDefault(false)
}

private fun hazardMarkerColor(value: String) = when (value) {
    "HIGH" -> android.graphics.Color.rgb(220, 38, 38)
    "MEDIUM" -> android.graphics.Color.rgb(245, 158, 11)
    else -> android.graphics.Color.rgb(5, 150, 105)
}

private fun formatHazardTimestamp(value: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA).format(Date(value))
