package com.bikeeyes.app.ui.screens.mypage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.AdminRideDetailResponse
import com.bikeeyes.app.AdminRideItem
import com.bikeeyes.app.AuthRepository
import com.bikeeyes.app.UserSettingsStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

@Composable
fun AdminRecordsScreen(
    onRideClick: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onDashboard: () -> Unit = {}
) {
    val context = LocalContext.current
    val profile = remember { UserSettingsStore(context).loadProfile() }
    val repository = remember { AuthRepository(context) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("ALL") }
    var selectedYear by remember { mutableStateOf<Int?>(null) }
    var selectedMonth by remember { mutableStateOf<Int?>(null) }
    var selectedDay by remember { mutableStateOf<Int?>(null) }
    var rides by remember { mutableStateOf<List<AdminRideItem>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<AdminRideDetailResponse?>(null) }
    var selectedImage by remember { mutableStateOf<Bitmap?>(null) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        if (profile.role != "ADMIN") {
            error = "관리자 계정만 진입할 수 있습니다."
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching { repository.adminRides(query, status, selectedYear, selectedMonth, selectedDay) }
            .onSuccess {
                rides = it.items
                total = it.total
            }
            .onFailure { error = AuthRepository.userMessage(it) }
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AdminScreenHeader(
            "주행 기록 조회",
            "사용자별 주행 결과와 데이터 동기화 상태를 확인합니다.",
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
            detail = null
            refreshKey++
        }, enabled = !loading && profile.role == "ADMIN")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            listOf(
                "ALL" to "전체",
                "COMPLETE" to "완료",
                "INCOMPLETE" to "미완료",
                "DELETED" to "삭제"
            ).forEach { item ->
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !loading && status != item.first,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 2.dp,
                        vertical = 8.dp
                    ),
                    onClick = {
                        status = item.first
                        detail = null
                        refreshKey++
                    }
                ) {
                    Text(item.second, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading && profile.role == "ADMIN",
            onClick = {
                detail = null
                refreshKey++
            }
        ) {
            Text("기록 검색")
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("검색 결과 ${total}건", fontWeight = FontWeight.SemiBold)

        rides.forEach { ride ->
            AdminRecordCard(
                ride = ride,
                enabled = !loading,
                onClick = {
                    onRideClick(ride.ride_session_id)
                }
            )
        }
        detail?.let {
            AdminRecordDetail(
                detail = it,
                onImageClick = { eventUuid ->
                    loading = true
                    imageError = null
                    scope.launch {
                        runCatching {
                            val bytes = repository.adminSensorImage(eventUuid)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?: error("image decode failed")
                        }.onSuccess { bitmap ->
                            selectedImage = bitmap
                        }.onFailure {
                            imageError = "이미지를 불러오지 못했습니다. 파일 보존 상태를 확인해 주세요."
                        }
                        loading = false
                    }
                }
            )
        }
        imageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        selectedImage?.let { bitmap ->
            Text("선택한 수집 이미지", fontWeight = FontWeight.Bold)
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "관리자 노면 수집 이미지",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                contentScale = ContentScale.Fit
            )
        }
    }

}

@Composable
private fun AdminRecordCard(
    ride: AdminRideItem,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "${ride.nickname ?: "익명/테스트"} · ${statusLabel(ride.sync_status)}",
                fontWeight = FontWeight.Bold
            )
            Text(ride.email ?: "계정 연결 없음", style = MaterialTheme.typography.bodySmall)
            Text(
                "${formatTimestamp(ride.ended_at)} · " +
                    "${"%.2f".format(ride.total_distance_meters / 1_000.0)}km · " +
                    "${ride.elapsed_seconds / 60}분",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "센서 ${ride.received_sensor_logs}/${ride.expected_sensor_logs} · " +
                    "이미지 ${ride.image_count} · " +
                    "사고 ${ride.received_accidents}/${ride.expected_accidents}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "기기 ${ride.device_id ?: "미등록"}",
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                onClick = onClick
            ) {
                Text("노면·사고 기록 상세")
            }
        }
    }
}

@Composable
private fun AdminRecordDetail(
    detail: AdminRideDetailResponse,
    onImageClick: (String) -> Unit
) {
    Text(
        "선택한 주행 상세",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    AdminDetailCard(
        "주행 정보",
        "${detail.ride.nickname ?: "익명/테스트"} · ${detail.ride.email ?: "계정 없음"}\n" +
            "거리 ${"%.2f".format(detail.ride.total_distance_meters / 1_000.0)}km · " +
            "평균 ${"%.1f".format(detail.ride.average_speed_kmh)}km/h · " +
            "최고 ${"%.1f".format(detail.ride.max_speed_kmh)}km/h\n" +
            "센서 ${detail.sensor_total}건 · 사고 ${detail.accidents.size}건"
    )
    Text("노면 수집 기록", fontWeight = FontWeight.Bold)
    if (detail.sensors.isEmpty()) Text("수집된 센서 기록이 없습니다.")
    detail.sensors.take(20).forEach { sensor ->
        val acceleration = sqrt(
            sensor.accel_x * sensor.accel_x +
                sensor.accel_y * sensor.accel_y +
                sensor.accel_z * sensor.accel_z
        )
        val gyro = sqrt(
            sensor.gyro_x * sensor.gyro_x +
                sensor.gyro_y * sensor.gyro_y +
                sensor.gyro_z * sensor.gyro_z
        )
        AdminDetailCard(
            formatTimestamp(sensor.captured_at),
            "가속도 합성 ${"%.2f".format(acceleration)} · 자이로 합성 ${"%.2f".format(gyro)}\n" +
                "GPS ${formatCoordinate(sensor.latitude)}, ${formatCoordinate(sensor.longitude)} · " +
                "이미지 ${if (sensor.has_image) "있음" else "없음"}"
        )
        if (sensor.has_image && !sensor.event_uuid.isNullOrBlank()) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onImageClick(sensor.event_uuid) }
            ) {
                Text("수집 이미지 보기")
            }
        }
    }
    if (detail.sensors_limited || detail.sensors.size > 20) {
        Text(
            "화면에는 최근 20건만 표시합니다. 서버에는 총 ${detail.sensor_total}건이 저장되어 있습니다.",
            style = MaterialTheme.typography.bodySmall
        )
    }
    Text("사고 기록", fontWeight = FontWeight.Bold)
    if (detail.accidents.isEmpty()) Text("사고 기록이 없습니다.")
    detail.accidents.forEach { accident ->
        AdminDetailCard(
            formatTimestamp(accident.detected_at),
            "상태 ${accident.confirmation_status} · " +
                "오탐 ${if (accident.is_false_positive) "예" else "아니오"}\n" +
                "충격 ${"%.2f".format(accident.acceleration_magnitude)} · " +
                "회전 ${"%.2f".format(accident.gyro_magnitude)} · " +
                "GPS ${formatCoordinate(accident.latitude)}, ${formatCoordinate(accident.longitude)}"
        )
    }
}

@Composable
private fun AdminDetailCard(title: String, detail: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    "COMPLETE" -> "동기화 완료"
    "INCOMPLETE" -> "일부 데이터 누락"
    "DELETED" -> "삭제 대기/삭제됨"
    else -> status
}

private fun formatTimestamp(value: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA).format(Date(value))

private fun formatCoordinate(value: Double?): String =
    value?.let { "%.5f".format(it) } ?: "-"
