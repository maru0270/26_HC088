package com.bikeeyes.app.ui.screens.mypage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.ui.theme.BikeEyesGreen

@Composable
fun AdminRecordsHubScreen(
    onRidesClick: () -> Unit,
    onRoadRecordsClick: () -> Unit,
    onAccidentsClick: () -> Unit,
    onBack: () -> Unit = {},
    onDashboard: () -> Unit = {}
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AdminScreenHeader("기록 관리", "조회할 기록 유형을 선택하세요.", onBack, onDashboard)
        RecordTypeCard(
            "주행 기록 조회",
            "사용자별 주행, 거리, 시간과 동기화 상태를 확인합니다.",
            Color(0xFF2563EB),
            onRidesClick
        )
        RecordTypeCard(
            "위험 구간 기록 조회",
            "노면 센서값, GPS와 수집 이미지를 확인합니다.",
            Color(0xFFF59E0B),
            onRoadRecordsClick
        )
        RecordTypeCard(
            "사고 기록 조회",
            "사고 의심, 사용자 응답과 센서 근거를 확인합니다.",
            Color(0xFFE11D48),
            onAccidentsClick
        )
    }
}

@Composable
private fun RecordTypeCard(title: String, detail: String, accent: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("●", color = accent)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFF667085))
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = BikeEyesGreen)
        }
    }
}
