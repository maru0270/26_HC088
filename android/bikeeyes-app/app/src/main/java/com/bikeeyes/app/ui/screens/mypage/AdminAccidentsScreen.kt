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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.AdminAccidentListItem
import com.bikeeyes.app.AuthRepository
import com.bikeeyes.app.UserSettingsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AdminAccidentsScreen(
    onAccidentsClick: (String, String) -> Unit = { _, _ -> },
    onBack: () -> Unit = {},
    onDashboard: () -> Unit = {}
) {
    val context = LocalContext.current
    val repository = remember { AuthRepository(context) }
    val profile = remember { UserSettingsStore(context).loadProfile() }
    var query by rememberSaveable { mutableStateOf("") }
    var responseFilter by rememberSaveable { mutableStateOf("ALL") }
    var selectedYear by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectedMonth by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectedDay by rememberSaveable { mutableStateOf<Int?>(null) }
    var rides by remember { mutableStateOf<List<AdminAccidentListItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var currentPage by rememberSaveable { mutableIntStateOf(0) }
    var totalAccidentRides by remember { mutableIntStateOf(0) }
    val pageSize = 5

    LaunchedEffect(refreshKey, currentPage) {
        if (profile.role != "ADMIN") {
            error = "관리자 계정만 접근할 수 있습니다."
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching {
            repository.adminAccidents(
                query = query,
                responseStatus = responseFilter,
                year = selectedYear,
                month = selectedMonth,
                day = selectedDay,
                limit = pageSize,
                offset = currentPage * pageSize
            )
        }.onSuccess {
            rides = it.items
            totalAccidentRides = it.total
        }
            .onFailure { error = AuthRepository.userMessage(it) }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AdminScreenHeader(
            "사고 기록 조회",
            "사고 의심과 사용자 응답 결과를 확인합니다.",
            onBack,
            onDashboard
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("이메일·닉네임 검색") },
            singleLine = true
        )
        AdminDateFilter(selectedYear, selectedMonth, selectedDay, { year, month, day ->
            selectedYear = year
            selectedMonth = month
            selectedDay = day
            currentPage = 0
            refreshKey++
        }, enabled = !loading && profile.role == "ADMIN")
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading,
            onClick = {
                currentPage = 0
                refreshKey++
            }
        ) { Text("검색") }
        Text("응답 상태", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AdminAccidentFilter.entries.forEach {
                FilterChip(
                    selected = responseFilter == it.name,
                    onClick = {
                        responseFilter = it.name
                        currentPage = 0
                        refreshKey++
                    },
                    label = { Text(it.label) }
                )
            }
        }
        if (loading) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("조건에 맞는 사고 포함 주행 ${totalAccidentRides}건", fontWeight = FontWeight.Bold)
        rides.forEach { ride ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ride.nickname ?: "익명/테스트", fontWeight = FontWeight.Bold)
                    Text(
                        "최근 사고 ${accidentTime(ride.latest_detected_at)} · 사고 기록 ${ride.received_accidents}건",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        accidentResponseSummary(ride),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading,
                        onClick = {
                            onAccidentsClick(ride.ride_session_id, responseFilter)
                        }
                    ) { Text("사고 기록 보기") }
                }
            }
        }
        if (totalAccidentRides > 0) {
            val pageCount = (totalAccidentRides + pageSize - 1) / pageSize
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !loading && currentPage > 0,
                    onClick = { currentPage-- }
                ) { Text("‹ 이전") }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !loading && currentPage < pageCount - 1,
                    onClick = { currentPage++ }
                ) { Text("다음 ›") }
            }
            Text(
                "${currentPage + 1} / ${pageCount} 페이지 · 페이지당 ${pageSize}건",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun accidentTime(value: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA).format(Date(value))

private fun accidentResponseSummary(item: AdminAccidentListItem): String = buildList {
    if (item.accident_response_count > 0) add("사고 ${item.accident_response_count}건")
    if (item.safe_response_count > 0) add("괜찮아요 ${item.safe_response_count}건")
    if (item.no_response_count > 0) add("무응답 ${item.no_response_count}건")
}.joinToString(" · ").ifEmpty { "응답 결과 확인 필요" }
