package com.bikeeyes.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import com.bikeeyes.app.ui.BikeEyesApp
import com.bikeeyes.app.ui.theme.BikeEyesTheme
import androidx.compose.material3.MaterialTheme

class MainActivity : ComponentActivity() {
    private var uploadJob: Job? = null
    private var serviceRunning by mutableStateOf(false)
    private var statusMessage by mutableStateOf("센서 수집 대기 중입니다.")
    private var pendingCount by mutableStateOf(0)
    private var retryingCount by mutableStateOf(0)
    private var failedCount by mutableStateOf(0)
    private var syncedCount by mutableStateOf(0)
    private var permissionRequestResult by mutableStateOf<Boolean?>(null)
    private var staleUploadNotificationShown = false

    private val uploadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val localDatabase: BikeEyesLocalDatabase by lazy {
        BikeEyesLocalDatabase.getInstance(applicationContext)
    }

    private val uploadRepository: SensorUploadRepository by lazy {
        SensorUploadRepository(
            sensorApi = NetworkModule.dynamicSensorApi(applicationContext),
            localSyncLogDao = localDatabase.localSyncLogDao(),
            rideResultDao = localDatabase.rideResultDao(),
            accidentEventDao = localDatabase.accidentEventDao(),
            authRepository = AuthRepository(applicationContext),
            authSessionStore = AuthSessionStore(applicationContext),
            rideDeletionDao = localDatabase.rideDeletionDao()
        )
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val locationGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED

            if (locationGranted) {
                permissionRequestResult = hasRequiredPermissions()
                statusMessage = "권한 허용 완료. 수집 시작을 누르세요."
            } else {
                permissionRequestResult = false
                statusMessage = "위치 권한과 카메라 권한이 필요합니다."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BikeEyesTheme {
                BikeEyesApp(
                    onStartSensorLogging = {
                        if (hasRequiredPermissions()) {
                            startSensorLoggingService()
                            true
                        } else {
                            statusMessage = "위치, 카메라, 알림 권한을 허용해주세요."
                            requestRequiredPermissionsIfNeeded()
                            false
                        }
                    },
                    onStopSensorLogging = {
                        stopSensorLoggingService()
                    },
                    onDiscardSensorLogging = {
                        discardSensorLoggingService()
                    },
                    onPauseSensorLogging = {
                        controlSensorLoggingService(SensorLoggingService.ACTION_PAUSE_RIDE)
                    },
                    onResumeSensorLogging = {
                        controlSensorLoggingService(SensorLoggingService.ACTION_RESUME_RIDE)
                    },
                    areRequiredPermissionsGranted = {
                        hasRequiredPermissions()
                    },
                    onRequestRequiredPermissions = {
                        permissionRequestResult = null
                        requestRequiredPermissionsIfNeeded()
                    },
                    permissionRequestResult = permissionRequestResult,
                    onResolveAccident = { eventUuid, confirmation ->
                        resolveAccident(eventUuid, confirmation)
                    }
                )
            }
        }

        startQueueStatusPolling()
        startUploadLoop()
        lifecycleScope.launch(Dispatchers.IO) {
            recoverInterruptedRideIfNeeded()
            MockRiskZoneRepository(
                localDatabase.riskZoneDao(),
                NetworkModule.dynamicSensorApi(this@MainActivity)
            ).refresh()
            uploadRepository.enqueueMissingRideResults(
                localDatabase.rideResultDao().getAll()
            )
        }
    }

    private fun resolveAccident(eventUuid: String, confirmation: AccidentConfirmation) {
        val alert = AccidentAlertState.resolve(eventUuid) ?: return
        val request = alert.toRequest(confirmation)
        lifecycleScope.launch(Dispatchers.IO) {
            localDatabase.accidentEventDao().insert(AccidentEventEntity(
                event_uuid=request.event_uuid, ride_session_id=request.ride_session_id, detected_at=request.detected_at,
                latitude=request.latitude, longitude=request.longitude, accuracy=request.accuracy, speed=request.speed,
                accel_x=request.accel_x, accel_y=request.accel_y, accel_z=request.accel_z,
                gyro_x=request.gyro_x, gyro_y=request.gyro_y, gyro_z=request.gyro_z,
                acceleration_magnitude=request.acceleration_magnitude, gyro_magnitude=request.gyro_magnitude,
                confirmation_status=request.confirmation_status, is_false_positive=request.is_false_positive,
                sync_status="PENDING",
                created_at=System.currentTimeMillis()))
            uploadRepository.enqueueAccident(request)
        }
        if (confirmation != AccidentConfirmation.SAFE) RideTrackingState.incrementAccidentCount()
    }

    private fun startUploadLoop() {

        uploadJob?.cancel()

        uploadJob = uploadScope.launch {

            while (isActive) {

                try {
                    uploadRepository.uploadPendingOnly()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                delay(3000)
            }
        }
    }

    private fun startQueueStatusPolling() {
        lifecycleScope.launch {
            val dao = localDatabase.localSyncLogDao()

            while (isActive) {
                val counts = withContext(Dispatchers.IO) {
                    QueueStatusCounts(
                        pending = dao.countByStatus("PENDING"),
                        retrying = dao.countByStatus("RETRYING"),
                        failed = dao.countByStatus("FAILED"),
                        synced = dao.countByStatus("SYNCED")
                    )
                }

                pendingCount = counts.pending
                retryingCount = counts.retrying
                failedCount = counts.failed
                syncedCount = counts.synced
                RideTrackingState.updateQueueStatus(
                    pending = counts.pending,
                    retrying = counts.retrying,
                    failed = counts.failed
                )
                val oldest = withContext(Dispatchers.IO) { dao.oldestUnsyncedAt() }
                if (!staleUploadNotificationShown && oldest != null &&
                    System.currentTimeMillis() - oldest >= 24L * 60 * 60 * 1000
                ) {
                    staleUploadNotificationShown = true
                    AppAlertManager.showStatusNotification(
                        this@MainActivity,
                        "전송 대기 데이터가 있습니다",
                        "24시간 이상 전송되지 않은 수집 데이터를 확인해주세요.",
                        24001
                    )
                }

                delay(1_000)
            }
        }
    }

    private fun startSensorLoggingService() {
        val intent = Intent(this, SensorLoggingService::class.java)

        startForegroundService(intent)

        serviceRunning = true
        statusMessage = "백그라운드 센서 수집 중입니다."
    }

    private fun stopSensorLoggingService() {
        val syncStatus = when {
            failedCount > 0 -> RideSyncStatus.PENDING
            pendingCount > 0 || retryingCount > 0 -> RideSyncStatus.UPLOADING
            else -> RideSyncStatus.SYNCED
        }
        val rideResult = RideTrackingState.finishRide(syncStatus)
        RideRecoveryStore(this).complete()
        AppAlertManager.showRideSummary(this, rideResult)

        lifecycleScope.launch(Dispatchers.IO) {
            val persistedResult = rideResult.copy(
                syncStatus = if (RideTrackingState.serverReachable.value) {
                    RideSyncStatus.UPLOADING
                } else {
                    RideSyncStatus.PENDING
                }
            )
            localDatabase.rideResultDao().insert(persistedResult.toEntity())
            uploadRepository.enqueueRideResult(persistedResult.toRequest())
            RideTrackingState.updateLastRideSyncStatus(
                rideSessionId = persistedResult.rideSessionId,
                syncStatus = persistedResult.syncStatus
            )
        }

        val intent = Intent(
            this,
            SensorLoggingService::class.java
        )

        stopService(intent)

        serviceRunning = false
        statusMessage = "주행을 종료했습니다."
    }

    private fun controlSensorLoggingService(action: String) {
        startService(
            Intent(this, SensorLoggingService::class.java).setAction(action)
        )
    }

    private fun discardSensorLoggingService() {
        RideRecoveryStore(this).complete()
        stopService(Intent(this, SensorLoggingService::class.java))
        RideTrackingState.abandonRide()
        serviceRunning = false
        statusMessage = "이동이 거의 없는 주행 기록을 저장하지 않았습니다."
    }

    private suspend fun recoverInterruptedRideIfNeeded() {
        val store = RideRecoveryStore(this)
        val snapshot = store.recover() ?: return
        if (
            RideTrackingState.currentRideSessionId() == snapshot.sessionId &&
            RideTrackingState.elapsedSeconds.value > 0L
        ) {
            return
        }
        if (snapshot.startedAt <= 0L || snapshot.elapsedSeconds <= 0L) {
            store.complete()
            return
        }
        val averageSpeed = if (snapshot.elapsedSeconds > 0) {
            (snapshot.distanceMeters / 1000.0) / (snapshot.elapsedSeconds / 3600.0)
        } else 0.0
        localDatabase.rideResultDao().insert(
            RideResultEntity(
                ride_session_id = snapshot.sessionId,
                started_at = snapshot.startedAt,
                ended_at = System.currentTimeMillis(),
                elapsed_seconds = snapshot.elapsedSeconds,
                total_distance_meters = snapshot.distanceMeters,
                average_speed_kmh = averageSpeed,
                max_speed_kmh = 0.0,
                collected_frame_count = 0,
                accident_count = 0,
                sync_status = "RECOVERED_PENDING"
            )
        )
        store.complete()
    }

    private fun requestRequiredPermissionsIfNeeded() {
        val missingPermissions = getRequiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            permissionRequestResult = true
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val locationGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

        val notificationGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

        return locationGranted && notificationGranted
    }

    private fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions
    }
}

@Composable
fun SensorLoggingControlScreen(
    serviceRunning: Boolean,
    statusMessage: String,
    pendingCount: Int,
    retryingCount: Int,
    failedCount: Int,
    syncedCount: Int,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "BikeEyes Sensor Logger",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (serviceRunning) "상태: 수집 중" else "상태: 대기 중",
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(text = statusMessage)

            Spacer(modifier = Modifier.height(24.dp))

            Text(text = "Room Queue")
            Text(text = "PENDING: $pendingCount")
            Text(text = "RETRYING: $retryingCount")
            Text(text = "FAILED: $failedCount")
            Text(text = "SYNCED: $syncedCount")

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onStartClick,
                enabled = !serviceRunning
            ) {
                Text(text = "수집 시작")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onStopClick,
                enabled = serviceRunning
            ) {
                Text(text = "수집 중지")
            }
        }
    }
}

private data class QueueStatusCounts(
    val pending: Int,
    val retrying: Int,
    val failed: Int,
    val synced: Int
)
