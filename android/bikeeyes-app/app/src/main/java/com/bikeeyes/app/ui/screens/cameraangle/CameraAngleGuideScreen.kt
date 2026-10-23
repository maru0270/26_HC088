package com.bikeeyes.app.ui.screens.cameraangle

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.bikeeyes.app.CameraMountAngleConfig
import com.bikeeyes.app.ui.components.BikeEyesOutlinedButton
import com.bikeeyes.app.ui.components.BikeEyesPrimaryButton
import com.bikeeyes.app.ui.components.BikeEyesStatusCard
import com.bikeeyes.app.ui.theme.BikeEyesBorderGray
import com.bikeeyes.app.ui.theme.BikeEyesGreen
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

@Composable
fun CameraAngleGuideScreen(
    onBackClick: () -> Unit = {},
    onStartRideClick: () -> Unit = {}
) {
    val context = LocalContext.current
    var pitch by remember { mutableDoubleStateOf(0.0) }
    var roll by remember { mutableDoubleStateOf(0.0) }
    var gyroMagnitude by remember { mutableDoubleStateOf(Double.MAX_VALUE) }
    var hasMeasurement by remember { mutableStateOf(false) }
    var stableSince by remember { mutableLongStateOf(0L) }
    var recheckKey by remember { mutableLongStateOf(0L) }

    val angleInRange = hasMeasurement &&
            abs(pitch - CameraMountAngleConfig.TARGET_PITCH_DEGREES) <=
            CameraMountAngleConfig.PITCH_TOLERANCE_DEGREES &&
            abs(roll - CameraMountAngleConfig.TARGET_ROLL_DEGREES) <=
            CameraMountAngleConfig.ROLL_TOLERANCE_DEGREES
    val motionStable = gyroMagnitude <= CameraMountAngleConfig.MAX_STABLE_GYRO_RAD_PER_SEC
    val now = SystemClock.elapsedRealtime()
    if (angleInRange && motionStable) {
        if (stableSince == 0L) stableSince = now
    } else {
        stableSince = 0L
    }
    val mountReady = stableSince > 0L &&
            now - stableSince >= CameraMountAngleConfig.REQUIRED_STABLE_DURATION_MS

    DisposableEffect(context, recheckKey) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        var filteredAx = 0.0
        var filteredAy = 0.0
        var filteredAz = 0.0
        var initialized = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        val alpha = 0.12
                        if (!initialized) {
                            filteredAx = event.values[0].toDouble()
                            filteredAy = event.values[1].toDouble()
                            filteredAz = event.values[2].toDouble()
                            initialized = true
                        } else {
                            filteredAx += alpha * (event.values[0] - filteredAx)
                            filteredAy += alpha * (event.values[1] - filteredAy)
                            filteredAz += alpha * (event.values[2] - filteredAz)
                        }
                        pitch = Math.toDegrees(
                            atan2(-filteredAx, sqrt(filteredAy * filteredAy + filteredAz * filteredAz))
                        )
                        roll = Math.toDegrees(atan2(filteredAy, filteredAz))
                        hasMeasurement = true
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        val current = sqrt(
                            event.values[0] * event.values[0] +
                                    event.values[1] * event.values[1] +
                                    event.values[2] * event.values[2]
                        ).toDouble()
                        gyroMagnitude = if (gyroMagnitude == Double.MAX_VALUE) current
                        else gyroMagnitude * 0.8 + current * 0.2
                    }
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        accel?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        gyro?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { manager.unregisterListener(listener) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp)
    ) {
        CameraGuideTopArea(onBackClick)
        Spacer(Modifier.height(24.dp))
        Text("카메라 방향 안내", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        CameraGuideMessageCard(mountReady)
        Spacer(Modifier.height(18.dp))
        CameraPreviewStatus(pitch, roll, mountReady)
        Spacer(Modifier.height(18.dp))
        BikeEyesStatusCard(
            title = "휴대폰 장착 각도",
            status = when {
                !hasMeasurement -> "측정 중"
                mountReady -> "정상"
                angleInRange -> "각도 유지 필요"
                else -> "조정 필요"
            },
            statusColor = if (mountReady) BikeEyesGreen else Color(0xFFF9A825)
        )
        Spacer(Modifier.height(10.dp))
        BikeEyesStatusCard(
            title = "휴대폰 움직임",
            status = if (motionStable) "안정" else "흔들림",
            statusColor = if (motionStable) BikeEyesGreen else Color(0xFFD92D20)
        )
        Spacer(Modifier.height(18.dp))
        BikeEyesOutlinedButton(
            text = "다시 확인",
            onClick = {
                hasMeasurement = false
                stableSince = 0L
                gyroMagnitude = Double.MAX_VALUE
                recheckKey++
            }
        )
        Spacer(Modifier.height(10.dp))
        BikeEyesPrimaryButton(
            text = if (mountReady) "주행 시작" else "각도를 맞춰주세요",
            onClick = onStartRideClick,
            enabled = mountReady
        )
    }
}

@Composable
private fun CameraGuideTopArea(onBackClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBackClick) {
            Text("‹", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        Text("BikeEyes", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = BikeEyesGreen)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun CameraGuideMessageCard(ready: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, BikeEyesBorderGray),
        colors = CardDefaults.cardColors()
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).background(Color(0xFFEAF7EF), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) { Text(if (ready) "✓" else "↕", style = MaterialTheme.typography.titleLarge) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    if (ready) "촬영 가능한 장착 상태입니다." else "휴대폰 각도를 조정해 주세요.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text("초기 기준은 2026년 6월 27일 안정 주행 데이터입니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CameraPreviewStatus(pitch: Double, roll: Double, ready: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraPermissionGranted = remember(context) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var previewReady by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, previewView, cameraPermissionGranted) {
        val view = previewView
        if (!cameraPermissionGranted || view == null) {
            onDispose { }
        } else {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            var cameraProvider: ProcessCameraProvider? = null
            var preview: Preview? = null
            var disposed = false
            cameraProviderFuture.addListener({
                if (!disposed) {
                    try {
                        val provider = cameraProviderFuture.get()
                        val createdPreview = Preview.Builder().build().also {
                            it.setSurfaceProvider(view.surfaceProvider)
                        }
                        cameraProvider = provider
                        preview = createdPreview
                        provider.unbind(createdPreview)
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            createdPreview
                        )
                        previewReady = true
                    } catch (_: Exception) {
                        previewReady = false
                    }
                }
            }, ContextCompat.getMainExecutor(context))
            onDispose {
                disposed = true
                preview?.let { cameraProvider?.unbind(it) }
            }
        }
    }

    Box(
        Modifier.fillMaxWidth().height(300.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1E2937))
    ) {
        if (cameraPermissionGranted) {
            AndroidView(
                factory = {
                    PreviewView(it).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        previewView = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        if (!cameraPermissionGranted || !previewReady) {
            Text(
                if (cameraPermissionGranted) "카메라 화면을 준비하는 중입니다." else "카메라 권한이 필요합니다.",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        Text(
            if (ready) "정렬 상태: 정상" else "정렬 상태: 조정 필요",
            color = Color.White,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
        )
        Text(
            String.format(Locale.US, "피치 %.1f°  ·  롤 %.1f°", pitch, roll),
            color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
        )
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(2.dp).background(BikeEyesGreen))
        Box(Modifier.align(Alignment.Center).width(2.dp).height(220.dp).background(BikeEyesGreen))
    }
}
