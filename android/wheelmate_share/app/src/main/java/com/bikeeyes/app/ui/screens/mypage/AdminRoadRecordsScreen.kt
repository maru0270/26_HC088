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
import androidx.compose.material3.Checkbox
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
import com.bikeeyes.app.AdminSensorRecord
import com.bikeeyes.app.AuthRepository
import com.bikeeyes.app.UserSettingsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt
import kotlinx.coroutines.launch

@Composable
fun AdminRoadRecordsScreen(
    onRideClick: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onDashboard: () -> Unit = {}
) {
    val context = LocalContext.current
    val repository = remember { AuthRepository(context) }
    val profile = remember { UserSettingsStore(context).loadProfile() }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var imageOnly by remember { mutableStateOf(false) }
    var rides by remember { mutableStateOf<List<AdminRideItem>>(emptyList()) }
    var selected by remember { mutableStateOf<AdminRideDetailResponse?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        if (profile.role != "ADMIN") {
            error = "관리자 계정만 접근할 수 있습니다."
            return@LaunchedEffect
        }
        loading = true
        runCatching { repository.adminRides(query, "ALL") }
            .onSuccess { response ->
                rides = response.items.filter {
                    it.received_sensor_logs > 0 && (!imageOnly || it.image_count > 0)
                }
            }
            .onFailure { error = AuthRepository.userMessage(it) }
        loading = false
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AdminScreenHeader(
            "위험 구간 기록 조회",
            "주행별 노면 센서·GPS·이미지 기록을 조회합니다.",
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = imageOnly, onCheckedChange = { imageOnly = it })
            Text("이미지가 있는 기록만")
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading,
            onClick = { selected = null; bitmap = null; refreshKey++ }
        ) { Text("검색") }
        if (loading) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("주행 묶음 ${rides.size}건", fontWeight = FontWeight.Bold)
        rides.forEach { ride ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ride.nickname ?: "익명/테스트", fontWeight = FontWeight.Bold)
                    Text(
                        "${adminTime(ride.ended_at)} · 센서 ${ride.received_sensor_logs}건 · 이미지 ${ride.image_count}개",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(ride.email ?: "계정 연결 없음", style = MaterialTheme.typography.bodySmall)
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading,
                        onClick = {
                            onRideClick(ride.ride_session_id)
                        }
                    ) { Text("노면 데이터 보기") }
                }
            }
        }
        selected?.let { detail ->
            Text("노면 데이터 묶음", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("전체 센서 ${detail.sensor_total}건 · 최근 최대 100건 표시")
            detail.sensors.take(100).forEach { sensor ->
                RoadSensorAdminCard(sensor) { eventUuid ->
                    loading = true
                    scope.launch {
                        runCatching {
                            val bytes = repository.adminSensorImage(eventUuid)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?: error("image decode failed")
                        }.onSuccess { bitmap = it }
                            .onFailure { error = "이미지를 불러오지 못했습니다." }
                        loading = false
                    }
                }
            }
        }
        bitmap?.let {
            Text("수집 이미지", fontWeight = FontWeight.Bold)
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "노면 수집 이미지",
                modifier = Modifier.fillMaxWidth().height(260.dp),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun RoadSensorAdminCard(sensor: AdminSensorRecord, onImageClick: (String) -> Unit) {
    val acceleration = sqrt(sensor.accel_x * sensor.accel_x + sensor.accel_y * sensor.accel_y + sensor.accel_z * sensor.accel_z)
    val gyro = sqrt(sensor.gyro_x * sensor.gyro_x + sensor.gyro_y * sensor.gyro_y + sensor.gyro_z * sensor.gyro_z)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(adminTime(sensor.captured_at), fontWeight = FontWeight.SemiBold)
            Text(
                "가속도 ${"%.2f".format(acceleration)} · 자이로 ${"%.2f".format(gyro)}\n" +
                    "GPS ${sensor.latitude?.let { "%.5f".format(it) } ?: "-"}, " +
                    "${sensor.longitude?.let { "%.5f".format(it) } ?: "-"}",
                style = MaterialTheme.typography.bodySmall
            )
            if (sensor.has_image && !sensor.event_uuid.isNullOrBlank()) {
                Button(onClick = { onImageClick(sensor.event_uuid) }) { Text("이미지 보기") }
            } else {
                Text("이미지 없음", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun adminTime(value: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA).format(Date(value))
