package com.bikeeyes.app.ui.screens.home

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.BikeEyesLocalDatabase
import com.bikeeyes.app.RideResultEntity
import com.bikeeyes.app.UserSettingsStore
import com.bikeeyes.app.ui.components.BikeEyesOutlinedButton
import com.bikeeyes.app.ui.components.BikeEyesPrimaryButton
import com.bikeeyes.app.ui.components.BikeEyesSummaryCard
import com.bikeeyes.app.ui.theme.BikeEyesBorderGray
import com.bikeeyes.app.ui.theme.BikeEyesGreen
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    onStartRideClick: () -> Unit,
    onRouteSearchClick: () -> Unit,
    onRideHistoryClick: () -> Unit
) {
    val context = LocalContext.current
    val database = remember(context) { BikeEyesLocalDatabase.getInstance(context) }
    val rides by database.rideResultDao().observeAll().collectAsState(initial = emptyList())
    val accidents by database.accidentEventDao().observeAll().collectAsState(initial = emptyList())
    val profile = remember(context) { UserSettingsStore(context).loadProfile() }
    val monday = remember { startOfCurrentWeek() }
    val weeklyRides = rides.filter { it.ended_at >= monday }
    val realAccidents = accidents.count {
        it.detected_at >= monday && it.confirmation_status != "SAFE"
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("BikeEyes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = BikeEyesGreen)
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "안녕하세요, ${profile.nickname.ifBlank { profile.email.substringBefore("@").ifBlank { "라이더" } }}님!",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text("오늘도 안전한 라이딩 되세요.")
        Spacer(Modifier.height(24.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BikeEyesSummaryCard(
                value = String.format(Locale.KOREA, "%.1fkm", weeklyRides.sumOf { it.total_distance_meters } / 1000.0),
                title = "이번 주 주행",
                modifier = Modifier.weight(1f).height(96.dp)
            )
            BikeEyesSummaryCard(
                value = formatDuration(weeklyRides.sumOf { it.elapsed_seconds }),
                title = "전체 이동시간",
                modifier = Modifier.weight(1f).height(96.dp)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BikeEyesSummaryCard(
                value = "0회",
                title = "위험 통과",
                modifier = Modifier.weight(1f).height(96.dp)
            )
            BikeEyesSummaryCard(
                value = "${realAccidents}회",
                title = "사고 감지",
                modifier = Modifier.weight(1f).height(96.dp)
            )
        }
        Text(
            "위험 통과 횟수는 실제 위험구간 API 연결 후 집계됩니다.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(24.dp))
        BikeEyesPrimaryButton(text = "주행 시작", onClick = onStartRideClick)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BikeEyesOutlinedButton("경로 탐색", onRouteSearchClick, Modifier.weight(1f))
            BikeEyesOutlinedButton("주행 기록", onRideHistoryClick, Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
        RecentRideCard(rides.firstOrNull())
    }
}

@Composable
private fun RecentRideCard(ride: RideResultEntity?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BikeEyesBorderGray),
        colors = CardDefaults.cardColors()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("최근 주행 기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (ride == null) {
                Text("아직 저장된 주행 기록이 없습니다.")
            } else {
                Text(SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(ride.ended_at)))
                Text(
                    String.format(
                        Locale.KOREA,
                        "%.1fkm · %s · 평균 %.1fkm/h",
                        ride.total_distance_meters / 1000.0,
                        formatDuration(ride.elapsed_seconds),
                        ride.average_speed_kmh
                    )
                )
                Text("동기화: ${syncLabel(ride.sync_status)}")
            }
        }
    }
}

private fun startOfCurrentWeek(): Long = Calendar.getInstance().run {
    firstDayOfWeek = Calendar.MONDAY
    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
    timeInMillis
}

private fun formatDuration(seconds: Long): String =
    "%02d:%02d".format(Locale.US, seconds / 3600, (seconds % 3600) / 60)

private fun syncLabel(value: String) = when (value) {
    "SYNCED" -> "완료"
    "UPLOADING" -> "전송 중"
    else -> "전송 대기"
}
