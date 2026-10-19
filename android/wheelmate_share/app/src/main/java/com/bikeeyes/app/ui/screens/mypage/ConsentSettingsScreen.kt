package com.bikeeyes.app.ui.screens.mypage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.AuthRepository
import com.bikeeyes.app.ConsentHistoryResponse
import com.bikeeyes.app.ConsentResponse
import com.bikeeyes.app.UserDataSummaryResponse
import kotlinx.coroutines.launch

@Composable
fun ConsentSettingsScreen() {
    val context = LocalContext.current
    val repository = remember(context) { AuthRepository(context) }
    val scope = rememberCoroutineScope()
    var consent by remember { mutableStateOf<ConsentResponse?>(null) }
    var history by remember { mutableStateOf<List<ConsentHistoryResponse>>(emptyList()) }
    var ai by remember { mutableStateOf(false) }
    var longTerm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var dataSummary by remember { mutableStateOf<UserDataSummaryResponse?>(null) }

    suspend fun refresh() {
        consent = repository.consents().also {
            ai = it.ai_research_optional
            longTerm = it.long_term_optional
        }
        history = repository.consentHistory()
        dataSummary = repository.dataSummary()
    }
    LaunchedEffect(Unit) {
        runCatching { refresh() }.onFailure { message = AuthRepository.userMessage(it) }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("개인정보 동의 관리", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("현재 정책 버전: ${consent?.policy_version ?: "불러오는 중"}")
        ConsentCard("서비스 이용약관 (필수)", "서비스 제공과 계정 운영에 필요한 약관입니다.", true)
        ConsentCard("개인정보 처리 동의 (필수)", "계정 및 서비스 운영정보 처리에 동의합니다.", true)
        ConsentCard("GPS·센서·이미지 수집 (필수)", "노면 데이터 수집을 위한 필수 동의입니다.", true)
        ConsentCard("AI 학습·연구 재사용 (선택)", "비식별 데이터를 모델 연구에 재사용합니다.", ai) { ai = it }
        ConsentCard("장기 보관·서비스 개선 (선택)", "비식별 데이터를 기본 보관기간 이후에도 유지합니다.", longTerm) { longTerm = it }
        Button(
            onClick = {
                scope.launch {
                    runCatching {
                        repository.updateConsents(ai, longTerm)
                        refresh()
                    }.onSuccess {
                        message = "선택 동의가 저장되었습니다."
                    }.onFailure {
                        message = AuthRepository.userMessage(it)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("동의 설정 저장") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text("내 데이터 현황", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        dataSummary?.let {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "주행 ${it.rides}건 · 센서 ${it.sensor_logs}건 · 사고 ${it.accident_events}건\n" +
                        "7일 내 복구 가능 ${it.recoverable_rides}건\n" +
                        "원본 GPS·센서·이미지 1년 · 시스템 로그 90일 보관",
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        Text("변경 이력", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (history.isEmpty()) Text("변경 이력이 없습니다.")
        history.forEach {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "${it.changed_at}\nAI 연구: ${if (it.ai_research_optional) "동의" else "미동의"} · " +
                        "장기 보관: ${if (it.long_term_optional) "동의" else "미동의"} · ${it.policy_version}",
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun ConsentCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)? = null
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(description, style = MaterialTheme.typography.bodySmall)
            Switch(
                checked = checked,
                enabled = onCheckedChange != null,
                onCheckedChange = { onCheckedChange?.invoke(it) }
            )
        }
    }
}
