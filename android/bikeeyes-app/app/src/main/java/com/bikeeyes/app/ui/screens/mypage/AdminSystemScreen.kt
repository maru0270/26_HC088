package com.bikeeyes.app.ui.screens.mypage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.AuditLogResponse
import com.bikeeyes.app.AuthRepository
import com.bikeeyes.app.SyncIssueItem
import com.bikeeyes.app.SyncIssuesResponse
import com.bikeeyes.app.SystemErrorResponse
import com.bikeeyes.app.UserSettingsStore

private enum class AdminSystemTab(val label: String) {
    ERRORS("서버 오류"),
    SYNC("동기화"),
    AUDIT("로그")
}

@Composable
fun AdminSystemScreen(onBack: () -> Unit = {}, onDashboard: () -> Unit = {}) {
    val context = LocalContext.current
    val repository = remember { AuthRepository(context) }
    val profile = remember { UserSettingsStore(context).loadProfile() }
    var sync by remember { mutableStateOf<SyncIssuesResponse?>(null) }
    var errors by remember { mutableStateOf<List<SystemErrorResponse>>(emptyList()) }
    var audits by remember { mutableStateOf<List<AuditLogResponse>>(emptyList()) }
    var selectedTab by remember { mutableStateOf(AdminSystemTab.ERRORS) }
    var query by remember { mutableStateOf("") }
    var level by remember { mutableStateOf("ALL") }
    var selectedYear by remember { mutableStateOf<Int?>(null) }
    var selectedMonth by remember { mutableStateOf<Int?>(null) }
    var selectedDay by remember { mutableStateOf<Int?>(null) }
    var selectedDetail by remember { mutableStateOf<Pair<String, String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        if (profile.role != "ADMIN") {
            message = "관리자 계정만 접근할 수 있습니다."
            return@LaunchedEffect
        }
        loading = true
        message = null
        runCatching {
            sync = repository.syncIssues(selectedYear, selectedMonth, selectedDay)
            errors = repository.systemErrors(selectedYear, selectedMonth, selectedDay)
            audits = repository.auditLogs(selectedYear, selectedMonth, selectedDay)
        }.onFailure { message = AuthRepository.userMessage(it) }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AdminScreenHeader(
            "시스템 로그·운영 진단",
            "서버 오류, 동기화 상태와 관리자 감사 로그를 확인합니다.",
            onBack,
            onDashboard
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AdminSystemTab.entries.forEach { tab ->
                FilterChip(
                    selected = selectedTab == tab,
                    onClick = {
                        selectedTab = tab
                        query = ""
                        selectedDetail = null
                    },
                    label = { Text(tab.label) }
                )
            }
        }
        AdminDateFilter(selectedYear, selectedMonth, selectedDay, { year, month, day ->
            selectedYear = year
            selectedMonth = month
            selectedDay = day
            refreshKey++
        }, enabled = !loading && profile.role == "ADMIN")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                when (selectedTab) {
                    AdminSystemTab.ERRORS -> "오류 ${errors.size}건"
                    AdminSystemTab.SYNC -> "불완전 주행 ${sync?.incomplete_ride_count ?: 0}건"
                    AdminSystemTab.AUDIT -> "감사 로그 ${audits.size}건"
                },
                fontWeight = FontWeight.Bold
            )
            Button(enabled = !loading, onClick = { refreshKey++ }) { Text("새로고침") }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    when (selectedTab) {
                        AdminSystemTab.ERRORS -> "API·요청 ID·메시지 검색"
                        AdminSystemTab.SYNC -> "종료 시각·누락 건수 검색"
                        AdminSystemTab.AUDIT -> "작업·대상·작업자 검색"
                    }
                )
            },
            singleLine = true
        )
        if (selectedTab == AdminSystemTab.ERRORS) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL" to "전체", "WARN" to "경고", "ERROR" to "오류").forEach {
                    FilterChip(
                        selected = level == it.first,
                        onClick = { level = it.first },
                        label = { Text(it.second) }
                    )
                }
            }
        }
        if (loading) CircularProgressIndicator()
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        when (selectedTab) {
            AdminSystemTab.ERRORS -> ErrorLogSection(errors, query, level) {
                selectedDetail = effectiveErrorLevel(it) to buildString {
                    appendLine("발생 시각: ${it.created_at}")
                    appendLine("API: ${it.endpoint.orEmpty()}")
                    appendLine("응답 코드: ${it.status_code ?: "-"}")
                    appendLine("요청 ID: ${it.request_id ?: "-"}")
                    append("메시지: ${it.message}")
                }
            }
            AdminSystemTab.SYNC -> SyncLogSection(sync, query) {
                selectedDetail = "동기화 문제" to buildString {
                    appendLine("센서: ${it.received_sensor_logs}/${it.expected_sensor_logs} (누락 ${it.missing_sensor_logs})")
                    appendLine("사고: ${it.received_accidents}/${it.expected_accidents} (누락 ${it.missing_accidents})")
                    appendLine("주행 종료: ${it.ended_at}")
                    append("마지막 센서 수신: ${it.last_sensor_received_at ?: "-"}")
                }
            }
            AdminSystemTab.AUDIT -> AuditLogSection(audits, query) {
                selectedDetail = "관리자 작업 상세" to buildString {
                    appendLine("발생 시각: ${it.created_at}")
                    appendLine("작업자 ID: ${it.actor_user_id ?: "-"}")
                    appendLine("작업: ${it.action}")
                    appendLine("대상 유형: ${it.target_type}")
                    append("대상 ID: ${it.target_id ?: "-"}")
                }
            }
        }
    }

    selectedDetail?.let { detail ->
        AlertDialog(
            onDismissRequest = { selectedDetail = null },
            title = { Text(detail.first) },
            text = { Text(detail.second) },
            confirmButton = {
                TextButton(onClick = { selectedDetail = null }) { Text("확인") }
            }
        )
    }

}

@Composable
private fun ErrorLogSection(
    errors: List<SystemErrorResponse>,
    query: String,
    level: String,
    onClick: (SystemErrorResponse) -> Unit
) {
    val normalized = query.trim().lowercase()
    val filtered = errors.filter {
        (level == "ALL" || effectiveErrorLevel(it) == level) &&
            (normalized.isBlank() ||
                it.endpoint.orEmpty().lowercase().contains(normalized) ||
                it.request_id.orEmpty().lowercase().contains(normalized) ||
                it.message.lowercase().contains(normalized))
    }
    if (filtered.isEmpty()) Text("조건에 맞는 서버 오류가 없습니다.")
    filtered.forEach {
        LogCard(
            title = "${effectiveErrorLevel(it)} · ${it.status_code ?: "-"}",
            detail = "${it.endpoint.orEmpty()}\n${it.message}\n${it.created_at}",
            onClick = { onClick(it) }
        )
    }
}

@Composable
private fun SyncLogSection(
    sync: SyncIssuesResponse?,
    query: String,
    onClick: (SyncIssueItem) -> Unit
) {
    val normalizedQuery = query.trim()
    val items = sync?.items.orEmpty().filter {
        normalizedQuery.isBlank() || buildString {
            append(it.ended_at)
            append(' ')
            append(it.last_sensor_received_at.orEmpty())
            append(' ')
            append(it.missing_sensor_logs)
            append(' ')
            append(it.missing_accidents)
        }.contains(normalizedQuery, ignoreCase = true)
    }
    if (items.isEmpty()) Text("조건에 맞는 동기화 문제가 없습니다.")
    items.forEachIndexed { index, item ->
        LogCard(
            "동기화 문제 ${index + 1}",
            "센서 누락 ${item.missing_sensor_logs}건 · 사고 누락 ${item.missing_accidents}건",
            onClick = { onClick(item) }
        )
    }
}

@Composable
private fun AuditLogSection(
    audits: List<AuditLogResponse>,
    query: String,
    onClick: (AuditLogResponse) -> Unit
) {
    val normalized = query.trim().lowercase()
    val filtered = audits.filter {
        normalized.isBlank() ||
            it.action.lowercase().contains(normalized) ||
            it.target_type.lowercase().contains(normalized) ||
            it.target_id.orEmpty().lowercase().contains(normalized) ||
            it.actor_user_id?.toString().orEmpty().contains(normalized)
    }
    if (filtered.isEmpty()) Text("조건에 맞는 관리자 작업 로그가 없습니다.")
    filtered.forEach {
        LogCard(
            it.action,
            "작업자 ${it.actor_user_id ?: "-"} · ${it.target_type} ${it.target_id.orEmpty()}\n${it.created_at}",
            onClick = { onClick(it) }
        )
    }
}

@Composable
private fun LogCard(title: String, detail: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
            Text("상세 보기 ›", style = MaterialTheme.typography.labelSmall, color = Color(0xFF067647))
        }
    }
}

private fun effectiveErrorLevel(error: SystemErrorResponse): String =
    if (error.status_code == 404) "ERROR" else error.level
