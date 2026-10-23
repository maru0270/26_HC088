package com.bikeeyes.app.ui.screens.mypage

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import com.bikeeyes.app.AdminReviewItem
import com.bikeeyes.app.AdminReviewItemDetail
import com.bikeeyes.app.AuthRepository
import com.bikeeyes.app.SensorSample
import com.bikeeyes.app.ui.components.BikeEyesKakaoMap
import com.bikeeyes.app.ui.components.BikeEyesMapMarker
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

private const val REVIEW_IMPACT_THRESHOLD_MPS2 = 14.0
private const val STANDARD_GRAVITY_MPS2 = 9.80665

private val REVIEW_CLASSES = listOf(
    "pothole" to "포트홀",
    "manhole" to "맨홀",
    "flood_area" to "물웅덩이",
    "bollard" to "볼라드",
    "linear" to "선형 균열",
    "alligator" to "거북등 균열",
)

@Composable
fun AdminReviewItemsScreen() {
    val context = LocalContext.current
    val repository = remember { AuthRepository(context) }
    val scope = rememberCoroutineScope()
    var response by remember { mutableStateOf<com.bikeeyes.app.AdminReviewItemListResponse?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selectedYear by remember { mutableStateOf<Int?>(null) }
    var selectedMonth by remember { mutableStateOf<Int?>(null) }
    var selectedDay by remember { mutableStateOf<Int?>(null) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var selections by remember { mutableStateOf<Map<Long, Set<String>>>(emptyMap()) }
    var successDialogMessage by remember { mutableStateOf<String?>(null) }

    successDialogMessage?.let { dialogMessage ->
        AlertDialog(
            onDismissRequest = { successDialogMessage = null },
            title = { Text("위험구간 후보 등록 완료") },
            text = { Text(dialogMessage) },
            confirmButton = {
                TextButton(onClick = { successDialogMessage = null }) {
                    Text("확인")
                }
            },
        )
    }

    LaunchedEffect(refreshKey) {
        loading = true
        message = null
        runCatching { repository.adminReviewItems(year = selectedYear, month = selectedMonth, day = selectedDay) }
            .onSuccess {
                response = it
                currentIndex = currentIndex.coerceAtMost((it.items.size - 1).coerceAtLeast(0))
            }
            .onFailure { message = AuthRepository.userMessage(it) }
        loading = false
    }

    val items = response?.items.orEmpty()
    val selectedItem = items.getOrNull(currentIndex)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Text("재검토 항목 관리", style = MaterialTheme.typography.headlineSmall)
            Text("AI 미탐지 의심 이미지와 센서 근거를 검토합니다.")
        }
        AdminDateFilter(selectedYear, selectedMonth, selectedDay, { year, month, day ->
            selectedYear = year
            selectedMonth = month
            selectedDay = day
            refreshKey++
        }, enabled = !loading)
        Text("검토 대기 ${response?.pending_count ?: 0}건", color = MaterialTheme.colorScheme.primary)
        if (loading) CircularProgressIndicator()
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (selectedItem == null) {
            Text("현재 검토할 재검토 항목이 없습니다.")
        } else {
            Text("${currentIndex + 1} / ${items.size}", style = MaterialTheme.typography.titleMedium)
            ReviewItemCard(
                item = selectedItem,
                selected = selections[selectedItem.review_item_id].orEmpty(),
                onToggleClass = { className ->
                    val current = selections[selectedItem.review_item_id].orEmpty().toMutableSet()
                    if (!current.add(className)) current.remove(className)
                    selections = selections + (selectedItem.review_item_id to current)
                },
                onRegister = {
                    val classes = selections[selectedItem.review_item_id].orEmpty().toList()
                    if (classes.isEmpty()) {
                        message = "후보 등록 전에 위험요소 종류를 하나 이상 선택해주세요."
                    } else {
                        scope.launch {
                            runCatching { repository.registerReviewItem(selectedItem.review_item_id, classes) }
                                .onSuccess {
                                    val selectedLabels = REVIEW_CLASSES
                                        .filter { (className, _) -> className in classes }
                                        .joinToString(", ") { (_, label) -> label }
                                    successDialogMessage = "$selectedLabels 클래스로 등록되었습니다."
                                    selections = selections - selectedItem.review_item_id
                                    refreshKey++
                                }
                                .onFailure { message = AuthRepository.userMessage(it) }
                        }
                    }
                },
                onDismiss = {
                    scope.launch {
                        runCatching { repository.dismissReviewItem(selectedItem.review_item_id) }
                            .onSuccess {
                                message = "해당 사항 없음으로 처리했습니다. 이미지 삭제가 2분 후 예약되었습니다."
                                refreshKey++
                            }
                            .onFailure { message = AuthRepository.userMessage(it) }
                    }
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = { currentIndex-- }, enabled = currentIndex > 0) { Text("이전") }
                Button(onClick = { currentIndex++ }, enabled = currentIndex < items.lastIndex) { Text("다음") }
            }
        }
    }
}

@Composable
private fun ReviewItemCard(
    item: AdminReviewItem,
    selected: Set<String>,
    onToggleClass: (String) -> Unit,
    onRegister: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { AuthRepository(context) }
    val detail by produceState<AdminReviewItemDetail?>(initialValue = null, item.review_item_id) {
        value = runCatching { repository.adminReviewItemDetail(item.review_item_id) }.getOrNull()
    }
    var image by remember(item.review_item_id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var imageLoading by remember(item.review_item_id) { mutableStateOf(true) }
    var imageError by remember(item.review_item_id) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.review_item_id) {
        imageLoading = true
        imageError = null
        image = null
        runCatching {
            val bytes = repository.adminReviewItemImage(item.review_item_id)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: error("서버 응답을 이미지로 해석할 수 없습니다.")
        }.onSuccess {
            image = it
        }.onFailure {
            imageError = AuthRepository.userMessage(it)
        }
        imageLoading = false
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI 탐지 결과 없음", style = MaterialTheme.typography.titleMedium)
            Text("촬영 시각 : ${formatReviewTime(item.captured_at)}")
            Text(
                text = "충격 : ${"%.2f".format(item.peak_impact_delta)} m/s²",
                color = if (item.peak_impact_delta >= REVIEW_IMPACT_THRESHOLD_MPS2) {
                    Color(0xFFD92D20)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text("속도 : ${"%.2f".format(item.speed_median)} m/s")
            Text("위치 : ${item.latitude?.let { "위도 : %.6f".format(it) } ?: "-"}, ${item.longitude?.let { "경도 : %.6f".format(it) } ?: "-"}")
            Text(
                "기준 : 충격 ${"%.1f".format(REVIEW_IMPACT_THRESHOLD_MPS2)} m/s² 이상, " +
                    "속도 1.5 m/s 이상"
            )
            when {
                imageLoading -> CircularProgressIndicator()
                image != null -> Image(
                    bitmap = image!!.asImageBitmap(),
                    contentDescription = "재검토 원본 이미지",
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentScale = ContentScale.Fit,
                )
                else -> Text(
                    "재검토 이미지를 불러오지 못했습니다. ${imageError.orEmpty()}",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ReviewImageLocationMap(item)
            if (detail == null) {
                Text("수집 센서 데이터를 불러오는 중입니다…")
            } else {
                ReviewSensorData(detail!!)
            }
            Text("위험요소 종류를 복수 선택할 수 있습니다.")
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                REVIEW_CLASSES.forEach { (name, label) ->
                    val isSelected = name in selected
                    FilterChip(
                        selected = isSelected,
                        onClick = { onToggleClass(name) },
                        label = { Text(label) },
                        border = if (isSelected) BorderStroke(2.dp, Color.Black) else null,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRegister) { Text("위험 구간 후보 등록") }
                Button(onClick = onDismiss) { Text("해당 사항 없음") }
            }
        }
    }
}

@Composable
private fun ReviewImageLocationMap(item: AdminReviewItem) {
    val latitude = item.latitude
    val longitude = item.longitude
    val hasValidLocation = latitude != null && longitude != null &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        !(latitude == 0.0 && longitude == 0.0)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("사진 촬영 위치", style = MaterialTheme.typography.titleSmall)
        if (hasValidLocation) {
            val markerLatitude = requireNotNull(latitude)
            val markerLongitude = requireNotNull(longitude)
            BikeEyesKakaoMap(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(12.dp)),
                initialLatitude = markerLatitude,
                initialLongitude = markerLongitude,
                showCurrentLocation = false,
                blockParentScrollGestures = true,
                markers = listOf(
                    BikeEyesMapMarker(
                        id = "review-image-${item.review_item_id}",
                        latitude = markerLatitude,
                        longitude = markerLongitude,
                    )
                ),
            )
        } else {
            Text(
                "이 사진에는 저장된 위치 정보가 없습니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReviewSensorData(detail: AdminReviewItemDetail) {
    val representativeSample = remember(detail) { detail.representativeSensorSample() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("대표 센서값 (Peak)", style = MaterialTheme.typography.titleSmall)
        if (representativeSample == null) {
            Text("대표 센서값이 없습니다.")
        } else {
            Text("선정 시각 : ${formatReviewTimeWithMillis(representativeSample.sampled_at)}")
            Text(
                String.format(
                    Locale.KOREA,
                    "가속도 : X/Y/Z %.2f/%.2f/%.2f m/s²\n" +
                        "자이로 : X/Y/Z %.2f/%.2f/%.2f rad/s\n" +
                        "지자기 : X/Y/Z %.2f/%.2f/%.2f μT\n" +
                        "GPS 속도 : %.2f m/s",
                        representativeSample.accel_x,
                        representativeSample.accel_y,
                        representativeSample.accel_z,
                        representativeSample.gyro_x,
                        representativeSample.gyro_y,
                        representativeSample.gyro_z,
                        representativeSample.geomagnetic_x,
                        representativeSample.geomagnetic_y,
                        representativeSample.geomagnetic_z,
                        representativeSample.speed ?: 0.0,
                )
            )
        }
    }
}

private fun AdminReviewItemDetail.representativeSensorSample(): SensorSample? {
    peak_sample_at?.let { peakAt ->
        sensor_samples.firstOrNull { it.sampled_at == peakAt }?.let { return it }
    }
    return sensor_samples.maxByOrNull { sample ->
        abs(
            sqrt(
                sample.accel_x * sample.accel_x +
                    sample.accel_y * sample.accel_y +
                    sample.accel_z * sample.accel_z
            ) - STANDARD_GRAVITY_MPS2
        )
    }
}

private fun formatReviewTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.KOREA).format(Date(epochMillis))

private fun formatReviewTimeWithMillis(epochMillis: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS", Locale.KOREA).format(Date(epochMillis))
