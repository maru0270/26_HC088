package com.bikeeyes.app.ui.screens.mypage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.AdminDiagnosticsResponse
import com.bikeeyes.app.AdminDashboardResponse
import com.bikeeyes.app.AdminUserDetailResponse
import com.bikeeyes.app.AdminUserItem
import com.bikeeyes.app.AuditLogResponse
import com.bikeeyes.app.AuthRepository
import com.bikeeyes.app.AuthSessionStore
import com.bikeeyes.app.BikeEyesLocalDatabase
import com.bikeeyes.app.DeviceHealthPolicy
import com.bikeeyes.app.RideTrackingState
import com.bikeeyes.app.RetentionStatusResponse
import com.bikeeyes.app.SyncIssuesResponse
import com.bikeeyes.app.SystemErrorResponse
import com.bikeeyes.app.SensorUploadRepository
import com.bikeeyes.app.AdminOperationsResponse
import com.bikeeyes.app.UserSettingsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun AdminDiagnosticsScreen(
    onRecordsClick: () -> Unit = {},
    onHazardZonesClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val profile = remember { UserSettingsStore(context).loadProfile() }
    val repository = remember { AuthRepository(context) }
    val scope = rememberCoroutineScope()
    var diagnostics by remember { mutableStateOf<AdminDiagnosticsResponse?>(null) }
    var dashboard by remember { mutableStateOf<AdminDashboardResponse?>(null) }
    var users by remember { mutableStateOf<List<AdminUserItem>>(emptyList()) }
    var userTotal by remember { mutableIntStateOf(0) }
    var userQuery by remember { mutableStateOf("") }
    var userStatus by remember { mutableStateOf("ALL") }
    var selectedUser by remember { mutableStateOf<AdminUserDetailResponse?>(null) }
    var audits by remember { mutableStateOf<List<AuditLogResponse>>(emptyList()) }
    var retention by remember { mutableStateOf<RetentionStatusResponse?>(null) }
    var syncIssues by remember { mutableStateOf<SyncIssuesResponse?>(null) }
    var systemErrors by remember { mutableStateOf<List<SystemErrorResponse>>(emptyList()) }
    var operations by remember { mutableStateOf<AdminOperationsResponse?>(null) }
    var localQueue by remember { mutableStateOf("확인 중") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        if (profile.role != "ADMIN") {
            error = "관리자 계정만 진입할 수 있습니다."
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching {
            dashboard = repository.adminDashboard()
            val userResponse = repository.adminUsers(userQuery, userStatus)
            users = userResponse.items
            userTotal = userResponse.total
            diagnostics = repository.adminDiagnostics()
            audits = repository.auditLogs()
            retention = repository.retentionStatus()
            syncIssues = repository.syncIssues()
            systemErrors = repository.systemErrors()
            operations = repository.adminOperations()
            val dao = BikeEyesLocalDatabase.getInstance(context).localSyncLogDao()
            localQueue = "대기 ${dao.countByStatus("PENDING")} · 재시도/실패 ${dao.countByStatus("FAILED")}"
        }.onFailure {
            error = AuthRepository.userMessage(it)
        }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("관리자 진단", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Button(enabled = !loading && profile.role == "ADMIN", onClick = { refreshKey++ }) {
                Text("새로고침")
            }
        }
        if (loading) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = profile.role == "ADMIN",
            onClick = onRecordsClick
        ) {
            Text("주행·노면·사고 기록 관리")
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = profile.role == "ADMIN",
            onClick = onHazardZonesClick
        ) {
            Text("위험구간 후보·확정·제외 관리")
        }

        dashboard?.let { AdminDashboardSection(it) }
        AdminUsersSection(
            query = userQuery,
            onQueryChanged = { userQuery = it },
            status = userStatus,
            total = userTotal,
            users = users,
            loading = loading,
            onSearch = {
                selectedUser = null
                refreshKey++
            },
            onStatusChanged = {
                userStatus = it
                selectedUser = null
                refreshKey++
            },
            onUserClick = { userId ->
                loading = true
                error = null
                scope.launch {
                    runCatching { repository.adminUserDetail(userId) }
                        .onSuccess { selectedUser = it }
                        .onFailure { error = AuthRepository.userMessage(it) }
                    loading = false
                }
            }
        )
        selectedUser?.let { AdminUserDetailSection(it) }

        diagnostics?.let {
            DiagnosticCard("서버 데이터", "사용자 ${it.users} · 주행 ${it.rides} · 삭제 대기 ${it.pending_deletions}")
            DiagnosticCard("수집 데이터", "센서 ${it.sensor_logs} · 사고 ${it.accident_events}")
            DiagnosticCard(
                "고아 센서 세션",
                "연결 대기 ${it.orphan_sensor_waiting} · 고아 확정 ${it.orphan_sensor_confirmed}" +
                    " · 연결 완료 ${it.orphan_sensor_resolved}"
            )
            DiagnosticCard(
                "레거시·이미지 정합성",
                "UUID 없는 레거시 ${it.legacy_sensor_without_uuid}건" +
                    "\nDB 이미지 참조 ${it.referenced_images}개 · 파일 누락 ${it.missing_image_files}개"
            )
            DiagnosticCard(
                "DB 스키마",
                "최신 마이그레이션 ${it.latest_migration ?: "확인 불가"}"
            )
            DiagnosticCard(
                "이미지 저장소",
                "${it.image_storage_directory}\n파일당 최대 ${it.image_max_bytes / 1024 / 1024}MB"
            )
        }
        operations?.let {
            DiagnosticCard(
                "서버 운영 상태",
                "DB ${it.health.database} (${it.health.database_latency_ms}ms) · " +
                    "저장공간 ${it.health.storage}\n" +
                    "여유 ${it.health.storage_free_bytes / 1024 / 1024}MB · " +
                    "백업 ${it.health.backup.status}\n" +
                    "24시간 서버오류 ${it.last_24_hours.server_errors}건 · " +
                    "경고 ${if (it.alerts.isEmpty()) "없음" else it.alerts.joinToString()}"
            )
            Button(
                enabled = !loading && profile.role == "ADMIN",
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        runCatching { repository.runBackup() }
                            .onSuccess { refreshKey++ }
                            .onFailure {
                                error = AuthRepository.userMessage(it)
                                loading = false
                            }
                    }
                }
            ) {
                Text("DB 백업 실행")
            }
        }
        syncIssues?.let {
            DiagnosticCard(
                "전송 완성도",
                "누락 가능 주행 ${it.incomplete_ride_count}건" +
                    if (it.items.isNotEmpty()) {
                        "\n최근 누락: 센서 ${it.items.first().missing_sensor_logs}건 · " +
                            "사고 ${it.items.first().missing_accidents}건"
                    } else ""
            )
        }
        DiagnosticCard("최근 서버 오류", "${systemErrors.size}건")
        Button(
            enabled = !loading && profile.role == "ADMIN",
            onClick = {
                loading = true
                scope.launch {
                    val dao = BikeEyesLocalDatabase.getInstance(context).localSyncLogDao()
                    dao.resetFailuresForManualRetry(System.currentTimeMillis())
                    SensorUploadRepository.resetNetworkBackoff()
                    refreshKey++
                }
            }
        ) {
            Text("실패 전송 다시 시도")
        }
        retention?.let { status ->
            val latest = status.latest
            DiagnosticCard(
                "데이터 보관 자동화",
                "최근 실행 ${latest?.status ?: "실행 이력 없음"}" +
                    (latest?.completed_at?.let { " · $it" } ?: "") +
                    "\n파일 삭제 대기 ${status.queued_file_deletions}개" +
                    "\n원본 ${status.raw_retention_days}일 · 로그 ${status.system_log_retention_days}일" +
                    " · 주행 복구 ${status.ride_recovery_days}일"
            )
            Button(
                enabled = !loading && profile.role == "ADMIN",
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        runCatching { repository.runRetention() }
                            .onSuccess { refreshKey++ }
                            .onFailure {
                                error = AuthRepository.userMessage(it)
                                loading = false
                            }
                    }
                }
            ) {
                Text("보관 정책 지금 실행")
            }
        }
        DiagnosticCard(
            "기기 및 연결 상태",
            buildString {
                appendLine("서버 ${UserSettingsStore(context).loadServerBaseUrl()}")
                appendLine("로그인 ${if (AuthSessionStore(context).hasSession()) "정상" else "세션 없음"}")
                appendLine("전송 $localQueue")
                appendLine("GPS ${RideTrackingState.gpsAccuracyMeters.value?.let { "%.1fm".format(it) } ?: "수신 전"}")
                appendLine("카메라 ${if (RideTrackingState.cameraReady.value) "정상" else "확인 필요"}")
                appendLine("저조도 ${if (RideTrackingState.lowLightDetected.value) "감지됨" else "정상"}")
                append("배터리 ${DeviceHealthPolicy.batteryPercent(context)}% · 저장공간 ${DeviceHealthPolicy.availableBytes(context) / 1024 / 1024}MB")
            }
        )

        Text("최근 관리자 감사 로그", fontWeight = FontWeight.Bold)
        if (!loading && audits.isEmpty() && error == null) Text("표시할 감사 로그가 없습니다.")
        audits.take(20).forEach {
            DiagnosticCard(
                it.action,
                "${it.target_type} ${it.target_id.orEmpty()}\n${formatAuditTime(it.created_at)}"
            )
        }
    }
}

private fun formatAuditTime(value: String): String = runCatching {
    val input = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    val output = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA)
    output.format(input.parse(value) ?: Date())
}.getOrDefault(value)

@Composable
private fun DiagnosticCard(title: String, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AdminDashboardSection(dashboard: AdminDashboardResponse) {
    Text(
        "관리자 대시보드",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AdminMetricCard(
            modifier = Modifier.weight(1f),
            title = "사용자",
            value = "${dashboard.active_users}명",
            detail = "오늘 +${dashboard.new_users_today}"
        )
        AdminMetricCard(
            modifier = Modifier.weight(1f),
            title = "주행",
            value = "${dashboard.rides}건",
            detail = "오늘 +${dashboard.rides_today}"
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AdminMetricCard(
            modifier = Modifier.weight(1f),
            title = "노면 수집",
            value = "${dashboard.sensor_logs}건",
            detail = "이미지 ${dashboard.images}개"
        )
        AdminMetricCard(
            modifier = Modifier.weight(1f),
            title = "사고 이벤트",
            value = "${dashboard.accident_events}건",
            detail = "등록 기기 ${dashboard.registered_devices}대"
        )
    }
    DiagnosticCard(
        "위험구간 및 운영 상태",
        "후보 ${dashboard.candidate_hazard_zones} · 확정 ${dashboard.confirmed_hazard_zones}\n" +
            "삭제 대기 주행 ${dashboard.pending_ride_deletions} · " +
            "최근 24시간 경고/오류 ${dashboard.warnings_last_24_hours}"
    )
}

@Composable
private fun AdminMetricCard(
    modifier: Modifier,
    title: String,
    value: String,
    detail: String
) {
    Card(modifier) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AdminUsersSection(
    query: String,
    onQueryChanged: (String) -> Unit,
    status: String,
    total: Int,
    users: List<AdminUserItem>,
    loading: Boolean,
    onSearch: () -> Unit,
    onStatusChanged: (String) -> Unit,
    onUserClick: (Long) -> Unit
) {
    Text(
        "사용자 관리",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("이메일·닉네임·전화번호 검색") },
        singleLine = true
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf("ALL" to "전체", "ACTIVE" to "활성", "INACTIVE" to "비활성").forEach {
            Button(
                modifier = Modifier.weight(1f),
                enabled = !loading && status != it.first,
                onClick = { onStatusChanged(it.first) }
            ) {
                Text(it.second)
            }
        }
    }
    Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = !loading,
        onClick = onSearch
    ) {
        Text("사용자 검색")
    }
    Text("검색 결과 ${total}명", fontWeight = FontWeight.SemiBold)
    if (!loading && users.isEmpty()) {
        Text("조건에 맞는 사용자가 없습니다.")
    }
    users.forEach { user ->
        Card(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "${user.nickname} · ${user.role}",
                    fontWeight = FontWeight.Bold
                )
                Text(user.email, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${if (user.is_active) "활성" else "비활성"} · 기기 ${user.device_count}대 · " +
                        "주행 ${user.ride_count}건 · 센서 ${user.sensor_log_count}건 · " +
                        "사고 ${user.accident_count}건",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "최근 접속 ${user.last_seen_at ?: "기록 없음"}",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !loading,
                    onClick = { onUserClick(user.user_id) }
                ) {
                    Text("사용자 상세")
                }
            }
        }
    }
}

@Composable
private fun AdminUserDetailSection(user: AdminUserDetailResponse) {
    DiagnosticCard(
        "선택한 사용자 상세",
        buildString {
            appendLine("${user.nickname} · ${user.email}")
            appendLine("권한 ${user.role} · 상태 ${if (user.is_active) "활성" else "비활성"}")
            appendLine("전화번호 ${user.phone ?: "미등록"} · 기기 ${user.device_count}대")
            appendLine("주행 ${user.ride_count} · 복구 대기 ${user.recoverable_ride_count}")
            appendLine("센서 ${user.sensor_log_count} · 이미지 ${user.image_count} · 사고 ${user.accident_count}")
            append("가입 ${user.created_at} · 최근 접속 ${user.last_seen_at ?: "기록 없음"}")
        }
    )
    Text("최근 주행", fontWeight = FontWeight.Bold)
    if (user.recent_rides.isEmpty()) {
        Text("주행 기록이 없습니다.")
    }
    user.recent_rides.forEachIndexed { index, ride ->
        DiagnosticCard(
            "최근 주행 ${index + 1}",
            "거리 ${"%.2f".format(ride.total_distance_meters / 1_000.0)}km · " +
                "시간 ${ride.elapsed_seconds / 60}분 · " +
                "수집 ${ride.collected_frame_count}건 · 사고 ${ride.accident_count}건"
        )
    }
}
