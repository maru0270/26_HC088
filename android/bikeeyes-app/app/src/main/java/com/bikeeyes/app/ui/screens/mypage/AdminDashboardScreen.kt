package com.bikeeyes.app.ui.screens.mypage

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.AdminDashboardResponse
import com.bikeeyes.app.AdminHazardZoneItem
import com.bikeeyes.app.AdminOperationsResponse
import com.bikeeyes.app.AuthRepository
import com.bikeeyes.app.UserSettingsStore
import com.bikeeyes.app.ui.components.BikeEyesKakaoMap
import com.bikeeyes.app.ui.components.BikeEyesMapMarker
import com.bikeeyes.app.ui.theme.BikeEyesGreen

@Composable
fun AdminDashboardScreen(
    onUsersClick: () -> Unit,
    onRidesClick: () -> Unit,
    onAccidentsClick: () -> Unit,
    onRoadAnomalyZonesClick: () -> Unit,
    onHazardZonesClick: () -> Unit,
    onReviewItemsClick: () -> Unit,
    onSystemClick: () -> Unit
) {
    val context = LocalContext.current
    val profile = remember { UserSettingsStore(context).loadProfile() }
    val repository = remember { AuthRepository(context) }
    var dashboard by remember { mutableStateOf<AdminDashboardResponse?>(null) }
    var operations by remember { mutableStateOf<AdminOperationsResponse?>(null) }
    var zones by remember { mutableStateOf<List<AdminHazardZoneItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var hazardStatusFilter by remember { mutableStateOf("ALL") }
    var hazardSeverityFilter by remember { mutableStateOf("ALL") }

    val filteredZones = zones.filter { zone ->
        (hazardStatusFilter == "ALL" || zone.status.equals(hazardStatusFilter, ignoreCase = true)) &&
            (hazardSeverityFilter == "ALL" || zone.severity.equals(hazardSeverityFilter, ignoreCase = true))
    }

    LaunchedEffect(refreshKey) {
        if (profile.role != "ADMIN") {
            error = "관리자 계정만 접근할 수 있습니다."
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching {
            dashboard = repository.adminDashboard()
            operations = repository.adminOperations()
            zones = repository.adminHazardZones(
                status = "ALL",
                zoneCategory = "USER_HAZARD",
                includeTestData = false,
                limit = 500
            ).items.filter {
                it.status == "CANDIDATE" || it.isDashboardPublicConfirmedHazard()
            }
        }.onFailure { error = AuthRepository.userMessage(it) }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "관리자 대시보드",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "BikeEyes 서비스 현황",
                    color = Color(0xFF667085),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(
                enabled = !loading && profile.role == "ADMIN",
                onClick = { refreshKey++ }
            ) { Text("새로고침") }
        }

        if (loading) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        dashboard?.let { data ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DashboardMetricCard(
                    Modifier.weight(1f), "전체 사용자", "${data.active_users}",
                    "오늘 +${data.new_users_today}", BikeEyesGreen
                )
                DashboardMetricCard(
                    Modifier.weight(1f), "전체 주행", "${data.rides}",
                    "오늘 +${data.rides_today}", Color(0xFF2563EB)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DashboardMetricCard(
                    Modifier.weight(1f), "사고 접수", "${data.accident_events}",
                    "센서 기반 사고 이벤트", Color(0xFFE11D48)
                )
                DashboardMetricCard(
                    Modifier.weight(1f), "위험구간",
                    "${data.candidate_hazard_zones + data.confirmed_hazard_zones}",
                    "후보 ${data.candidate_hazard_zones} · 확정 ${data.confirmed_hazard_zones}",
                    Color(0xFFF59E0B)
                )
            }
        }

        Text("위험구간 현황", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("상태", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        DashboardHazardFilterRow(
            values = listOf("ALL" to "전체", "CONFIRMED" to "확정", "CANDIDATE" to "후보"),
            selected = hazardStatusFilter,
            onSelected = { hazardStatusFilter = it }
        )
        Text("위험도", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        DashboardHazardFilterRow(
            values = listOf("ALL" to "전체", "HIGH" to "높음", "MEDIUM" to "보통", "LOW" to "낮음"),
            selected = hazardSeverityFilter,
            onSelected = { hazardSeverityFilter = it }
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("● 높음", color = Color(0xFFDC2626), style = MaterialTheme.typography.bodySmall)
            Text("● 보통", color = Color(0xFFF59E0B), style = MaterialTheme.typography.bodySmall)
            Text("● 낮음", color = Color(0xFF059669), style = MaterialTheme.typography.bodySmall)
        }
        BikeEyesKakaoMap(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clip(RoundedCornerShape(16.dp)),
            showCurrentLocation = false,
            markers = filteredZones.map {
                BikeEyesMapMarker(
                    id = it.zone_id,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    color = dashboardHazardMarkerColor(it.severity)
                )
            },
            blockParentScrollGestures = true,
            onMarkerClick = { onHazardZonesClick() }
        )
        if (filteredZones.isEmpty()) {
            Text(
                "선택한 조건에 맞는 위험구간이 없습니다.",
                color = Color(0xFF667085),
                style = MaterialTheme.typography.bodySmall
            )
        }

        operations?.let { data ->
            Text("서비스 상태", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            AdminStatusCard(
                healthy = data.health.database == "healthy" && data.health.storage == "healthy",
                title = if (data.alerts.isEmpty()) "시스템 정상" else "확인 필요",
                detail = "DB ${data.health.database} · 저장공간 ${data.health.storage} · " +
                    "최근 24시간 서버 오류 ${data.last_24_hours.server_errors}건"
            )
        }

        dashboard?.let { data ->
            AdminStatusCard(
                healthy = data.warnings_last_24_hours == 0,
                title = "최근 운영 현황",
                detail = "경고/오류 ${data.warnings_last_24_hours}건 · " +
                    "삭제 대기 주행 ${data.pending_ride_deletions}건 · 등록 기기 ${data.registered_devices}대"
            )
        }

        Text("관리 메뉴", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AdminMenuCard("사용자 관리", "계정·등록 기기와 사용자별 주행·데이터 동기화 현황 조회", onUsersClick)
        AdminMenuCard("주행 기록 조회", "주행 시간·거리와 이미지·센서·사고 데이터의 수집 및 동기화 상태 확인", onRidesClick)
        AdminMenuCard("사고 기록 조회", "단말 센서 기반 사고 의심 이벤트와 사용자 응답·블랙박스 근거 확인", onAccidentsClick)
        AdminMenuCard(
            "노면이상 구간 관리",
            "유효 AI 판독과 GPS 위치 근거로 생성된 노면이상 후보·확정 구간 관리",
            onRoadAnomalyZonesClick
        )
        AdminMenuCard("위험구간 관리", "AI 판독과 Peak 충격·GPS 속도 조건을 함께 충족한 위험구간 후보 관리", onHazardZonesClick)
        AdminMenuCard("재검토 항목 관리", "AI 미탐지 이미지 중 센서·GPS 조건을 충족한 관리자 재검토 항목 처리", onReviewItemsClick)
        AdminMenuCard("시스템 로그·운영 진단", "서버·DB·이미지 저장소 상태와 동기화·보관·감사 로그 점검", onSystemClick)
    }
}

@Composable
private fun DashboardHazardFilterRow(
    values: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        values.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                label = { Text(label) }
            )
        }
    }
}

private fun dashboardHazardMarkerColor(severity: String) = when (severity.uppercase()) {
    "HIGH" -> AndroidColor.rgb(220, 38, 38)
    "MEDIUM" -> AndroidColor.rgb(245, 158, 11)
    else -> AndroidColor.rgb(5, 150, 105)
}

private fun AdminHazardZoneItem.isDashboardPublicConfirmedHazard(): Boolean {
    if (!status.equals("CONFIRMED", ignoreCase = true)) return false
    if (!zone_category.equals("USER_HAZARD", ignoreCase = true)) return false
    if (is_test_data || !is_user_visible) return false
    val expiresAt = expires_at ?: return true
    return runCatching {
        java.time.LocalDateTime.parse(expiresAt).isAfter(java.time.LocalDateTime.now())
    }.getOrDefault(false)
}

@Composable
private fun DashboardMetricCard(
    modifier: Modifier,
    title: String,
    value: String,
    detail: String,
    accent: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(accent)
                    .fillMaxWidth(0.28f)
                    .height(5.dp)
            )
            Text(title, style = MaterialTheme.typography.bodySmall, color = Color(0xFF475467))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = Color(0xFF667085))
        }
    }
}

@Composable
private fun AdminStatusCard(healthy: Boolean, title: String, detail: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (healthy) Color(0xFFECFDF3) else Color(0xFFFFF7ED)
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (healthy) "●" else "▲", color = if (healthy) BikeEyesGreen else Color(0xFFF59E0B))
            Column {
                Text(title, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFF475467))
            }
        }
    }
}

@Composable
private fun AdminMenuCard(title: String, detail: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFF667085))
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = BikeEyesGreen)
        }
    }
}

@Composable
private fun EmptyAdminPanel(message: String) {
    Card(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text(message, color = Color(0xFF667085))
        }
    }
}
