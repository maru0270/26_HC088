package com.bikeeyes.app.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bikeeyes.app.KakaoMapConfiguration
import com.bikeeyes.app.ui.theme.BikeEyesGreen
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.camera.CameraPosition
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import com.kakao.vectormap.route.RouteLineOptions
import com.kakao.vectormap.route.RouteLineSegment
import com.kakao.vectormap.route.RouteLineStyle

private const val JINJU_LATITUDE = 35.1798
private const val JINJU_LONGITUDE = 128.1076
private const val DEFAULT_ZOOM_LEVEL = 15
private const val NAVIGATION_ZOOM_LEVEL = 18
private const val CURRENT_LOCATION_LABEL_ID = "bikeeyes-current-location"
private const val DATA_LABEL_PREFIX = "bikeeyes-data-"
private const val ROUTE_LAYER_ID = "bikeeyes-route-layer"
private const val LOCATION_UPDATE_INTERVAL_MS = 1_000L
private const val LOCATION_UPDATE_MIN_DISTANCE_METERS = 1f

data class BikeEyesMapPoint(
    val latitude: Double,
    val longitude: Double
)

data class BikeEyesMapMarker(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val color: Int = AndroidColor.rgb(217, 45, 32)
)

data class BikeEyesMapLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedMetersPerSecond: Float,
    val headingDegrees: Float?
)

@Composable
fun BikeEyesKakaoMap(
    modifier: Modifier = Modifier,
    initialLatitude: Double? = null,
    initialLongitude: Double? = null,
    showCurrentLocation: Boolean = true,
    followCurrentLocation: Boolean = false,
    followZoomLevel: Int = NAVIGATION_ZOOM_LEVEL,
    markers: List<BikeEyesMapMarker> = emptyList(),
    routePoints: List<BikeEyesMapPoint> = emptyList(),
    headingUp: Boolean = false,
    simulatedLocation: BikeEyesMapLocation? = null,
    blockParentScrollGestures: Boolean = false,
    onMarkerClick: (String) -> Unit = {},
    onLocationChanged: (latitude: Double, longitude: Double) -> Unit = { _, _ -> },
    onLocationDetailsChanged: (BikeEyesMapLocation) -> Unit = {}
) {
    if (!KakaoMapConfiguration.isConfigured) {
        Box(
            modifier = modifier.background(Color(0xFFEAF7EF)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "카카오 네이티브 앱 키를 설정해 주세요.",
                style = MaterialTheme.typography.bodyMedium,
                color = BikeEyesGreen
            )
        }
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapReloadKey by remember { mutableStateOf(0) }
    val mapView = remember(context, mapReloadKey) { MapView(context) }
    val locationManager = remember(context) {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val currentLocationMarker = remember { createMapMarker(AndroidColor.rgb(239, 68, 68), true) }
    var kakaoMap by remember { mutableStateOf<KakaoMap?>(null) }
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var locationMessage by remember { mutableStateOf<String?>(null) }
    var currentLocationLabel by remember { mutableStateOf<Label?>(null) }
    var sensorHeadingDegrees by remember { mutableStateOf<Float?>(null) }
    var navigationHeadingDegrees by remember { mutableStateOf<Float?>(null) }
    var displayedHeadingDegrees by remember { mutableStateOf<Float?>(null) }
    val headingStabilizer = remember { HeadingStabilizer() }
    var hasCenteredMap by remember { mutableStateOf(false) }
    var isHeadingTracking by remember { mutableStateOf(false) }
    var lastContentSignature by remember { mutableStateOf("") }
    var mapErrorMessage by remember(mapReloadKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(mapReloadKey) {
        hasCenteredMap = false
        lastContentSignature = ""
        currentLocationLabel = null
        isHeadingTracking = false
    }

    DisposableEffect(sensorManager, showCurrentLocation, simulatedLocation) {
        if (!showCurrentLocation || simulatedLocation != null) {
            onDispose { }
        } else {
            val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (rotationSensor == null) {
                onDispose { }
            } else {
                val listener = object : SensorEventListener {
                    private val rotationMatrix = FloatArray(9)
                    private var lastUpdateAt = 0L

                    override fun onSensorChanged(event: SensorEvent) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUpdateAt < HEADING_UPDATE_INTERVAL_MS) return
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        sensorHeadingDegrees = mountedPhoneHeading(rotationMatrix)
                        lastUpdateAt = now
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensorManager.registerListener(
                    listener,
                    rotationSensor,
                    SensorManager.SENSOR_DELAY_UI
                )
                onDispose { sensorManager.unregisterListener(listener) }
            }
        }
    }

    LaunchedEffect(currentLocation, sensorHeadingDegrees) {
        val location = currentLocation ?: return@LaunchedEffect
        headingStabilizer.update(
            nowMs = SystemClock.elapsedRealtime(),
            speedMetersPerSecond = location.speed,
            accuracyMeters = location.accuracy,
            gpsBearingDegrees = location.bearing.takeIf { location.hasBearing() },
            gpsSampleId = location.elapsedRealtimeNanos,
            sensorBearingDegrees = sensorHeadingDegrees
        )?.let { navigationHeadingDegrees = it }
    }

    LaunchedEffect(navigationHeadingDegrees) {
        val target = navigationHeadingDegrees ?: return@LaunchedEffect
        val start = displayedHeadingDegrees
        if (start == null) {
            displayedHeadingDegrees = target
            return@LaunchedEffect
        }
        val delta = shortestHeadingDelta(start, target)
        animate(
            initialValue = start,
            targetValue = start + delta,
            animationSpec = tween(
                durationMillis = HEADING_ANIMATION_MS,
                easing = FastOutSlowInEasing
            )
        ) { value, _ ->
            displayedHeadingDegrees = normalizeHeading(value)
        }
    }

    DisposableEffect(locationManager, showCurrentLocation, simulatedLocation) {
        if (!showCurrentLocation || simulatedLocation != null) {
            onDispose { }
        } else {
            val hasFineLocation =
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
            val hasCoarseLocation =
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED

            if (!hasFineLocation && !hasCoarseLocation) {
                locationMessage = "위치 권한을 허용해 주세요."
                onDispose { }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        currentLocation = location
                        locationMessage = null
                    }

                    @Deprecated("Deprecated in Android")
                    override fun onStatusChanged(
                        provider: String?,
                        status: Int,
                        extras: Bundle?
                    ) = Unit
                }

                val providers = buildList {
                    if (hasFineLocation &&
                        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ) {
                        add(LocationManager.GPS_PROVIDER)
                    }
                    if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        add(LocationManager.NETWORK_PROVIDER)
                    }
                }

                if (providers.isEmpty()) {
                    locationMessage = "기기의 위치 서비스를 켜 주세요."
                } else {
                    currentLocation = providers
                        .mapNotNull { provider ->
                            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
                        }
                        .maxByOrNull { it.time }

                    providers.forEach { provider ->
                        runCatching {
                            locationManager.requestLocationUpdates(
                                provider,
                                LOCATION_UPDATE_INTERVAL_MS,
                                LOCATION_UPDATE_MIN_DISTANCE_METERS,
                                listener,
                                Looper.getMainLooper()
                            )
                        }
                    }
                    if (currentLocation == null) {
                        locationMessage = "현재 위치를 확인하는 중입니다."
                    }
                }

                onDispose { locationManager.removeUpdates(listener) }
            }
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.resume()
                Lifecycle.Event.ON_PAUSE -> mapView.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        mapView.start(
            object : MapLifeCycleCallback() {
                override fun onMapDestroy() = Unit
                override fun onMapError(error: Exception) {
                    mapErrorMessage = "지도를 불러오지 못했습니다.\n네트워크 연결을 확인해 주세요."
                }
            },
            object : KakaoMapReadyCallback() {
                override fun onMapReady(readyMap: KakaoMap) {
                    mapErrorMessage = null
                    kakaoMap = readyMap
                }

                override fun getPosition(): LatLng = LatLng.from(
                    initialLatitude ?: JINJU_LATITUDE,
                    initialLongitude ?: JINJU_LONGITUDE
                )

                override fun getZoomLevel(): Int = DEFAULT_ZOOM_LEVEL
            }
        )

        onDispose {
            kakaoMap?.trackingManager?.stopTracking()
            kakaoMap = null
            currentLocationLabel = null
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.pause()
            mapView.finish()
        }
    }

    LaunchedEffect(kakaoMap, markers, routePoints, followCurrentLocation, onMarkerClick) {
        val readyMap = kakaoMap ?: return@LaunchedEffect
        val labelLayer = readyMap.labelManager?.layer ?: return@LaunchedEffect
        labelLayer.getAllLabels()
            .filter { it.labelId?.startsWith(DATA_LABEL_PREFIX) == true }
            .forEach { it.remove() }

        markers.forEach { marker ->
            labelLayer.addLabel(
                LabelOptions.from(
                    "$DATA_LABEL_PREFIX${marker.id}",
                    LatLng.from(marker.latitude, marker.longitude)
                )
                    .setStyles(
                        LabelStyle.from(createMapMarker(marker.color, false))
                            .setAnchorPoint(0.5f, 1f)
                    )
                    .setClickable(true)
                    .setTag(marker.id)
            )
        }
        readyMap.setOnLabelClickListener { _, _, label ->
            (label.tag as? String)?.let(onMarkerClick)
            label.tag is String
        }

        val routeManager = readyMap.routeLineManager
        routeManager?.getLayer(ROUTE_LAYER_ID)?.let { routeManager.remove(it) }
        if (routePoints.size >= 2) {
            val layer = routeManager?.addLayer(ROUTE_LAYER_ID)
            val positions = routePoints.map { LatLng.from(it.latitude, it.longitude) }
            val segment = RouteLineSegment.from(
                positions,
                RouteLineStyle.from(14f, AndroidColor.rgb(5, 150, 105))
            )
            layer?.addRouteLine(RouteLineOptions.from(segment))
        }

        val contentPoints = buildList {
            addAll(markers.map { LatLng.from(it.latitude, it.longitude) })
            addAll(routePoints.map { LatLng.from(it.latitude, it.longitude) })
        }
        val contentSignature = buildString {
            markers.forEach { append("${it.id}:${it.latitude}:${it.longitude};") }
            routePoints.forEach { append("${it.latitude}:${it.longitude};") }
        }
        if (
            !followCurrentLocation &&
            contentPoints.isNotEmpty() &&
            contentSignature != lastContentSignature
        ) {
            readyMap.moveCamera(
                if (contentPoints.size == 1) {
                    CameraUpdateFactory.newCenterPosition(contentPoints.first(), DEFAULT_ZOOM_LEVEL)
                } else {
                    CameraUpdateFactory.fitMapPoints(contentPoints.toTypedArray(), 100)
                }
            )
            hasCenteredMap = true
            lastContentSignature = contentSignature
        }
    }

    LaunchedEffect(
        kakaoMap,
        currentLocation,
        simulatedLocation,
        displayedHeadingDegrees,
        showCurrentLocation,
        followCurrentLocation,
        followZoomLevel,
        headingUp
    ) {
        if (!showCurrentLocation) return@LaunchedEffect
        val readyMap = kakaoMap ?: return@LaunchedEffect
        val locationDetails = simulatedLocation ?: currentLocation?.let { location ->
            BikeEyesMapLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy,
                speedMetersPerSecond = location.speed,
                headingDegrees = navigationHeadingDegrees
            )
        } ?: return@LaunchedEffect
        onLocationChanged(locationDetails.latitude, locationDetails.longitude)
        onLocationDetailsChanged(locationDetails)
        val position = LatLng.from(locationDetails.latitude, locationDetails.longitude)
        val effectiveHeading = simulatedLocation?.headingDegrees ?: displayedHeadingDegrees

        val label = currentLocationLabel
        if (label == null) {
            currentLocationLabel = readyMap.labelManager?.layer?.addLabel(
                LabelOptions.from(CURRENT_LOCATION_LABEL_ID, position)
                    .setStyles(
                        LabelStyle.from(currentLocationMarker)
                            .setAnchorPoint(0.5f, 0.5f)
                    )
            )
        } else {
            label.moveTo(position)
        }
        effectiveHeading?.let { heading ->
            currentLocationLabel?.rotateTo(if (headingUp) 0f else heading)
        }

        if (followCurrentLocation) {
            val heading = effectiveHeading
            if (headingUp && heading != null) {
                if (isHeadingTracking) {
                    readyMap.trackingManager?.stopTracking()
                    isHeadingTracking = false
                }
                val currentCamera = readyMap.cameraPosition
                // GPS 방위각은 북쪽=0°, 시계 방향이고 카카오 카메라 회전은
                // 동쪽=0°인 화면 좌표계이므로 90° 기준축 보정이 필요하다.
                val mapRotation = ((450f - heading) % 360f).toDouble()
                readyMap.moveCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.from(
                            locationDetails.latitude,
                            locationDetails.longitude,
                            followZoomLevel,
                            currentCamera?.tiltAngle ?: 0.0,
                            mapRotation,
                            currentCamera?.height ?: 0.0
                        )
                    )
                )
            } else {
                if (isHeadingTracking) {
                    readyMap.trackingManager?.stopTracking()
                    isHeadingTracking = false
                }
                readyMap.moveCamera(
                CameraUpdateFactory.newCenterPosition(position, followZoomLevel)
                )
            }
            hasCenteredMap = true
        } else if (!hasCenteredMap && markers.isEmpty() && routePoints.isEmpty()) {
            readyMap.moveCamera(
                CameraUpdateFactory.newCenterPosition(position, DEFAULT_ZOOM_LEVEL)
            )
            hasCenteredMap = true
        }
    }

    Box(modifier = modifier) {
        key(mapReloadKey) {
            AndroidView(
                factory = { viewContext ->
                    MapTouchContainer(viewContext).apply {
                        blockParentScroll = blockParentScrollGestures
                        addView(
                            mapView,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                },
                update = { container ->
                    container.blockParentScroll = blockParentScrollGestures
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        locationMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .background(Color.White.copy(alpha = 0.88f)),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF374151)
            )
        }
        mapErrorMessage?.let { message ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF4F7F5))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                Text(
                    text = message,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF374151)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = { mapReloadKey += 1 }) {
                    Text("다시 시도")
                }
            }
        }
    }
}

/**
 * Keeps drag gestures inside the map from being intercepted by a Compose
 * verticalScroll/LazyColumn parent. The parent can scroll again as soon as the
 * finger leaves the map.
 */
private class MapTouchContainer(context: Context) : FrameLayout(context) {
    var blockParentScroll: Boolean = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (blockParentScroll) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP -> parent?.requestDisallowInterceptTouchEvent(true)

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.dispatchTouchEvent(event)
    }
}

private fun createMapMarker(color: Int, currentLocation: Boolean): Bitmap {
    val width = 72
    val height = if (currentLocation) 72 else 92
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    if (currentLocation) {
        paint.color = color
        val direction = android.graphics.Path().apply {
            moveTo(width / 2f, 1f)
            lineTo(width / 2f - 14f, 25f)
            lineTo(width / 2f + 14f, 25f)
            close()
        }
        canvas.drawPath(direction, paint)
        paint.color = AndroidColor.WHITE
        canvas.drawCircle(width / 2f, height / 2f + 7f, width * 0.40f, paint)
        paint.color = color
        canvas.drawCircle(width / 2f, height / 2f + 7f, width * 0.29f, paint)
    } else {
        paint.color = color
        canvas.drawCircle(width / 2f, 32f, 27f, paint)
        val path = android.graphics.Path().apply {
            moveTo(width / 2f - 17f, 48f)
            lineTo(width / 2f, 88f)
            lineTo(width / 2f + 17f, 48f)
            close()
        }
        canvas.drawPath(path, paint)
        paint.color = AndroidColor.WHITE
        canvas.drawCircle(width / 2f, 32f, 10f, paint)
    }
    return bitmap
}

private const val HEADING_UPDATE_INTERVAL_MS = 100L
private const val HEADING_ANIMATION_MS = 350

/**
 * Returns the horizontal heading of the direction in which a bicycle-mounted
 * phone's rear camera points. When the phone is almost flat, the rear-camera
 * axis has no useful horizontal projection, so the top edge is used instead.
 */
private fun mountedPhoneHeading(rotationMatrix: FloatArray): Float {
    val rearCameraEast = -rotationMatrix[2]
    val rearCameraNorth = -rotationMatrix[5]
    val rearHorizontal = kotlin.math.hypot(rearCameraEast, rearCameraNorth)
    val east: Float
    val north: Float
    if (rearHorizontal >= REAR_CAMERA_HORIZONTAL_MIN) {
        east = rearCameraEast
        north = rearCameraNorth
    } else {
        east = rotationMatrix[1]
        north = rotationMatrix[4]
    }
    return normalizeHeading(Math.toDegrees(kotlin.math.atan2(east, north).toDouble()).toFloat())
}

private fun shortestHeadingDelta(from: Float, to: Float): Float =
    ((to - from + 540f) % 360f) - 180f

private fun normalizeHeading(value: Float): Float = (value % 360f + 360f) % 360f

private const val REAR_CAMERA_HORIZONTAL_MIN = 0.35f
