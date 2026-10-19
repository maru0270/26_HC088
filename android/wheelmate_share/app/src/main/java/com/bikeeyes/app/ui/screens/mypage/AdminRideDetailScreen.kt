package com.bikeeyes.app.ui.screens.mypage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.AdminAccidentRecord
import com.bikeeyes.app.AdminRideDetailResponse
import com.bikeeyes.app.AdminSensorRecord
import com.bikeeyes.app.AuthRepository
import com.bikeeyes.app.UserSettingsStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt
import kotlinx.coroutines.launch

@Composable
fun AdminRideDetailScreen(
    sessionId: String,
    onAccidentClick: (String) -> Unit = {},
    onBack: () -> Unit,
    onDashboard: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { AuthRepository(context) }
    val profile = remember { UserSettingsStore(context).loadProfile() }
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<AdminRideDetailResponse?>(null) }
    var selectedImage by remember { mutableStateOf<Bitmap?>(null) }
    var selectedImageIndex by remember { mutableStateOf(-1) }
    var selectedImageEventUuid by remember { mutableStateOf<String?>(null) }
    var imageRequestGeneration by remember { mutableLongStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var imageLoading by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadImage(eventUuid: String, imageIndex: Int) {
        val requestGeneration = imageRequestGeneration + 1L
        imageRequestGeneration = requestGeneration
        selectedImageIndex = imageIndex
        selectedImageEventUuid = eventUuid
        selectedImage = null
        imageError = null
        imageLoading = true
        scope.launch {
            val result = runCatching {
                val bytes = repository.adminSensorImage(eventUuid)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: error("이미지 디코딩 실패")
            }
            if (
                imageRequestGeneration != requestGeneration ||
                selectedImageEventUuid != eventUuid
            ) return@launch
            result.onSuccess { selectedImage = it }
                .onFailure { imageError = "이미지가 만료되었거나 파일을 불러올 수 없습니다." }
            imageLoading = false
        }
    }

    fun loadDetail() {
        if (profile.role != "ADMIN") {
            error = "관리자 계정만 접근할 수 있습니다."
            loading = false
            return
        }
        loading = true
        error = null
        scope.launch {
            runCatching { repository.adminRideDetail(sessionId) }
                .onSuccess { response ->
                    detail = response
                    val firstImageEventUuid = response.sensors.firstOrNull {
                        it.has_image && !it.event_uuid.isNullOrBlank()
                    }?.event_uuid
                    if (!firstImageEventUuid.isNullOrBlank()) {
                        loadImage(firstImageEventUuid, 0)
                    } else {
                        imageRequestGeneration++
                        selectedImage = null
                        selectedImageIndex = -1
                        selectedImageEventUuid = null
                        imageError = null
                        imageLoading = false
                    }
                }
                .onFailure { error = AuthRepository.userMessage(it) }
            loading = false
        }
    }

    LaunchedEffect(sessionId) {
        imageRequestGeneration++
        detail = null
        selectedImage = null
        selectedImageIndex = -1
        selectedImageEventUuid = null
        imageError = null
        imageLoading = false
        error = null
        loadDetail()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AdminScreenHeader(
            title = "주행 기록 상세",
            description = "주행 요약과 센서·GPS·이미지·사고 데이터를 확인합니다.",
            onBack = onBack,
            onDashboard = onDashboard
        )

        when {
            loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            error != null -> AdminDetailMessage(
                title = "상세 기록을 불러오지 못했습니다.",
                message = error.orEmpty(),
                actionLabel = "다시 시도",
                onAction = { loadDetail() }
            )
            detail != null -> {
                val value = detail!!
                val imageSensors = value.sensors.filter {
                    it.has_image && !it.event_uuid.isNullOrBlank()
                }
                val selectedSensor = imageSensors.firstOrNull {
                    it.event_uuid == selectedImageEventUuid
                }
                RideSummarySection(value)

                AdminSectionTitle("수집 이미지", "총 ${imageSensors.size}장")
                if (imageSensors.isEmpty()) {
                    AdminEmptyCard("이 주행에 연결된 수집 이미지가 없습니다.")
                } else {
                    AdminInlineImagePager(
                        bitmap = selectedImage,
                        imageKey = selectedImageEventUuid,
                        loading = imageLoading,
                        error = imageError,
                        position = selectedImageIndex,
                        total = imageSensors.size,
                        onPrevious = {
                            val previous = selectedImageIndex - 1
                            val eventUuid = imageSensors.getOrNull(previous)?.event_uuid
                            if (!eventUuid.isNullOrBlank()) loadImage(eventUuid, previous)
                        },
                        onNext = {
                            val next = selectedImageIndex + 1
                            val eventUuid = imageSensors.getOrNull(next)?.event_uuid
                            if (!eventUuid.isNullOrBlank()) loadImage(eventUuid, next)
                        }
                    )
                }

                AdminSectionTitle("현재 이미지의 센서·GPS 데이터", "")
                if (selectedSensor == null) {
                    AdminEmptyCard(
                        if (imageSensors.isEmpty()) {
                            "표시할 이미지와 센서 데이터가 없습니다."
                        } else {
                            "이 이미지에 연결된 센서 데이터를 찾을 수 없습니다."
                        }
                    )
                } else {
                    AdminSensorDetailCard(
                        index = selectedImageIndex + 1,
                        sensor = selectedSensor
                    )
                }

                AdminSectionTitle("사고 감지 기록", "총 ${value.accidents.size}건")
                if (value.accidents.isEmpty()) {
                    AdminEmptyCard("이 주행에서 저장된 사고 기록이 없습니다.")
                } else {
                    value.accidents.forEachIndexed { index, accident ->
                        AdminAccidentDetailCard(
                            index = index + 1,
                            accident = accident,
                            onClick = { onAccidentClick(accident.event_uuid) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RideSummarySection(detail: AdminRideDetailResponse) {
    val ride = detail.ride
    AdminPanel("주행 요약") {
        Text(ride.nickname ?: "익명/테스트", fontWeight = FontWeight.Bold)
        Text(ride.email ?: "연결된 사용자 계정 없음", color = Color(0xFF667085))
        HorizontalDivider()
        AdminKeyValue("주행 종료", adminDetailTime(ride.ended_at))
        AdminKeyValue("주행 시간", formatDuration(ride.elapsed_seconds))
        AdminKeyValue("주행 거리", "%.2f km".format(ride.total_distance_meters / 1_000.0))
        AdminKeyValue("평균 속도", "%.1f km/h".format(ride.average_speed_kmh))
        AdminKeyValue("최고 속도", "%.1f km/h".format(ride.max_speed_kmh))
        AdminKeyValue("센서 수신", "${detail.sensor_total}/${ride.expected_sensor_logs}건")
        AdminKeyValue("사고 수신", "${detail.accidents.size}/${ride.expected_accidents}건")
        AdminKeyValue("기기", ride.device_id ?: "미등록")
    }
}

@Composable
private fun AdminSensorDetailCard(
    index: Int,
    sensor: AdminSensorRecord
) {
    val acceleration = sqrt(sensor.accel_x * sensor.accel_x + sensor.accel_y * sensor.accel_y + sensor.accel_z * sensor.accel_z)
    val gyro = sqrt(sensor.gyro_x * sensor.gyro_x + sensor.gyro_y * sensor.gyro_y + sensor.gyro_z * sensor.gyro_z)
    AdminPanel("#$index · ${adminDetailTime(sensor.captured_at)}") {
        AdminKeyValue("GPS", " 위도 : ${coordinate(sensor.latitude)}, 경도 : ${coordinate(sensor.longitude)}")
        AdminKeyValue("GPS 정확도", sensor.accuracy?.let { "%.1f m".format(it) } ?: "-")
        AdminKeyValue("속도", sensor.speed?.let { "%.2f m/s".format(it) } ?: "-")
        AdminKeyValue(
            "가속도 X / Y / Z",
            "%.2f / %.2f / %.2f m/s²".format(sensor.accel_x, sensor.accel_y, sensor.accel_z)
        )
        AdminKeyValue("가속도 합성", "%.2f m/s²".format(acceleration))
        AdminKeyValue(
            "자이로 X / Y / Z",
            "%.2f / %.2f / %.2f rad/s".format(sensor.gyro_x, sensor.gyro_y, sensor.gyro_z)
        )
        AdminKeyValue("자이로 합성", "%.2f rad/s".format(gyro))
        AdminKeyValue(
            "지자기 X / Y / Z",
            "%.2f / %.2f / %.2f μT".format(sensor.geomagnetic_x, sensor.geomagnetic_y, sensor.geomagnetic_z)
        )
        Text(
            "현재 표시된 수집 이미지와 연결된 센서 데이터입니다.",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF067647)
        )
    }
}

@Composable
private fun AdminInlineImagePager(
    bitmap: Bitmap?,
    imageKey: String?,
    loading: Boolean,
    error: String?,
    position: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF3)),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("수집 이미지", fontWeight = FontWeight.Bold, color = Color(0xFF067647))
                Text(
                    if (total > 0 && position >= 0) "${position + 1} / $total" else "0 / 0",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                bitmap != null -> key(imageKey.orEmpty()) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "관리자 수집 이미지",
                        modifier = Modifier.fillMaxWidth().height(280.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    enabled = !loading && position > 0,
                    onClick = onPrevious
                ) { Text("‹ 이전") }
                Button(
                    enabled = !loading && position >= 0 && position < total - 1,
                    onClick = onNext
                ) { Text("다음 ›") }
            }
        }
    }
}

@Composable
private fun AdminAccidentDetailCard(
    index: Int,
    accident: AdminAccidentRecord,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                "#$index · ${adminDetailTime(accident.detected_at)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            AdminKeyValue("응답 상태", accident.confirmation_status)
            AdminKeyValue("오탐", if (accident.is_false_positive) "예" else "아니요")
            AdminKeyValue("충격", "%.2f m/s²".format(accident.acceleration_magnitude))
            AdminKeyValue("회전", "%.2f rad/s".format(accident.gyro_magnitude))
            AdminKeyValue("GPS", "${coordinate(accident.latitude)}, ${coordinate(accident.longitude)}")
            Text(
                "사고 감지 기록 상세 보기 ›",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF067647)
            )
        }
    }
}

@Composable
private fun AdminPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun AdminKeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color(0xFF667085))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AdminSectionTitle(title: String, count: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(count, style = MaterialTheme.typography.bodySmall, color = Color(0xFF067647))
    }
}

@Composable
private fun AdminEmptyCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF2F4F7))
    ) { Text(message, Modifier.padding(16.dp), color = Color(0xFF667085)) }
}

@Composable
private fun AdminDetailMessage(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4ED))
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Color(0xFFB42318))
            Text(message, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

private fun adminDetailTime(value: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA).format(Date(value))

private fun coordinate(value: Double?): String = value?.let { "%.5f".format(it) } ?: "-"

private fun formatDuration(seconds: Long): String =
    "%d시간 %02d분 %02d초".format(seconds / 3_600, (seconds % 3_600) / 60, seconds % 60)
