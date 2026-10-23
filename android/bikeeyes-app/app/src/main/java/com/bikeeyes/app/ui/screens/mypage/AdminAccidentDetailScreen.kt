package com.bikeeyes.app.ui.screens.mypage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.AccidentImageItem
import com.bikeeyes.app.AuthRepository
import com.bikeeyes.app.NetworkModule
import com.bikeeyes.app.UserSettingsStore
import com.bikeeyes.app.ui.components.BikeEyesKakaoMap
import com.bikeeyes.app.ui.components.BikeEyesMapMarker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface AdminAccidentLoad<out T> {
    data object Loading : AdminAccidentLoad<Nothing>
    data class Ready<T>(val value: T) : AdminAccidentLoad<T>
    data class Failed(val message: String) : AdminAccidentLoad<Nothing>
}

// 취소된 이전 사고/이미지 요청이 새로운 선택을 덮어쓰지 않도록 취소를 전파.
private suspend fun <T> loadAdminAccidentData(block: suspend () -> T): AdminAccidentLoad<T> =
    try {
        AdminAccidentLoad.Ready(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        AdminAccidentLoad.Failed(AuthRepository.userMessage(error))
    }

@Composable
fun AdminAccidentDetailScreen(
    sessionId: String,
    initialFilter: String = "ALL",
    initialEventUuid: String? = null,
    onBack: () -> Unit,
    onDashboard: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember(context) { AuthRepository(context) }
    val isAdmin = remember(context) { UserSettingsStore(context).loadProfile().role == "ADMIN" }
    var refreshKey by remember(sessionId) { mutableIntStateOf(0) }
    // Room으로 다른 사용자의 기록을 복제하지 않는다. 서버에서도 require_admin으로 검사.
    key(sessionId, refreshKey) {
        val detail by produceState<AdminAccidentLoad<com.bikeeyes.app.AdminRideDetailResponse>>(
            initialValue = AdminAccidentLoad.Loading
        ) {
            value = if (!isAdmin) {
                AdminAccidentLoad.Failed("관리자 계정만 접근할 수 있습니다.")
            } else {
                loadAdminAccidentData { repository.adminRideDetail(sessionId) }
            }
        }
        Column(
            Modifier.fillMaxSize().background(Color(0xFFF8FAFC))
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AdminScreenHeader(
                "사고 감지 기록 상세",
                "선택한 사고 감지 기록과 근거 이미지를 확인합니다.",
                onBack, onDashboard
            )
            when (val state = detail) {
                AdminAccidentLoad.Loading -> CircularProgressIndicator()
                is AdminAccidentLoad.Failed -> AccidentDetailPanel("조회 실패") {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    if (isAdmin) OutlinedButton(onClick = { refreshKey++ }) { Text("다시 시도") }
                }
                is AdminAccidentLoad.Ready -> {
                    val response = state.value
                    AccidentDetailPanel("주행 정보") {
                        Text(response.ride.nickname ?: "익명/테스트", fontWeight = FontWeight.Bold)
                        response.ride.email?.let { Text(it) }
                        Text("주행 시작 시간 : ${accidentDetailTime(response.ride.started_at)}")
                        Text("주행 종료 시간 : ${accidentDetailTime(response.ride.ended_at)}")
                        Text("서버에 저장된 사고 기록 ${response.accidents.size}건")
                    }
                    val event = response.accidents.firstOrNull { it.event_uuid == initialEventUuid }
                        ?: response.accidents.maxWithOrNull(
                            compareBy<com.bikeeyes.app.AdminAccidentRecord> { it.detected_at }
                                .thenBy { it.event_uuid }
                        )
                    if (event == null) {
                        AccidentDetailPanel("사고 감지 기록") {
                            Text("이 주행에 저장된 사고 기록이 없습니다.")
                        }
                    } else {
                        // 사고 UUID가 바뀌면 지도, 이미지 목록, 이미지 페이지/비동기 요청을 모두 초기화.
                        key(event.event_uuid) {
                            AccidentDetailPanel("사고 상세 내용") {
                                AccidentValue("사용자 응답", event.responseLabel())
                                AccidentValue("발생 시각", accidentDetailTime(event.detected_at))
                                AccidentValue("서버 저장", "저장 완료")
                                AccidentValue("당시 속도", event.speed?.let { "%.1f km/h".format(it * 3.6) } ?: "정보 없음")
                                AccidentValue("가속도 크기", "%.2f m/s²".format(event.acceleration_magnitude))
                                AccidentValue("자이로 크기", "%.2f rad/s".format(event.gyro_magnitude))
                                AccidentValue("GPS 정확도", event.accuracy?.let { "%.1f m".format(it) } ?: "정보 없음")
                            }
                            val latitude = event.latitude
                            val longitude = event.longitude
                            if (latitude != null && longitude != null) {
                                BikeEyesKakaoMap(
                                    modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp)),
                                    initialLatitude = latitude, initialLongitude = longitude,
                                    showCurrentLocation = false,
                                    blockParentScrollGestures = true,
                                    markers = listOf(BikeEyesMapMarker(
                                        id = event.event_uuid, latitude = latitude, longitude = longitude
                                    ))
                                )
//                                Text("사고 위치: %.6f, %.6f".format(latitude, longitude), style = MaterialTheme.typography.bodySmall)
//                                Text("사고 위치: %.6f, %.6f".format(latitude, longitude), style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("이 사고에 저장된 GPS 위치가 없습니다.", color = Color(0xFF667085))
                            }
                            AdminAccidentImages(event.event_uuid, event.isSafeResponse(), repository)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminAccidentImages(eventUuid: String, isSafeResponse: Boolean, repository: AuthRepository) {
    val context = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    AccidentDetailPanel("사고 감지 시점의 수집 이미지") {
        if (isSafeResponse) {
            Text("괜찮아요 기록은 별도 블랙박스 보존 대상은 아니지만, 당시 이미지가 남아 있으면 표시합니다.",
                style = MaterialTheme.typography.bodySmall)
        }
        key(eventUuid, retry) {
            val images by produceState<AdminAccidentLoad<com.bikeeyes.app.AccidentImageListResponse>>(
                initialValue = AdminAccidentLoad.Loading
            ) {
                value = loadAdminAccidentData {
                    NetworkModule.dynamicSensorApi(context).getAccidentImages(eventUuid)
                }
            }
            when (val state = images) {
                AdminAccidentLoad.Loading -> CircularProgressIndicator()
                is AdminAccidentLoad.Failed -> {
                    Text("사고 이미지 목록을 불러오지 못했습니다. ${state.message}", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { retry++ }) { Text("다시 시도") }
                }
                is AdminAccidentLoad.Ready -> {
                    val response = state.value
                    Text("감지 시점 이전 최대 ${response.lookback_seconds / 60}분 ${response.lookback_seconds % 60}초 · ${response.items.size}장")
                    if (response.items.isEmpty()) {
                        Text("해당 주행의 감지 시점 이전에 조회 가능한 이미지가 없습니다. 미수집·미전송 또는 삭제된 이미지는 표시할 수 없습니다.")
                    } else {
                        AdminAccidentImagePager(response.items, repository)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminAccidentImagePager(items: List<AccidentImageItem>, repository: AuthRepository) {
    var index by remember(items) { mutableIntStateOf(0) }
    val selected = items[index]
    var retry by remember(selected.event_uuid) { mutableIntStateOf(0) }
    // 이미지마다 별도 composition을 사용해 이전 bitmap이 새 번호 아래 남지 않도록 함.
    key(selected.event_uuid, retry) {
        val image by produceState<AdminAccidentLoad<Bitmap>>(initialValue = AdminAccidentLoad.Loading) {
            value = loadAdminAccidentData {
                withContext(Dispatchers.IO) {
                    val bytes = repository.adminSensorImage(selected.event_uuid)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("이미지 디코딩 실패")
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
            when (val state = image) {
                AdminAccidentLoad.Loading -> CircularProgressIndicator()
                is AdminAccidentLoad.Ready -> Image(
                    bitmap = state.value.asImageBitmap(),
                    contentDescription = "선택한 사고의 블랙박스 이미지 ${index + 1}",
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit
                )
                is AdminAccidentLoad.Failed -> Column {
                    Text("이미지를 불러오지 못했습니다. ${state.message}", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { retry++ }) { Text("이미지 다시 시도") }
                }
            }
        }
    }
    Text("${index + 1} / ${items.size} · ${accidentDetailTime(selected.captured_at)}", style = MaterialTheme.typography.bodySmall)
    AdminAccidentImageSensorValues(selected)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(onClick = { index-- }, enabled = index > 0) { Text("‹ 이전 이미지") }
        OutlinedButton(onClick = { index++ }, enabled = index < items.lastIndex) { Text("다음 이미지 ›") }
    }
}

@Composable
private fun AdminAccidentImageSensorValues(item: AccidentImageItem) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("해당 이미지의 센서값", fontWeight = FontWeight.Bold)
        Text(
            "대표 Peak 시각: ${item.peak_sample_at?.let(::accidentDetailTime) ?: "기록 없음"}",
            style = MaterialTheme.typography.bodySmall
        )
        AccidentSensorVector("가속도", item.accel_x, item.accel_y, item.accel_z, "m/s²")
        AccidentSensorVector("자이로", item.gyro_x, item.gyro_y, item.gyro_z, "rad/s")
        AccidentSensorVector("지자기", item.geomagnetic_x, item.geomagnetic_y, item.geomagnetic_z, "μT")
        Text(
            "GPS 속도: ${item.speed?.let { "%.2f m/s (%.1f km/h)".format(it, it * 3.6) } ?: "기록 없음"}",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "GPS 위치: ${item.latitude?.let { "위도 : %.6f".format(it) } ?: "-"}, " +
                "${item.longitude?.let { "경도 : %.6f".format(it) } ?: "-"} · " +
                "정확도 ${item.accuracy?.let { "%.1f m".format(it) } ?: "기록 없음"}",
            style = MaterialTheme.typography.bodySmall
        )
        if (item.sensor_samples.isEmpty()) {
            Text("연결된 원본 센서 샘플이 없습니다.", color = Color(0xFF667085), style = MaterialTheme.typography.bodySmall)
        } else {
            Text("연결된 원본 센서 샘플 ${item.sensor_samples.size}건", fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall)
            item.sensor_samples.forEachIndexed { sampleIndex, sample ->
                Text(
                    "#${sampleIndex + 1} ${accidentDetailTime(sample.sampled_at)} · " +
                        "가속도 %.2f/%.2f/%.2f · 자이로 %.2f/%.2f/%.2f · 지자기 %.2f/%.2f/%.2f".format(
                            sample.accel_x, sample.accel_y, sample.accel_z,
                            sample.gyro_x, sample.gyro_y, sample.gyro_z,
                            sample.geomagnetic_x, sample.geomagnetic_y, sample.geomagnetic_z
                        ),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun AccidentSensorVector(
    label: String,
    x: Double?,
    y: Double?,
    z: Double?,
    unit: String
) {
    val value = if (x == null || y == null || z == null) {
        "기록 없음"
    } else {
        "X %.2f · Y %.2f · Z %.2f %s".format(x, y, z, unit)
    }
    Text("$label: $value", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AccidentDetailPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun AccidentValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFF667085), style = MaterialTheme.typography.bodySmall)
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
    }
}

private fun accidentDetailTime(value: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA).format(Date(value))
