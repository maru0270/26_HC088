package com.bikeeyes.app.ui.screens.riskzone

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.BikeEyesLocalDatabase
import com.bikeeyes.app.AuthRepository
import com.bikeeyes.app.HazardFeedbackResponse
import com.bikeeyes.app.MockRiskZoneRepository
import com.bikeeyes.app.NetworkModule
import com.bikeeyes.app.RiskZoneEntity
import com.bikeeyes.app.ui.components.BikeEyesKakaoMap
import com.bikeeyes.app.ui.components.BikeEyesMapMarker
import com.bikeeyes.app.ui.theme.BikeEyesBorderGray
import com.bikeeyes.app.ui.theme.BikeEyesGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class RiskViewMode(val label: String) { MAP("지도"), LIST("목록") }

@Composable
fun RiskZoneHubScreen(onZoneClick: (String) -> Unit) {
    val context = LocalContext.current
    val database = BikeEyesLocalDatabase.getInstance(context)
    val repository = remember(database) {
        MockRiskZoneRepository(
            database.riskZoneDao(),
            com.bikeeyes.app.NetworkModule.dynamicSensorApi(context)
        )
    }
    val zones by repository.observeZones().collectAsState(initial = emptyList())
    var viewMode by remember { mutableStateOf(RiskViewMode.MAP) }
    var severity by remember { mutableStateOf("ALL") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(repository) {
        loading = true
        loadError = null
        repository.refresh().onFailure {
            loadError = it.message ?: "위험구간 정보를 불러오지 못했습니다."
        }
        loading = false
    }

    val filtered = zones.filter { severity == "ALL" || it.severity == severity }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text("위험지도", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RiskViewMode.entries.forEach { mode ->
                FilterChip(
                    selected = viewMode == mode,
                    onClick = { viewMode = mode },
                    label = { Text(mode.label) }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("ALL" to "전체 위험도", "HIGH" to "높음", "MEDIUM" to "보통", "LOW" to "낮음")
                .forEach { (value, label) ->
                    FilterChip(
                        selected = severity == value,
                        onClick = { severity = value },
                        label = { Text(label) }
                    )
                }
        }
        Spacer(Modifier.height(8.dp))

        if (viewMode == RiskViewMode.MAP && filtered.isNotEmpty()) {
            RiskMapSummary(filtered.size)
            Spacer(Modifier.height(8.dp))
        }

        when {
            loadError != null -> StateCard("오류", loadError!!)
            loading -> StateCard("불러오는 중", "확정 위험구간 정보를 확인하고 있습니다.")
            zones.isEmpty() -> StateCard("확정 위험구간 없음", "현재 표시할 실제 확정 위험구간이 없습니다.")
            filtered.isEmpty() -> StateCard("검색 결과 없음", "선택한 조건에 맞는 위험구간이 없습니다.")
            viewMode == RiskViewMode.MAP -> RiskMapPreview(
                zones = filtered,
                onZoneClick = onZoneClick,
                modifier = Modifier.weight(1f)
            )
            else -> RiskZoneList(filtered, onZoneClick)
        }
    }
}

@Composable
private fun RiskMapSummary(visibleCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "현재 지도에 위험구간 ${visibleCount}곳 표시",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            RiskLegendItem("높음", severityColor("HIGH"))
            RiskLegendItem("보통", severityColor("MEDIUM"))
            RiskLegendItem("낮음", severityColor("LOW"))
        }
    }
}

@Composable
private fun RiskLegendItem(label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("●", color = color, style = MaterialTheme.typography.bodySmall)
        Text(label, color = Color(0xFF667085), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RiskMapPreview(
    zones: List<RiskZoneEntity>,
    onZoneClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF7EF)),
        shape = RoundedCornerShape(16.dp)
    ) {
        BikeEyesKakaoMap(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp)),
            showCurrentLocation = true,
            followCurrentLocation = true,
            markers = zones.map { zone ->
                BikeEyesMapMarker(
                    id = zone.zone_id,
                    latitude = zone.latitude,
                    longitude = zone.longitude,
                    color = severityMarkerColor(zone.severity)
                )
            },
            onMarkerClick = onZoneClick
        )
    }
}

@Composable
private fun RiskZoneList(zones: List<RiskZoneEntity>, onZoneClick: (String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(zones, key = { it.zone_id }) { zone ->
            RiskZoneCard(zone) { onZoneClick(zone.zone_id) }
        }
    }
}

@Composable
private fun RiskZoneCard(zone: RiskZoneEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        border = BorderStroke(1.dp, BikeEyesBorderGray),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(zone.title, fontWeight = FontWeight.Bold)
                Text(severityText(zone.severity), color = severityColor(zone.severity), fontWeight = FontWeight.Bold)
            }
            Text("${damageTypeText(zone.road_damage_type)} · 누적 ${zone.detection_count}회")
            Text(
                String.format(Locale.US, "%.6f, %.6f", zone.latitude, zone.longitude),
                color = Color(0xFF667085),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun RiskZoneDetailScreen(zoneId: String) {
    val context = LocalContext.current
    val database = BikeEyesLocalDatabase.getInstance(context)
    val zone by database.riskZoneDao().observeById(zoneId).collectAsState(initial = null)
    val repository = remember(context) { AuthRepository(context) }
    val scope = rememberCoroutineScope()
    var feedback by remember { mutableStateOf<HazardFeedbackResponse?>(null) }
    var feedbackLoading by remember { mutableStateOf(true) }
    var feedbackSaving by remember { mutableStateOf(false) }
    var feedbackError by remember { mutableStateOf<String?>(null) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(zoneId) {
        feedbackLoading = true
        feedbackError = null
        runCatching { repository.hazardZoneFeedback(zoneId) }
            .onSuccess { feedback = it }
            .onFailure { feedbackError = AuthRepository.userMessage(it) }
        feedbackLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("위험구간 상세", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        zone?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = severityColor(it.severity).copy(alpha = 0.12f))
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(it.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("위험도 ${severityText(it.severity)}", color = severityColor(it.severity), fontWeight = FontWeight.Bold)
                }
            }
            Text("대표 근거 이미지", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            HazardRepresentativeImage(zoneId)
            BikeEyesKakaoMap(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(16.dp)),
                initialLatitude = it.latitude,
                initialLongitude = it.longitude,
                showCurrentLocation = true,
                markers = listOf(
                    BikeEyesMapMarker(
                        id = it.zone_id,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        color = severityMarkerColor(it.severity)
                    )
                )
            )
            DetailItems(
                listOf(
                    "노면 파손 유형" to damageTypeText(it.road_damage_type),
                    "설명" to it.description,
                    "좌표" to String.format(Locale.US, "%.6f, %.6f", it.latitude, it.longitude),
                    "접근 판단 반경" to "15m",
                    "누적 감지 횟수" to "${it.detection_count}회",
                    "최근 감지" to SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(it.last_detected_at))
                )
            )
            RiderFeedbackSection(
                feedback = feedback,
                loading = feedbackLoading,
                saving = feedbackSaving,
                error = feedbackError,
                message = feedbackMessage,
                onSubmit = { feedbackType ->
                    if (!feedbackSaving) {
                        feedbackSaving = true
                        feedbackError = null
                        feedbackMessage = null
                        scope.launch {
                            runCatching {
                                repository.updateHazardZoneFeedback(zoneId, feedbackType)
                            }.onSuccess { response ->
                                feedback = response
                                feedbackMessage = if (response.changed) {
                                    "현장 의견이 반영되었습니다."
                                } else {
                                    "이미 같은 의견이 등록되어 있습니다."
                                }
                            }.onFailure {
                                feedbackError = AuthRepository.userMessage(it)
                            }
                            feedbackSaving = false
                        }
                    }
                }
            )
        } ?: StateCard("불러오는 중", "위험구간 상세 정보를 준비하고 있습니다.")
    }
}

private sealed interface RepresentativeImageState {
    data object Loading : RepresentativeImageState
    data class Available(val bitmap: android.graphics.Bitmap) : RepresentativeImageState
    data class Unavailable(val message: String) : RepresentativeImageState
}

@Composable
private fun HazardRepresentativeImage(zoneId: String) {
    val context = LocalContext.current
    val state by produceState<RepresentativeImageState>(
        initialValue = RepresentativeImageState.Loading,
        key1 = zoneId,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                NetworkModule.dynamicSensorApi(context)
                    .downloadHazardZoneRepresentativeImage(zoneId)
                    .use { body ->
                        val bytes = body.bytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: error("이미지 형식을 읽을 수 없습니다.")
                    }
            }.fold(
                onSuccess = { RepresentativeImageState.Available(it) },
                onFailure = {
                    RepresentativeImageState.Unavailable(
                        "보존 중인 대표 근거 이미지가 없습니다."
                    )
                },
            )
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2937)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            when (val current = state) {
                RepresentativeImageState.Loading -> CircularProgressIndicator(color = Color.White)
                is RepresentativeImageState.Available -> Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = "위험구간 대표 근거 이미지",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                is RepresentativeImageState.Unavailable -> Text(
                    current.message,
                    color = Color.White,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}

@Composable
private fun RiderFeedbackSection(
    feedback: HazardFeedbackResponse?,
    loading: Boolean,
    saving: Boolean,
    error: String?,
    message: String?,
    onSubmit: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, BikeEyesBorderGray),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("라이더 현장 확인", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "실제로 현장을 확인한 경우에만 현재 상태를 알려주세요. 의견은 AI 판정이나 위험구간을 직접 변경하지 않습니다.",
                color = Color(0xFF667085),
                style = MaterialTheme.typography.bodySmall
            )
            if (loading) {
                CircularProgressIndicator()
            } else {
                val current = feedback?.my_feedback?.feedback_type
                listOf(
                    "DANGER" to "위험요소가 그대로 있어요",
                    "CAUTION" to "일부 개선됐지만 주의가 필요해요",
                    "RESOLVED" to "위험요소가 개선됐어요"
                ).forEach { (value, label) ->
                    OutlinedButton(
                        onClick = { onSubmit(value) },
                        enabled = !saving && feedback?.can_submit == true,
                        border = BorderStroke(
                            if (current == value) 2.dp else 1.dp,
                            if (current == value) Color.Black else BikeEyesBorderGray
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(label)
                    }
                }
                feedback?.aggregate?.let { aggregate ->
                    Text(
                        "다른 라이더 의견 · 위험 유지 ${aggregate.danger} · 주의 ${aggregate.caution} · 개선 ${aggregate.resolved}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF475467)
                    )
                }
            }
            if (saving) Text("의견을 저장하고 있습니다.", color = BikeEyesGreen)
            message?.let { Text(it, color = BikeEyesGreen, fontWeight = FontWeight.SemiBold) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun DetailItems(items: List<Pair<String, String>>) {
    items.forEach { (label, value) ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, BikeEyesBorderGray),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(Modifier.padding(15.dp)) {
                Text(label, color = Color(0xFF667085), style = MaterialTheme.typography.bodySmall)
                Text(value, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StateCard(title: String, message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(message, color = Color(0xFF667085))
        }
    }
}

private fun severityColor(value: String) = when (value) {
    "HIGH" -> Color(0xFFDC2626)
    "MEDIUM" -> Color(0xFFF59E0B)
    else -> BikeEyesGreen
}

private fun severityMarkerColor(value: String) = when (value) {
    "HIGH" -> android.graphics.Color.rgb(220, 38, 38)
    "MEDIUM" -> android.graphics.Color.rgb(245, 158, 11)
    else -> android.graphics.Color.rgb(5, 150, 105)
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
    else -> "복합 노면 파손"
}
