package com.bikeeyes.app.ui.screens.mypage

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.AdminUserDetailResponse
import com.bikeeyes.app.AdminUserItem
import com.bikeeyes.app.AuthRepository
import com.bikeeyes.app.UserSettingsStore
import kotlinx.coroutines.launch

@Composable
fun AdminUsersScreen(onBack: () -> Unit = {}, onDashboard: () -> Unit = {}) {
    val context = LocalContext.current
    val repository = remember { AuthRepository(context) }
    val profile = remember { UserSettingsStore(context).loadProfile() }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("ALL") }
    var users by remember { mutableStateOf<List<AdminUserItem>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<AdminUserDetailResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey, status) {
        if (profile.role != "ADMIN") {
            error = "관리자 계정만 접근할 수 있습니다."
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching { repository.adminUsers(query, status) }
            .onSuccess {
                users = it.items
                total = it.total
            }
            .onFailure { error = AuthRepository.userMessage(it) }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AdminScreenHeader("사용자 관리", "등록 계정과 기기·수집 현황을 조회합니다.", onBack, onDashboard)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("이메일·닉네임·전화번호 검색") },
            singleLine = true
        )
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading,
            onClick = { selected = null; refreshKey++ }
        ) { Text("검색") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ALL" to "전체", "ACTIVE" to "활성", "INACTIVE" to "비활성").forEach { item ->
                FilterChip(
                    selected = status == item.first,
                    onClick = { status = item.first; selected = null },
                    label = { Text(item.second) }
                )
            }
        }
        if (loading) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("사용자 ${total}명", fontWeight = FontWeight.Bold)
        users.forEach { user ->
            AdminUserCard(user) {
                loading = true
                scope.launch {
                    runCatching { repository.adminUserDetail(user.user_id) }
                        .onSuccess { selected = it }
                        .onFailure { error = AuthRepository.userMessage(it) }
                    loading = false
                }
            }
        }
        selected?.let { AdminUserDetailCard(it) }
    }
}

@Composable
private fun AdminUserCard(user: AdminUserItem, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${user.nickname} · ${user.role}", fontWeight = FontWeight.Bold)
            Text(user.email, style = MaterialTheme.typography.bodySmall)
            Text(
                "주행 ${user.ride_count}건 · 센서 ${user.sensor_log_count}건 · " +
                    "사고 ${user.accident_count}건 · 기기 ${user.device_count}대",
                style = MaterialTheme.typography.bodySmall
            )
            Text(if (user.is_active) "활성 계정" else "비활성 계정")
        }
    }
}

@Composable
private fun AdminUserDetailCard(user: AdminUserDetailResponse) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("사용자 상세", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${user.nickname} · ${user.email}")
            Text("권한 ${user.role} · ${if (user.is_active) "활성" else "비활성"}")
            Text("기기 ${user.device_count}대 · 주행 ${user.ride_count}건")
            Text("센서 ${user.sensor_log_count}건 · 이미지 ${user.image_count}개 · 사고 ${user.accident_count}건")
            Text("복구 가능 주행 ${user.recoverable_ride_count}건")
            user.recent_rides.take(5).forEach {
                Text(
                    "• ${"%.1f".format(it.total_distance_meters / 1000)}km · ${it.elapsed_seconds / 60}분",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
