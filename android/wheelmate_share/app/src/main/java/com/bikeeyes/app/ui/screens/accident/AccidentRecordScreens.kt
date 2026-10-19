package com.bikeeyes.app.ui.screens.accident

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.AccidentEventEntity
import com.bikeeyes.app.AccidentImageItem
import com.bikeeyes.app.BikeEyesLocalDatabase
import com.bikeeyes.app.NetworkModule
import com.bikeeyes.app.ui.components.BikeEyesKakaoMap
import com.bikeeyes.app.ui.components.BikeEyesMapMarker
import com.bikeeyes.app.ui.theme.BikeEyesBorderGray
import com.bikeeyes.app.ui.theme.BikeEyesGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AccidentRecordListScreen(
    sessionId: String? = null,
    onEventClick: (String) -> Unit
) {
    val dao = BikeEyesLocalDatabase.getInstance(LocalContext.current).accidentEventDao()
    val events by (if (sessionId.isNullOrBlank()) dao.observeAll() else dao.observeByRideSession(sessionId))
        .collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(
            text = if (sessionId.isNullOrBlank()) "사고 감지 기록" else "이 주행의 사고 기록",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))
        if (events.isEmpty()) {
            Text("저장된 사고 감지 기록이 없습니다.", color = Color(0xFF667085))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(events, key = { it.event_uuid }) { event ->
                    AccidentEventCard(event) { onEventClick(event.event_uuid) }
                }
            }
        }
    }
}

@Composable
private fun AccidentEventCard(event: AccidentEventEntity, onClick: () -> Unit) {
    val accent = if (event.is_false_positive) BikeEyesGreen else Color(0xFFD92D20)
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BikeEyesBorderGray),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(confirmationText(event), fontWeight = FontWeight.Bold, color = accent)
                Text(syncText(event.sync_status), color = syncColor(event.sync_status))
            }
            Spacer(Modifier.height(8.dp))
            Text(formatDate(event.detected_at), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(locationSummary(event), color = Color(0xFF667085), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun AccidentRecordDetailScreen(eventUuid: String) {
    val event by BikeEyesLocalDatabase.getInstance(LocalContext.current)
        .accidentEventDao().observeByEventUuid(eventUuid).collectAsState(initial = null)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
    ) {
        Text("사고 감지 기록 상세", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        event?.let { AccidentDetailContent(it) }
            ?: Text("사고 기록을 불러오는 중입니다.", color = Color(0xFF667085))
    }
}

@Composable
private fun AccidentDetailContent(event: AccidentEventEntity) {
    if (event.latitude != null && event.longitude != null) {
        BikeEyesKakaoMap(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(16.dp)),
            initialLatitude = event.latitude,
            initialLongitude = event.longitude,
            showCurrentLocation = false,
            markers = listOf(
                BikeEyesMapMarker(
                    id = event.event_uuid,
                    latitude = event.latitude,
                    longitude = event.longitude
                )
            )
        )
        Spacer(Modifier.height(14.dp))
    }
    AccidentBlackboxImages(event)
    Spacer(Modifier.height(14.dp))
    val values = listOf(
        "판정 결과" to confirmationText(event),
        "서버 전송" to syncText(event.sync_status),
        "발생 시각" to formatDate(event.detected_at),
        "당시 속도" to event.speed?.let { String.format(Locale.US, "%.1f km/h", it * 3.6) }.orEmpty().ifBlank { "정보 없음" },
        "가속도 크기" to String.format(Locale.US, "%.2f m/s²", event.acceleration_magnitude),
        "자이로 크기" to String.format(Locale.US, "%.2f rad/s", event.gyro_magnitude),
        "GPS 정확도" to event.accuracy?.let { String.format(Locale.US, "%.1f m", it) }.orEmpty().ifBlank { "정보 없음" },
        "위치" to locationSummary(event)
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        values.forEach { (label, value) ->
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, BikeEyesBorderGray),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(label, color = Color(0xFF667085), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(value, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Text("이벤트 ID: ${event.event_uuid}", color = Color(0xFF667085), style = MaterialTheme.typography.bodySmall)
}

private sealed interface AccidentImageListState {
    data object Loading : AccidentImageListState
    data class Available(
        val lookbackSeconds: Int,
        val items: List<AccidentImageItem>
    ) : AccidentImageListState
    data class Unavailable(val message: String) : AccidentImageListState
}

private sealed interface AccidentImageState {
    data object Loading : AccidentImageState
    data class Available(val bitmap: Bitmap) : AccidentImageState
    data class Unavailable(val message: String) : AccidentImageState
}

@Composable
private fun AccidentBlackboxImages(event: AccidentEventEntity) {
    val context = LocalContext.current
    val listState by produceState<AccidentImageListState>(
        initialValue = AccidentImageListState.Loading,
        key1 = event.event_uuid,
        key2 = event.sync_status
    ) {
        value = AccidentImageListState.Loading
        value = when {
            event.sync_status != "SYNCED" ->
                AccidentImageListState.Unavailable(
                    "사고 기록과 이미지가 서버에 전송된 뒤 표시됩니다."
                )
            else -> withContext(Dispatchers.IO) {
                runCatching {
                    NetworkModule.dynamicSensorApi(context).getAccidentImages(event.event_uuid)
                }.fold(
                    onSuccess = { response ->
                        AccidentImageListState.Available(
                            response.lookback_seconds,
                            response.items
                        )
                    },
                    onFailure = {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        AccidentImageListState.Unavailable(
                            "사고 시점의 수집 이미지를 불러오지 못했습니다."
                        )
                    }
                )
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BikeEyesBorderGray),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("사고 감지 시점의 수집 이미지", fontWeight = FontWeight.Bold)
            if (event.confirmation_status == "SAFE" || event.is_false_positive) {
                Text("괜찮아요 기록은 별도 블랙박스 보존 대상은 아니지만, 당시 이미지가 남아 있으면 표시합니다.",
                    color = Color(0xFF667085), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            when (val state = listState) {
                AccidentImageListState.Loading ->
                    Text("보존 이미지를 확인하는 중입니다.", color = Color(0xFF667085))
                is AccidentImageListState.Unavailable ->
                    Text(state.message, color = Color(0xFF667085))
                is AccidentImageListState.Available -> {
                    val minutes = state.lookbackSeconds / 60
                    val seconds = state.lookbackSeconds % 60
                    Text(
                        "감지 시점 이전 최대 ${minutes}분 ${seconds}초 · ${state.items.size}장",
                        color = Color(0xFF667085),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(12.dp))
                    if (state.items.isEmpty()) {
                        Text(
                            "해당 주행의 감지 시점 이전에 조회 가능한 이미지가 없습니다. 미수집·미전송 또는 삭제된 이미지는 표시할 수 없습니다.",
                            color = Color(0xFF667085)
                        )
                    } else {
                        AccidentImagePager(event.event_uuid, state.items)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccidentImagePager(
    accidentEventUuid: String,
    items: List<AccidentImageItem>
) {
    var selectedIndex by remember(accidentEventUuid, items) { mutableIntStateOf(0) }
    val safeIndex = selectedIndex.coerceIn(0, items.lastIndex)
    if (safeIndex != selectedIndex) selectedIndex = safeIndex
    val selected = items[safeIndex]
    val context = LocalContext.current
    val imageState by produceState<AccidentImageState>(
        initialValue = AccidentImageState.Loading,
        key1 = selected.event_uuid
    ) {
        value = AccidentImageState.Loading
        value = withContext(Dispatchers.IO) {
            runCatching {
                NetworkModule.dynamicSensorApi(context)
                    .downloadSensorImage(selected.event_uuid)
                    .use { body ->
                        val bytes = body.bytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
            }.onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
            }.getOrNull()?.let(AccidentImageState::Available)
                ?: AccidentImageState.Unavailable(
                    "이미지를 불러오지 못했습니다. 보존 상태를 확인해 주세요."
                )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center
    ) {
        when (val state = imageState) {
            AccidentImageState.Loading ->
                Text("이미지를 불러오는 중입니다.", color = Color(0xFF667085))
            is AccidentImageState.Available -> Image(
                bitmap = state.bitmap.asImageBitmap(),
                contentDescription = "사고 직전 블랙박스 이미지",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            is AccidentImageState.Unavailable ->
                Text(state.message, color = Color(0xFF667085))
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "${safeIndex + 1} / ${items.size} · ${formatDate(selected.captured_at)}",
        color = Color(0xFF667085),
        style = MaterialTheme.typography.bodySmall
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = { selectedIndex-- },
            enabled = safeIndex > 0,
            modifier = Modifier.weight(1f)
        ) { Text("이전") }
        OutlinedButton(
            onClick = { selectedIndex++ },
            enabled = safeIndex < items.lastIndex,
            modifier = Modifier.weight(1f)
        ) { Text("다음") }
    }
}

private fun confirmationText(event: AccidentEventEntity) = when (event.confirmation_status) {
    "ACCIDENT" -> "사고입니다"
    "NO_RESPONSE" -> "30초 무응답"
    "SAFE" -> "괜찮아요 · 오탐"
    else -> event.confirmation_status
}

private fun syncText(status: String) = when (status) {
    "SYNCED" -> "전송 완료"
    "FAILED" -> "재전송 대기"
    else -> "전송 대기"
}

private fun syncColor(status: String) = if (status == "SYNCED") BikeEyesGreen else Color(0xFFF79009)
private fun formatDate(timestamp: Long) =
    SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA).format(Date(timestamp))

private fun locationSummary(event: AccidentEventEntity): String =
    if (event.latitude == null || event.longitude == null) "위치 정보 없음"
    else String.format(Locale.US, "%.6f, %.6f", event.latitude, event.longitude)
