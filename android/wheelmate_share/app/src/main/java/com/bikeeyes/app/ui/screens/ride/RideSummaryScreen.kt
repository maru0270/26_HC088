package com.bikeeyes.app.ui.screens.ride

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.RideResult
import com.bikeeyes.app.RideSyncStatus
import com.bikeeyes.app.RideTrackingState
import com.bikeeyes.app.ui.components.BikeEyesOutlinedButton
import com.bikeeyes.app.ui.components.BikeEyesPrimaryButton
import com.bikeeyes.app.ui.theme.BikeEyesBorderGray
import com.bikeeyes.app.ui.theme.BikeEyesGreen
import java.util.Locale

@Composable
fun RideSummaryScreen(
    onDetailClick: () -> Unit,
    onGoHomeClick: () -> Unit
) {
    val result by RideTrackingState.lastRideResult.collectAsState()

    BackHandler(onBack = onGoHomeClick)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Text(
            text = "주행 종료 요약",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "주행이 완료되었습니다!",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (result == null) {
            EmptyRideResultCard()
        } else {
            RideResultGrid(result = result!!)
            Spacer(modifier = Modifier.height(16.dp))
            SyncStatusCard(status = result!!.syncStatus)
        }

        Spacer(modifier = Modifier.height(36.dp))

        BikeEyesOutlinedButton(
            text = "상세 기록 보기",
            onClick = onDetailClick
        )

        Spacer(modifier = Modifier.height(10.dp))

        BikeEyesPrimaryButton(
            text = "메인으로 돌아가기",
            onClick = onGoHomeClick
        )
    }
}

@Composable
private fun RideResultGrid(result: RideResult) {
    val items = listOf(
        "주행 거리" to String.format(Locale.US, "%.2f km", result.totalDistanceMeters / 1000.0),
        "주행 시간" to formatSummaryDuration(result.elapsedSeconds),
        "평균 속도" to String.format(Locale.US, "%.1f km/h", result.averageSpeedKmh),
        "최고 속도" to String.format(Locale.US, "%.1f km/h", result.maxSpeedKmh),
        "수집 데이터" to "${result.collectedFrameCount}건",
        "사고 감지" to "${result.accidentCount}회"
    )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowItems.forEach { (title, value) ->
                    SummaryStatCard(
                        title = title,
                        value = value,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryStatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.height(94.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BikeEyesBorderGray),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF667085)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SyncStatusCard(status: RideSyncStatus) {
    val (title, description) = when (status) {
        RideSyncStatus.SYNCED -> "동기화 완료" to "수집한 데이터가 서버에 전송되었습니다."
        RideSyncStatus.UPLOADING -> "데이터 업로드 중" to "남은 데이터는 백그라운드에서 계속 전송됩니다."
        RideSyncStatus.PENDING -> "일부 데이터 전송 대기" to "네트워크가 연결되면 자동으로 다시 전송합니다."
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF7EF))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = title,
                color = BikeEyesGreen,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                color = Color(0xFF475467),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun EmptyRideResultCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4E5))
    ) {
        Text(
            text = "저장된 주행 결과를 찾을 수 없습니다.",
            modifier = Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun formatSummaryDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
