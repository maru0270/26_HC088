package com.bikeeyes.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import kotlinx.coroutines.delay
import com.bikeeyes.app.AccidentAlertState
import com.bikeeyes.app.AccidentConfirmation
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bikeeyes.app.ui.navigation.BikeEyesBottomBar
import com.bikeeyes.app.ui.navigation.BikeEyesNavigationGraph
import com.bikeeyes.app.ui.navigation.Screen
import androidx.compose.ui.platform.LocalContext
import com.bikeeyes.app.UserSettingsStore
import com.bikeeyes.app.RiskZoneApproachMonitor
import com.bikeeyes.app.AuthExpiryState
import com.bikeeyes.app.AppAlertManager
import com.bikeeyes.app.AppAlertType
import com.bikeeyes.app.DeviceHealthPolicy
import com.bikeeyes.app.SafetyTtsManager
import com.bikeeyes.app.ApproachVoiceType
import com.bikeeyes.app.BuildConfig
import com.bikeeyes.app.FilmingDemoConfig
import androidx.compose.runtime.mutableStateOf

private const val HAZARD_ALERT_AUTO_DISMISS_MS = 10_000L

@Composable
fun BikeEyesApp(
    onStartSensorLogging: () -> Boolean,
    onStopSensorLogging: () -> Unit,
    onDiscardSensorLogging: () -> Unit,
    onPauseSensorLogging: () -> Unit,
    onResumeSensorLogging: () -> Unit,
    areRequiredPermissionsGranted: () -> Boolean,
    onRequestRequiredPermissions: () -> Unit,
    permissionRequestResult: Boolean?,
    onResolveAccident: (String, AccidentConfirmation) -> Unit
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    var showLowBatteryDialog by remember { mutableStateOf(false) }
    var showStorageWarning by remember { mutableStateOf(false) }

    val bottomBarHiddenRoutes = setOf(
        Screen.Login.route,
        Screen.PasswordReset.route,
        Screen.Signup.route,
        Screen.SignupComplete.route,
        Screen.Permission.route
    )
    val showBottomBar = currentDestination?.route?.let { route ->
        route !in bottomBarHiddenRoutes
    } == true
    val applyKeyboardInsets = currentDestination?.route != Screen.Ride.route
    if (
        BuildConfig.DEBUG &&
        FilmingDemoConfig.enabled &&
        currentDestination?.route == Screen.Ride.route
    ) {
        LaunchedEffect(currentDestination?.route) {
            delay(FilmingDemoConfig.alertDelayMs)
            RiskZoneApproachMonitor.showFilmingDemo(ApproachVoiceType.ACCIDENT_ZONE)
        }
    }
    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                BikeEyesBottomBar(
                    navController = navController,
                    currentDestination = currentDestination
                )
            }
        }
    ) { innerPadding ->
        BikeEyesNavigationGraph(
            navController = navController,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .then(
                    if (applyKeyboardInsets) Modifier.imePadding()
                    else Modifier
                ),
            onStartSensorLogging = {
                val battery = DeviceHealthPolicy.batteryPercent(context)
                val storage = DeviceHealthPolicy.availableBytes(context)
                when {
                    battery <= DeviceHealthPolicy.LOW_BATTERY_PERCENT -> {
                        showLowBatteryDialog = true
                        false
                    }
                    storage < DeviceHealthPolicy.STORAGE_WARNING_BYTES -> {
                        showStorageWarning = true
                        false
                    }
                    else -> onStartSensorLogging()
                }
            },
            onStopSensorLogging = onStopSensorLogging,
            onDiscardSensorLogging = onDiscardSensorLogging,
            onPauseSensorLogging = onPauseSensorLogging,
            onResumeSensorLogging = onResumeSensorLogging,
            areRequiredPermissionsGranted = areRequiredPermissionsGranted,
            onRequestRequiredPermissions = onRequestRequiredPermissions,
            permissionRequestResult = permissionRequestResult
        )
    }
    if (showLowBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showLowBatteryDialog = false },
            title = { Text("배터리가 20% 이하입니다") },
            text = { Text("데이터 수집을 계속하면 주행 중 휴대폰이 꺼질 수 있습니다. 계속할까요?") },
            confirmButton = {
                Button(onClick = {
                    showLowBatteryDialog = false
                    if (onStartSensorLogging()) navController.navigate(Screen.Ride.route)
                }) { Text("계속 수집") }
            },
            dismissButton = {
                TextButton(onClick = { showLowBatteryDialog = false }) { Text("취소") }
            }
        )
    }
    if (showStorageWarning) {
        val criticallyLow =
            DeviceHealthPolicy.availableBytes(context) < DeviceHealthPolicy.IMAGE_STOP_BYTES
        AlertDialog(
            onDismissRequest = { showStorageWarning = false },
            title = { Text("저장공간이 부족합니다") },
            text = {
                Text(
                    if (criticallyLow) "남은 공간이 500MB 미만이라 이미지는 촬영하지 않고 센서·GPS만 수집합니다."
                    else "남은 공간이 1GB 미만입니다. 수집을 계속할까요?"
                )
            },
            confirmButton = {
                Button(onClick = {
                    showStorageWarning = false
                    if (onStartSensorLogging()) navController.navigate(Screen.Ride.route)
                }) { Text("계속 수집") }
            },
            dismissButton = {
                TextButton(onClick = { showStorageWarning = false }) { Text("취소") }
            }
        )
    }
    val accident by AccidentAlertState.active.collectAsState()
    val accidentAlertsEnabled =
        UserSettingsStore(LocalContext.current).loadNotifications().accidentAlerts
    accident?.let { alert ->
        var seconds by remember(alert.event_uuid) { mutableIntStateOf(30) }
        LaunchedEffect(alert.event_uuid) {
            AppAlertManager.signal(context, AppAlertType.ACCIDENT)
        }
        LaunchedEffect(alert.event_uuid) {
            while (seconds > 0) { delay(1000); seconds-- }
            onResolveAccident(alert.event_uuid, AccidentConfirmation.NO_RESPONSE)
        }
        if (accidentAlertsEnabled) {
            AlertDialog(onDismissRequest = {}, title={ Text("사고가 감지되었습니다") },
                text={ Text("강한 충격과 회전이 감지되었습니다. ${seconds}초 안에 상태를 알려주세요.") },
                confirmButton={ Button(onClick={ onResolveAccident(alert.event_uuid, AccidentConfirmation.ACCIDENT) }) { Text("사고입니다") } },
                dismissButton={ TextButton(onClick={ onResolveAccident(alert.event_uuid, AccidentConfirmation.SAFE) }) { Text("괜찮아요") } })
        }
    }
    val riskAlert by RiskZoneApproachMonitor.activeAlert.collectAsState()
    riskAlert?.let { alert ->
        val hazardAlertsEnabled =
            UserSettingsStore(LocalContext.current).loadNotifications().hazardAlerts
        val isFilmingDemo = alert.zoneId.startsWith("filming-demo-")
        if (hazardAlertsEnabled || isFilmingDemo) {
            LaunchedEffect(alert.zoneId) {
                AppAlertManager.signal(context, AppAlertType.HAZARD)
                SafetyTtsManager.speak(context, alert.voiceType, force = isFilmingDemo)
            }
            LaunchedEffect(alert.zoneId) {
                delay(HAZARD_ALERT_AUTO_DISMISS_MS)
                RiskZoneApproachMonitor.dismiss(alert.zoneId)
            }
            AlertDialog(
                onDismissRequest = { RiskZoneApproachMonitor.dismiss(alert.zoneId) },
                title = {
                    Text(
                        if (alert.voiceType == ApproachVoiceType.ACCIDENT_ZONE)
                            "사고 기록 구간 접근 알림"
                        else
                            "위험구간 접근 알림"
                    )
                },
                text = {
                    Text(
                        if (alert.voiceType == ApproachVoiceType.ACCIDENT_ZONE) {
                            "${alert.title}\n약 ${alert.distanceMeters.toInt()}m 앞에 사고 기록이 있는 구간입니다."
                        } else {
                            "${alert.title}\n약 ${alert.distanceMeters.toInt()}m 앞에 위험구간이 있습니다."
                        }
                    )
                },
                confirmButton = {
                    Button(onClick = { RiskZoneApproachMonitor.dismiss(alert.zoneId) }) {
                        Text("확인")
                    }
                }
            )
        } else {
            LaunchedEffect(alert.zoneId) {
                RiskZoneApproachMonitor.dismiss(alert.zoneId)
            }
        }
    }
    val authExpired by AuthExpiryState.expired.collectAsState()
    if (authExpired) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("로그인이 만료되었습니다") },
            text = {
                Text("수집 데이터는 휴대폰에 안전하게 보관됩니다. 다시 로그인하면 자동 전송을 재개합니다.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        AuthExpiryState.acknowledge()
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                ) { Text("다시 로그인") }
            }
        )
    }
}
