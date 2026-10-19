package com.bikeeyes.app.ui.screens.mypage

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.NotificationSettings
import com.bikeeyes.app.AuthRepository
import com.bikeeyes.app.UserProfile
import com.bikeeyes.app.UserSettingsStore
import com.bikeeyes.app.NetworkModule
import com.bikeeyes.app.SafetyContributionResponse
import com.bikeeyes.app.WeeklySafetyRankingResponse
import com.bikeeyes.app.ui.theme.BikeEyesBorderGray
import com.bikeeyes.app.ui.theme.BikeEyesGreen
import kotlinx.coroutines.launch

@Composable
fun MyPageScreen(
    onRideHistoryClick: () -> Unit,
    onNotificationSettingsClick: () -> Unit,
    onConsentSettingsClick: () -> Unit,
    onServerSettingsClick: () -> Unit,
    onAccountEditClick: () -> Unit,
    onPasswordChangeClick: () -> Unit,
    onAccountDeleteClick: () -> Unit,
    onAdminDiagnosticsClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    val context = LocalContext.current
    val store = remember(context) { UserSettingsStore(context) }
    val profile = store.loadProfile()
    val repository = remember(context) { AuthRepository(context) }
    val scope = rememberCoroutineScope()
    var logoutError by remember { mutableStateOf<String?>(null) }
    var safetyActivity by remember { mutableStateOf<SafetyContributionResponse?>(null) }
    var weeklyRanking by remember { mutableStateOf<WeeklySafetyRankingResponse?>(null) }
    var safetyActivityError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repository) {
        runCatching { repository.safetyContributions() }
            .onSuccess { safetyActivity = it }
            .onFailure { safetyActivityError = AuthRepository.userMessage(it) }
        runCatching { repository.weeklySafetyRanking() }
            .onSuccess { weeklyRanking = it }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("마이페이지", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        ProfileCard(profile)
        SafetyActivityCard(safetyActivity, safetyActivityError)
        WeeklySafetyRankingCard(weeklyRanking)
        MenuCard("내 주행 기록", "저장된 주행 결과와 상세 기록을 확인합니다.", onRideHistoryClick)
        MenuCard("알림 설정", "위험·사고·주행 종료 알림 방식을 설정합니다.", onNotificationSettingsClick)
        MenuCard("개인정보 동의 관리", "필수·선택 동의 내용과 변경 이력을 확인합니다.", onConsentSettingsClick)
        MenuCard("서버 주소 설정", "수집 데이터를 전송할 서버 주소와 연결 상태를 관리합니다.", onServerSettingsClick)
        MenuCard("계정 정보 수정", "이름, 이메일, 휴대전화 번호를 변경합니다.", onAccountEditClick)
        MenuCard("비밀번호 변경", "서버 계정의 비밀번호를 변경합니다.", onPasswordChangeClick)
        MenuCard("회원 탈퇴", "계정과 개인 위치·이미지 데이터를 즉시 삭제합니다.", onAccountDeleteClick)
        if (profile.role == "ADMIN") {
            MenuCard(
                "관리자 대시보드",
                "사용자·주행·위험구간·시스템 운영 현황을 관리합니다.",
                onAdminDiagnosticsClick
            )
        }
        OutlinedButton(
            onClick = {
                scope.launch {
                    runCatching { repository.logout() }
                        .onSuccess { onLogoutClick() }
                        .onFailure { logoutError = AuthRepository.userMessage(it) }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("로그아웃", color = Color(0xFFD92D20))
        }
        logoutError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            "로그아웃해도 단말에 저장된 주행·센서 데이터는 삭제되지 않습니다.",
            color = Color(0xFF667085),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SafetyActivityCard(
    activity: SafetyContributionResponse?,
    error: String?
) {
    var showBadgeGuide by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("나의 BikeEyes 활동", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            when {
                activity != null -> {
                    Text("총 기여 활동 ${activity.total_count}건", color = BikeEyesGreen, fontWeight = FontWeight.Bold)
                    Text("위험구간 현장 확인 ${activity.confirmation_count}건")
                    Text("위험구간 확정 기여 ${activity.establishment_count}건")
                    Text("개선 여부 확인 ${activity.resolution_count}건")
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SafetyBadgeIllustration(activity.badge.current_badge)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    "현재 배지 · ${activity.badge.current_badge ?: "첫 배지 준비 중"}",
                                    fontWeight = FontWeight.Bold,
                                )
                                activity.badge.next_badge?.let { next ->
                                    Text(
                                        "‘$next’까지 ${activity.badge.remaining_to_next}건 남았어요.",
                                        color = Color(0xFF667085),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } ?: Text(
                                    "최고 단계의 안전 배지를 달성했어요!",
                                    color = BikeEyesGreen,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { showBadgeGuide = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("배지 안내 보기") }
                }
                error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                else -> Text("기여 활동을 불러오고 있습니다.", color = Color(0xFF667085))
            }
        }
    }
    if (showBadgeGuide && activity != null) {
        AlertDialog(
            onDismissRequest = { showBadgeGuide = false },
            title = { Text("BikeEyes 안전 배지 안내") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "배지는 위험구간 현장 확인, 위험구간 확정 기여, 개선 여부 확인처럼 " +
                            "다른 라이더의 안전에 도움이 되는 활동을 모아 드립니다."
                    )
                    activity.badge.thresholds.forEach { level ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SafetyBadgeIllustration(level.name, compact = true)
                            Spacer(Modifier.width(10.dp))
                            Text("${level.name} · 누적 ${level.threshold}건", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Text(
                        "같은 의견을 반복해서 눌러도 중복으로 집계되지 않습니다.",
                        color = Color(0xFF667085),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showBadgeGuide = false }) { Text("확인") }
            },
        )
    }
}

@Composable
private fun SafetyBadgeIllustration(badgeName: String?, compact: Boolean = false) {
    val size = if (compact) 42.dp else 70.dp
    val (emoji, background) = when (badgeName) {
        "BikeEyes 가디언" -> "🦸" to Color(0xFFFFE4A3)
        "안전 라이더" -> "🛡️" to Color(0xFFCCE5FF)
        "도로 관찰자" -> "🔎" to Color(0xFFDDF7E7)
        else -> "🌱" to Color(0xFFEDE9FE)
    }
    Card(
        modifier = Modifier.size(size),
        shape = CircleShape,
        colors = CardDefaults.cardColors(containerColor = background),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge)
        }
    }
}

@Composable
private fun WeeklySafetyRankingCard(ranking: WeeklySafetyRankingResponse?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, BikeEyesBorderGray),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("이번 주 BikeEyes 안전 기여자", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            when {
                ranking == null -> Text("주간 순위를 불러오고 있습니다.", color = Color(0xFF667085))
                ranking.items.isEmpty() -> Text("이번 주 등록된 기여 활동이 없습니다.", color = Color(0xFF667085))
                else -> ranking.items.forEach { item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${item.rank}. ${item.nickname}")
                        Text("${item.contribution_count}건", color = BikeEyesGreen, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
fun ServerSettingsScreen(onSaved: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { UserSettingsStore(context) }
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(store.loadServerBaseUrl()) }
    var testing by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var connectionSucceeded by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = "서버 주소 설정",
        description = "PC와 휴대폰이 같은 네트워크에 있을 때 PC의 IPv4 주소와 서버 포트를 입력하세요."
    ) {
        OutlinedTextField(
            value = address,
            onValueChange = {
                address = it
                resultMessage = null
                connectionSucceeded = false
            },
            label = { Text("서버 기본 주소") },
            placeholder = { Text("http://192.168.0.10:8000") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Text(
            "예: http://192.168.10.111:8000\n주소 끝의 /는 자동으로 처리됩니다.",
            color = Color(0xFF667085),
            style = MaterialTheme.typography.bodySmall
        )
        resultMessage?.let {
            Text(
                it,
                color = if (connectionSucceeded) BikeEyesGreen else MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )
        }
        OutlinedButton(
            onClick = {
                if (!UserSettingsStore.isValidServerBaseUrl(address)) {
                    resultMessage = "올바른 서버 주소를 입력해주세요."
                    connectionSucceeded = false
                } else {
                    testing = true
                    resultMessage = "연결을 확인하고 있습니다."
                    scope.launch {
                        NetworkModule.testConnection(address)
                            .onSuccess { elapsed ->
                                connectionSucceeded = true
                                resultMessage = "서버 연결 성공 (${elapsed}ms)"
                            }
                            .onFailure { error ->
                                connectionSucceeded = false
                                resultMessage = "연결 실패: ${error.message ?: "서버에 접근할 수 없습니다."}"
                            }
                        testing = false
                    }
                }
            },
            enabled = !testing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (testing) "연결 확인 중" else "연결 테스트")
        }
        Button(
            onClick = {
                if (UserSettingsStore.isValidServerBaseUrl(address)) {
                    store.saveServerBaseUrl(address)
                    NetworkModule.onServerAddressChanged()
                    onSaved()
                } else {
                    connectionSucceeded = false
                    resultMessage = "올바른 서버 주소를 입력해주세요."
                }
            },
            enabled = !testing,
            modifier = Modifier.fillMaxWidth()
        ) { Text("주소 저장") }
        Text(
            "주소를 저장하면 다음 전송 시점부터 새 서버로 자동 연결됩니다. 앱 재시작은 필요하지 않습니다.",
            color = Color(0xFF667085),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ProfileCard(profile: UserProfile) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF7EF)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(profile.nickname, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(profile.email.ifBlank { "이메일 미설정" }, color = Color(0xFF667085))
            Text(profile.phone.ifBlank { "휴대전화 번호 미설정" }, color = Color(0xFF667085))
            Spacer(Modifier.height(4.dp))
            Text("단말 저장 계정", color = BikeEyesGreen, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MenuCard(title: String, description: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        border = BorderStroke(1.dp, BikeEyesBorderGray),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(description, color = Color(0xFF667085), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun NotificationSettingsScreen(onSaved: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { UserSettingsStore(context) }
    var settings by remember { mutableStateOf(store.loadNotifications()) }

    SettingsScaffold(
        title = "알림 설정",
        description = "설정은 단말에 저장되며 실제 시스템 알림 권한과 별도로 적용됩니다."
    ) {
        SettingSwitch("위험구간 접근 알림", "위험지도 기반 접근 알림", settings.hazardAlerts) {
            settings = settings.copy(hazardAlerts = it)
        }
        SettingSwitch("사고 의심 확인 알림", "센서 기반 사고 확인 팝업", settings.accidentAlerts) {
            settings = settings.copy(accidentAlerts = it)
        }
        SettingSwitch("주행 종료 요약 알림", "주행 종료 후 결과 안내", settings.rideSummaryAlerts) {
            settings = settings.copy(rideSummaryAlerts = it)
        }
        SettingSwitch("진동", "알림 발생 시 진동 사용", settings.vibration) {
            settings = settings.copy(vibration = it)
        }
        SettingSwitch("소리", "알림 발생 시 소리 사용", settings.sound) {
            settings = settings.copy(sound = it)
        }
        Button(
            onClick = {
                store.saveNotifications(settings)
                onSaved()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("저장") }
    }
}

@Composable
fun AccountEditScreen(onSaved: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { UserSettingsStore(context) }
    val initial = remember { store.loadProfile() }
    val repository = remember(context) { AuthRepository(context) }
    val scope = rememberCoroutineScope()
    var nickname by remember { mutableStateOf(initial.nickname) }
    var email by remember { mutableStateOf(initial.email) }
    var phone by remember { mutableStateOf(initial.phone) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    SettingsScaffold("계정 정보 수정", "닉네임과 휴대전화 번호를 서버 계정에 저장합니다. 로그인 이메일은 변경하지 않습니다.") {
        OutlinedTextField(
            nickname, { nickname = it }, label = { Text("닉네임") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            email, {}, label = { Text("이메일 (변경 불가)") },
            enabled = false, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(phone, { phone = it }, label = { Text("휴대전화 번호") }, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = !loading,
            onClick = {
                error = when {
                    nickname.isBlank() -> "닉네임을 입력해주세요."
                    email.isNotBlank() && !email.contains("@") -> "올바른 이메일 형식을 입력해주세요."
                    else -> null
                }
                if (error == null) {
                    loading = true
                    scope.launch {
                        runCatching { repository.updateProfile(nickname, phone) }
                            .onSuccess { onSaved() }
                            .onFailure {
                                error = AuthRepository.userMessage(it)
                                loading = false
                            }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (loading) "저장 중..." else "저장") }
    }
}

@Composable
fun PasswordChangeScreen(onChanged: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { AuthRepository(context) }
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    SettingsScaffold(
        "비밀번호 변경",
        "현재 비밀번호를 확인한 후 변경합니다. 변경이 완료되면 모든 기기에서 다시 로그인해야 합니다."
    ) {
        PasswordField("현재 비밀번호", current) { current = it }
        PasswordField("새 비밀번호", newPassword) { newPassword = it }
        PasswordField("새 비밀번호 확인", confirmation) { confirmation = it }
        Text("8자 이상이며 특수문자를 1개 이상 포함해야 합니다.", color = Color(0xFF667085), style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = !loading,
            onClick = {
                error = when {
                    current.isBlank() -> "현재 비밀번호를 입력해주세요."
                    !AuthRepository.isValidPassword(newPassword) -> "비밀번호 조건을 확인해주세요."
                    newPassword != confirmation -> "새 비밀번호가 서로 일치하지 않습니다."
                    else -> null
                }
                if (error == null) {
                    loading = true
                    scope.launch {
                        runCatching { repository.changePassword(current, newPassword) }
                            .onSuccess { onChanged() }
                            .onFailure {
                                error = AuthRepository.userMessage(it)
                                loading = false
                            }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (loading) "변경 중..." else "변경 완료") }
    }
}

@Composable
fun AccountDeleteScreen(onDeleted: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { AuthRepository(context) }
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SettingsScaffold(
        "회원 탈퇴",
        "즉시 처리되며 복구할 수 없습니다. GPS·주행·사고·원본 이미지는 삭제되고, 선택 동의가 있는 센서값만 식별정보 없이 유지됩니다."
    ) {
        PasswordField("현재 비밀번호", password) { password = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(
                checked = confirmed,
                onCheckedChange = { confirmed = it }
            )
            Text("삭제 정책을 확인했으며 회원 탈퇴에 동의합니다.")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            enabled = !loading,
            onClick = {
                error = when {
                    password.isBlank() -> "현재 비밀번호를 입력해주세요."
                    !confirmed -> "회원 탈퇴 동의를 확인해주세요."
                    else -> null
                }
                if (error == null) {
                    loading = true
                    scope.launch {
                        runCatching { repository.deleteAccount(password) }
                            .onSuccess { onDeleted() }
                            .onFailure {
                                error = AuthRepository.userMessage(it)
                                loading = false
                            }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = Color(0xFFD92D20)
            )
        ) { Text(if (loading) "처리 중..." else "회원 탈퇴") }
    }
}

@Composable
private fun PasswordField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation()
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, BikeEyesBorderGray),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(description, color = Color(0xFF667085), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun SettingsScaffold(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(description, color = Color(0xFF667085), style = MaterialTheme.typography.bodySmall)
        content()
    }
}
