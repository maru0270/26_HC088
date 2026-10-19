package com.bikeeyes.app.ui.screens.ride

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bikeeyes.app.AuthRepository
import com.bikeeyes.app.AuthSessionStore
import com.bikeeyes.app.BikeEyesLocalDatabase
import com.bikeeyes.app.DeletedRideItem
import com.bikeeyes.app.NetworkModule
import com.bikeeyes.app.RideDownloadSyncStatus
import com.bikeeyes.app.RideResultEntity
import com.bikeeyes.app.RideServerSyncRepository
import com.bikeeyes.app.RideServerSyncState
import com.bikeeyes.app.SensorUploadRepository
import com.bikeeyes.app.ui.theme.BikeEyesBorderGray
import com.bikeeyes.app.ui.theme.BikeEyesGreen
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.launch

private enum class RideFilter(val label: String) {
    ALL("전체"), LAST_7_DAYS("최근 7일"), LAST_30_DAYS("최근 30일"),
    SYNCED("동기화 완료"), PENDING("전송 대기")
}

private enum class AccidentRecordFilter(val label: String) {
    ALL("전체"), HAS_ACCIDENT("사고 기록 있음"), NO_ACCIDENT("사고 기록 없음")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RideHistoryScreen(onRideClick: (String) -> Unit) {
    val context = LocalContext.current
    val database = BikeEyesLocalDatabase.getInstance(context)
    val scope = rememberCoroutineScope()
    val uploadRepository = remember(database) {
        SensorUploadRepository(
            NetworkModule.dynamicSensorApi(context), database.localSyncLogDao(),
            database.rideResultDao(), database.accidentEventDao(), AuthRepository(context),
            AuthSessionStore(context), database.rideDeletionDao()
        )
    }
    val serverRepository = remember(context) { RideServerSyncRepository(context) }
    val syncState by RideServerSyncState.state.collectAsState()
    val rides by database.rideResultDao().observeAll().collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf(RideFilter.ALL) }
    var accidentFilter by remember { mutableStateOf(AccidentRecordFilter.ALL) }
    var dateQuery by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var rideToDelete by remember { mutableStateOf<RideResultEntity?>(null) }
    var rideToPermanentlyDelete by remember { mutableStateOf<DeletedRideItem?>(null) }
    var deletedRides by remember { mutableStateOf<List<DeletedRideItem>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(syncState.completedAt) {
        deletedRides = runCatching { serverRepository.getDeletedRides() }.getOrDefault(emptyList())
    }

    val now = System.currentTimeMillis()
    val filtered = rides.asSequence().filter { ride ->
        val matchesFilter = when (filter) {
            RideFilter.ALL -> true
            RideFilter.LAST_7_DAYS -> ride.ended_at >= now - 7L * 24 * 60 * 60 * 1000
            RideFilter.LAST_30_DAYS -> ride.ended_at >= now - 30L * 24 * 60 * 60 * 1000
            RideFilter.SYNCED -> ride.sync_status == "SYNCED"
            RideFilter.PENDING -> ride.sync_status != "SYNCED"
        }
        val matchesAccidentFilter = when (accidentFilter) {
            AccidentRecordFilter.ALL -> true
            AccidentRecordFilter.HAS_ACCIDENT -> ride.accident_count > 0
            AccidentRecordFilter.NO_ACCIDENT -> ride.accident_count == 0
        }
        matchesFilter && matchesAccidentFilter &&
            (dateQuery.isBlank() || formatRideDate(ride.started_at).contains(dateQuery.trim()))
    }.sortedByDescending { it.ended_at }.toList()

    val monthStart = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val monthly = rides.filter { it.ended_at >= monthStart }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("주행 기록", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                OutlinedButton(
                    enabled = syncState.status != RideDownloadSyncStatus.SYNCING,
                    onClick = { scope.launch { serverRepository.sync() } }
                ) {
                    if (syncState.status == RideDownloadSyncStatus.SYNCING) {
                        CircularProgressIndicator()
                    } else Text("새로고침")
                }
            }
        }
        item { Text("총 ${rides.size}회 · ${formatDistance(rides.sumOf { it.total_distance_meters })}") }
        item {
            Text(
                "이번 달 ${monthly.size}회 · ${formatDistance(monthly.sumOf { it.total_distance_meters })} · " +
                    formatDuration(monthly.sumOf { it.elapsed_seconds }),
                color = BikeEyesGreen,
                fontWeight = FontWeight.Bold
            )
        }
        syncState.message?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
        item {
            OutlinedTextField(
                value = dateQuery,
                onValueChange = {},
                readOnly = true,
                label = { Text("날짜 선택") },
                placeholder = { Text("전체 날짜") },
                trailingIcon = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(
                            painter = painterResource(android.R.drawable.ic_menu_my_calendar),
                            contentDescription = "날짜 선택"
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDatePicker = true }
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(RideFilter.entries) { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) }
                    )
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "사고 데이터",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(AccidentRecordFilter.entries) { option ->
                        FilterChip(
                            selected = accidentFilter == option,
                            onClick = { accidentFilter = option },
                            label = { Text(option.label) }
                        )
                    }
                }
            }
        }
        if (deletedRides.isNotEmpty()) {
            item { Text("7일 이내 복구 가능한 기록", fontWeight = FontWeight.Bold) }
            items(deletedRides, key = { "deleted-${it.ride_session_id}" }) { deleted ->
                DeletedRideCard(
                    ride = deleted,
                    onRestore = {
                        scope.launch {
                            runCatching { serverRepository.restoreRide(deleted.ride_session_id) }
                                .onSuccess {
                                    deletedRides = serverRepository.getDeletedRides()
                                    message = "주행 기록을 복구했습니다."
                                }
                                .onFailure { message = AuthRepository.userMessage(it) }
                        }
                    },
                    onPermanentDelete = {
                        rideToPermanentlyDelete = deleted
                    }
                )
            }
        }
        message?.let { item { Text(it, color = BikeEyesGreen) } }
        if (filtered.isEmpty()) {
            item { Text("조건에 맞는 주행 기록이 없습니다.", Modifier.padding(20.dp)) }
        } else {
            items(filtered, key = { it.ride_session_id }) { ride ->
                RideHistoryCard(
                    ride = ride,
                    onClick = { onRideClick(ride.ride_session_id) },
                    onDeleteClick = { rideToDelete = ride }
                )
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = parseRideDateQuery(dateQuery)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = datePickerState.selectedDateMillis != null,
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            dateQuery = formatRideDateQuery(it)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("확인")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            dateQuery = ""
                            showDatePicker = false
                        }
                    ) {
                        Text("전체 날짜")
                    }
                    TextButton(onClick = { showDatePicker = false }) {
                        Text("취소")
                    }
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    rideToDelete?.let { ride ->
        AlertDialog(
            onDismissRequest = { rideToDelete = null },
            title = { Text("주행 기록을 삭제할까요?") },
            text = {
                Text(
                    "목록에서는 즉시 사라지며 서버에서 7일 동안 복구할 수 있습니다. " +
                        "7일 후 주행 결과와 경로는 삭제되고 수집 데이터는 비식별 상태로 유지됩니다."
                )
            },
            confirmButton = {
                Button(onClick = {
                    rideToDelete = null
                    scope.launch {
                        uploadRepository.deleteRide(ride.ride_session_id)
                        deletedRides = runCatching { serverRepository.getDeletedRides() }
                            .getOrDefault(deletedRides)
                    }
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { rideToDelete = null }) { Text("취소") }
            }
        )
    }

    rideToPermanentlyDelete?.let { ride ->
        AlertDialog(
            onDismissRequest = { rideToPermanentlyDelete = null },
            title = { Text("주행 기록을 완전히 삭제할까요?") },
            text = {
                Text(
                    "7일 복구 대기 없이 주행 결과와 GPS 경로를 즉시 삭제합니다. " +
                        "삭제 후에는 복구할 수 없습니다. 수집 데이터는 정책에 따라 " +
                        "사용자·주행·위치 정보를 제거한 비식별 상태로 유지됩니다."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        rideToPermanentlyDelete = null
                        scope.launch {
                            runCatching {
                                serverRepository.permanentlyDeleteRide(ride.ride_session_id)
                            }.onSuccess {
                                deletedRides = serverRepository.getDeletedRides()
                                message = "주행 기록을 완전히 삭제했습니다."
                            }.onFailure {
                                message = AuthRepository.userMessage(it)
                            }
                        }
                    }
                ) {
                    Text("완전 삭제")
                }
            },
            dismissButton = {
                TextButton(onClick = { rideToPermanentlyDelete = null }) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
private fun DeletedRideCard(
    ride: DeletedRideItem,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7E8))) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(formatRideDate(ride.started_at), fontWeight = FontWeight.Bold)
                Text("7일 보관 후 영구 삭제", style = MaterialTheme.typography.bodySmall)
            }
            Row {
                TextButton(onClick = onRestore) { Text("복구") }
                TextButton(onClick = onPermanentDelete) {
                    Text("완전 삭제", color = Color(0xFFD92D20))
                }
            }
        }
    }
}

@Composable
private fun RideHistoryCard(
    ride: RideResultEntity,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BikeEyesBorderGray),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatRideDate(ride.started_at), fontWeight = FontWeight.Bold)
                TextButton(onClick = onDeleteClick) { Text("삭제", color = Color(0xFFD92D20)) }
            }
            Text(syncStatusText(ride.sync_status), color = BikeEyesGreen)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HistoryValue("거리", formatDistance(ride.total_distance_meters))
                HistoryValue("시간", formatDuration(ride.elapsed_seconds))
                HistoryValue("평균", String.format(Locale.KOREA, "%.1f km/h", ride.average_speed_kmh))
            }
            Text(
                "노면 수집 ${ride.collected_frame_count}개 · 사고 ${ride.accident_count}건",
                color = Color(0xFF667085),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun HistoryValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

internal fun formatRideDate(timestamp: Long): String =
    SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(timestamp))

private fun formatRideDateQuery(timestamp: Long): String =
    SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(timestamp))

private fun parseRideDateQuery(value: String): Long? =
    value.takeIf { it.isNotBlank() }?.let {
        runCatching {
            SimpleDateFormat("yyyy.MM.dd", Locale.KOREA).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(it)?.time
        }.getOrNull()
    }

internal fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600L
    val minutes = totalSeconds % 3600L / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

internal fun formatDistance(meters: Double): String =
    String.format(Locale.KOREA, "%.2f km", meters / 1000.0)

internal fun syncStatusText(status: String): String = when (status) {
    "SYNCED" -> "동기화 완료"
    "UPLOADING" -> "업로드 중"
    "RECOVERED_PENDING" -> "비정상 종료 후 복구됨 · 전송 대기"
    else -> "일부 데이터 전송 대기"
}
