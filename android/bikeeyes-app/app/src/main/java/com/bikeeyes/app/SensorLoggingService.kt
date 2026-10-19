package com.bikeeyes.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import java.util.concurrent.TimeUnit
import androidx.lifecycle.LifecycleService
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.camera.core.ImageCaptureException
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import android.util.Log
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Multipart
import retrofit2.http.Part
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.view.Surface
import android.view.WindowManager
import java.io.FileOutputStream
import android.widget.Toast



class SensorLoggingService : LifecycleService(), SensorEventListener {
    private val accidentDetector = AccidentDetector(onDetected = { publishAccidentAlert() })
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private lateinit var uploadRepository: SensorUploadRepository

    private var gyroSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private var geomagneticSensor: Sensor? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sendJob: Job? = null

    private var sensorFrameJob: Job? = null
    private var captureFrameChannel: Channel<CapturedFrame>? = null

    private var sensorSampleJob: Job? = null

    private var uploadJob: Job? = null

    private var rideTimerJob: Job? = null

    private var serviceInitialized = false
    private var manuallyPaused = false
    private var activeElapsedSeconds = 0L
    private var imageCapture: ImageCapture? = null
    private var consecutiveImageFailures = 0

    private var latestLatitude = 0.0
    private var latestLongitude = 0.0
    private var latestAccuracy = 0.0
    private var latestSpeed = 0.0
    private var latestDirection = 0.0

    private var previousRideLocation: Location? = null
    private var totalRideDistanceMeters = 0.0

    private var latestGyroX = 0f
    private var latestGyroY = 0f
    private var latestGyroZ = 0f

    private var latestAccelX = 0f
    private var latestAccelY = 0f
    private var latestAccelZ = 0f

    private var latestGeomagneticX = 0f
    private var latestGeomagneticY = 0f
    private var latestGeomagneticZ = 0f

    private val sensorSampleBuffer = mutableListOf<SensorSample>()

    private var collectionPausedByStationary = false
    private var stationaryStartTime: Long? = null
    private var stationaryBaseLocation: Location? = null
    private var movingDetectCount = 0

    private val sensorApi: SensorApi by lazy {
        NetworkModule.dynamicSensorApi(applicationContext)
    }

    private val localDatabase: BikeEyesLocalDatabase by lazy {
        BikeEyesLocalDatabase.getInstance(applicationContext)
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestLatitude = location.latitude
            latestLongitude = location.longitude
            latestAccuracy = location.accuracy.toDouble()
            latestSpeed = location.speed.toDouble()
            latestDirection = location.bearing.toDouble()
            accidentDetector.updateLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy.toDouble(),
                speedMetersPerSecond = location.speed.toDouble().coerceAtLeast(0.0)
            )
            RideTrackingState.updateGpsAccuracy(location.accuracy.toDouble())
            RiskZoneApproachMonitor.onLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = location.accuracy
            )

            val currentSpeedKmh =
                if (location.hasSpeed()) {
                    location.speed.toDouble() * 3.6
                } else {
                    0.0
                }

            if (!manuallyPaused) {
                RideTrackingState.updateCurrentSpeedKmh(
                    speedKmh = currentSpeedKmh.coerceAtLeast(0.0),
                    accuracyMeters = location.accuracy
                )
                updateRideDistance(location)
            }

            Log.d(
                "BikeGPS",
                "GPS 수신: accuracy=${location.accuracy}, speed=${currentSpeedKmh}km/h"
            )

            if (!manuallyPaused) checkStationaryState(location)
        }

        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private fun checkStationaryState(location: Location) {

        Log.d("BikeStationary", "check 호출됨, accuracy=${location.accuracy}")

        // 정확도가 너무 낮으면 판단하지 않음
        if (location.accuracy > MAX_GPS_ACCURACY_M) {
            return
        }

        // 최초 위치 저장
        if (stationaryBaseLocation == null) {
            stationaryBaseLocation = Location(location)
            stationaryStartTime = System.currentTimeMillis()
            return
        }

        val distance = location.distanceTo(stationaryBaseLocation!!)

        Log.d(
            "BikeStationary",
            "distance = %.2f m".format(distance)
        )

        if (distance <= STATIONARY_DISTANCE_M && location.speed <= STATIONARY_SPEED_MPS) {

            if (stationaryStartTime == null) {
                stationaryStartTime = System.currentTimeMillis()
            }

            val stationaryTime =
                System.currentTimeMillis() - stationaryStartTime!!

            Log.d(
                "BikeStationary",
                "stationaryTime = ${stationaryTime / 1000} sec"
            )

            if (!collectionPausedByStationary &&
                stationaryTime >= STATIONARY_TIME_MS
            ) {
                pauseCollectionByStationary()
            }

        } else {

            if ( collectionPausedByStationary &&
                (distance >= RESUME_DISTANCE_M || location.speed >= RESUME_SPEED_MPS)) {
                resumeCollectionByMovement()
            }

            stationaryBaseLocation = Location(location)
            stationaryStartTime = System.currentTimeMillis()

            Log.d(
                "BikeStationary",
                "기준 위치 갱신"
            )
        }
    }
    private fun pauseCollectionByStationary() {
        if (collectionPausedByStationary) return

        collectionPausedByStationary = true

        stopImageCollectionJobs()
        sensorSampleJob?.cancel()

        Log.d("BikeStationary", "정지 상태 감지 → 사진/센서 수집 일시정지")
        showToast("정지 감지: 수집 일시정지")
    }

    private fun resumeCollectionByMovement() {

        if (!collectionPausedByStationary) return

        collectionPausedByStationary = false

        stationaryBaseLocation = null
        stationaryStartTime = null
        movingDetectCount = 0

        if (!manuallyPaused) {
            startSamplingSensorEvery500ms()
            startQueueingEverySecond()
        }

        Log.d(
            "BikeStationary",
            "이동 감지 → 사진/센서 수집 재시작"
        )
        showToast("이동 감지: 수집 재시작")
    }

    private fun startRideTimer() {
        rideTimerJob?.cancel()

        RideTrackingState.startRide()
        RideRecoveryStore(this).begin(
            RideTrackingState.currentRideSessionId(),
            System.currentTimeMillis()
        )
        activeElapsedSeconds = 0L

        previousRideLocation = null
        totalRideDistanceMeters = 0.0

        rideTimerJob = serviceScope.launch {
            while (true) {
                delay(1000L)
                if (!manuallyPaused) {
                    activeElapsedSeconds += 1L
                    RideTrackingState.updateElapsedSeconds(activeElapsedSeconds)
                    RideRecoveryStore(this@SensorLoggingService).update(
                        activeElapsedSeconds, totalRideDistanceMeters
                    )
                }
            }
        }
    }

    private fun showToast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(
                applicationContext,
                message,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate() {
        super.onCreate()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        uploadRepository = SensorUploadRepository(
            sensorApi = sensorApi,
            localSyncLogDao = localDatabase.localSyncLogDao(),
            rideResultDao = localDatabase.rideResultDao(),
            accidentEventDao = localDatabase.accidentEventDao(),
            authRepository = AuthRepository(applicationContext),
            authSessionStore = AuthSessionStore(applicationContext),
            rideDeletionDao = localDatabase.rideDeletionDao()
        )

        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        geomagneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        startForeground(NOTIFICATION_ID, createNotification())

        when (intent?.action) {
            ACTION_PAUSE_RIDE -> {
                pauseRideManually()
                return START_NOT_STICKY
            }
            ACTION_RESUME_RIDE -> {
                resumeRideManually()
                return START_NOT_STICKY
            }
        }

        if (serviceInitialized) return START_NOT_STICKY
        serviceInitialized = true

        registerSensors()
        registerLocationUpdates()

        startRideTimer()

        // 0.5초 센서 샘플링 시작
        startSamplingSensorEvery500ms()

        // 기존 기능
        startCamera()
        startUploadingPendingEvery3Seconds()

        return START_NOT_STICKY
    }

    private fun pauseRideManually() {
        if (!serviceInitialized || manuallyPaused) return
        manuallyPaused = true
        RideTrackingState.setPaused(true)
        stopImageCollectionJobs()
        sensorSampleJob?.cancel()
        synchronized(sensorSampleBuffer) { sensorSampleBuffer.clear() }
        previousRideLocation = null
        accidentDetector.reset()
        Log.d("BikeRide", "사용자 요청으로 주행 수집 일시정지")
    }

    private fun resumeRideManually() {
        if (!serviceInitialized || !manuallyPaused) return
        manuallyPaused = false
        RideTrackingState.setPaused(false)
        previousRideLocation = null
        stationaryBaseLocation = null
        stationaryStartTime = null
        if (!collectionPausedByStationary) {
            startSamplingSensorEvery500ms()
            startQueueingEverySecond()
        }
        Log.d("BikeRide", "사용자 요청으로 주행 수집 재개")
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(locationListener)

        stopImageCollectionJobs()
        uploadJob?.cancel()
        sensorSampleJob?.cancel()
        rideTimerJob?.cancel()

        previousRideLocation = null
        accidentDetector.reset()
        totalRideDistanceMeters = 0.0

        RideTrackingState.resetLiveValues()
        RideTrackingState.updateCameraReady(false)

        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                latestGyroX = event.values[0]
                latestGyroY = event.values[1]
                latestGyroZ = event.values[2]
            }

            Sensor.TYPE_ACCELEROMETER -> {
                latestAccelX = event.values[0]
                latestAccelY = event.values[1]
                latestAccelZ = event.values[2]
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                latestGeomagneticX = event.values[0]
                latestGeomagneticY = event.values[1]
                latestGeomagneticZ = event.values[2]
            }
        }
        if (!manuallyPaused) {
            accidentDetector.update(latestAccelX.toDouble(), latestAccelY.toDouble(), latestAccelZ.toDouble(),
                latestGyroX.toDouble(), latestGyroY.toDouble(), latestGyroZ.toDouble())
        }
    }

    private fun publishAccidentAlert() {
        val acceleration = kotlin.math.sqrt((latestAccelX*latestAccelX + latestAccelY*latestAccelY + latestAccelZ*latestAccelZ).toDouble())
        val rotation = kotlin.math.sqrt((latestGyroX*latestGyroX + latestGyroY*latestGyroY + latestGyroZ*latestGyroZ).toDouble())
        AccidentAlertState.offer(AccidentAlert(ride_session_id=RideTrackingState.currentRideSessionId(), detected_at=System.currentTimeMillis(),
            latitude=latestLatitude, longitude=latestLongitude, accuracy=latestAccuracy, speed=latestSpeed,
            accel_x=latestAccelX.toDouble(), accel_y=latestAccelY.toDouble(), accel_z=latestAccelZ.toDouble(),
            gyro_x=latestGyroX.toDouble(), gyro_y=latestGyroY.toDouble(), gyro_z=latestGyroZ.toDouble(),
            acceleration_magnitude=acceleration, gyro_magnitude=rotation))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun registerSensors() {
        gyroSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        geomagneticSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun registerLocationUpdates() {
        Log.d("BikeGPS", "registerLocationUpdates 시작")

        val fineLocationGranted = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocationGranted = ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        Log.d("BikeGPS", "fine=$fineLocationGranted, coarse=$coarseLocationGranted")

        if (!fineLocationGranted && !coarseLocationGranted) {
            Log.d("BikeGPS", "위치 권한 없음 return")
            return
        }

        val lastLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        lastLocation?.let { location ->
            latestLatitude = location.latitude
            latestLongitude = location.longitude
            latestAccuracy = location.accuracy.toDouble()
            latestSpeed = location.speed.toDouble()
            latestDirection = location.bearing.toDouble()

        Log.d("BikeGPS", "마지막 위치 사용 가능, accuracy=${location.accuracy}")
            checkStationaryState(location)
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            1000L,
            0f,
            locationListener
        )

        Log.d("BikeGPS", "GPS_PROVIDER 등록 완료")

        locationManager.requestLocationUpdates(
            LocationManager.NETWORK_PROVIDER,
            1000L,
            0f,
            locationListener
        )

        Log.d("BikeGPS", "NETWORK_PROVIDER 등록 완료")

        Log.d("BikeGPS", "NETWORK_PROVIDER 위치 업데이트 요청 완료")
    }

    private fun startCamera() {
        Log.d("BikeCamera", "CameraX 초기화 시작")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(currentDisplayRotation())
                    .build()

                imageCapture = capture
                RideTrackingState.updateCameraReady(true)

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    capture
                )

                Log.d("BikeCamera", "CameraX 초기화 성공")

                startQueueingEverySecond()
            } catch (exception: Exception) {
                RideTrackingState.updateCameraReady(false)
                startQueueingEverySecond()
                Log.e("BikeCamera", "CameraX 초기화 실패: ${exception.message}", exception)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private suspend fun captureImageAndGetPath(capturedAt: Long): String? {
        if (!DeviceHealthPolicy.canCaptureImages(this)) {
            Log.w("BikeStorage", "Available storage below 500 MB; image capture paused")
            return null
        }
        val capture = imageCapture ?: return null
        capture.targetRotation = currentDisplayRotation()

        if (capture == null) {
            Log.e("BikeCamera", "imageCapture가 null입니다. 카메라 초기화 실패 또는 미완료")
            return null
        }

        val imageDir = File(getExternalFilesDir(null), "images")
        if (!imageDir.exists()) {
            imageDir.mkdirs()
        }

        val imageFile = File(
            imageDir,
            "bike_${capturedAt}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(imageFile).build()

        return suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        consecutiveImageFailures = 0
                        RideTrackingState.updateCameraReady(true)
                        Log.d("BikeCamera", "원본 사진 저장 성공")

                        val processedPath = preprocessImageForUpload(imageFile.absolutePath)
                        RideTrackingState.updateLowLightDetected(
                            isLowLightImage(processedPath ?: imageFile.absolutePath)
                        )

                        Log.d("BikeCamera", "이미지 전처리 완료")

                        continuation.resume(processedPath ?: imageFile.absolutePath)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        consecutiveImageFailures += 1
                        if (consecutiveImageFailures >= 3) {
                            RideTrackingState.updateCameraReady(false)
                        }
                        Log.e("BikeCamera", "사진 저장 실패: ${exception.message}", exception)
                        continuation.resume(null)
                    }
                }
            )
        }
    }

    private fun preprocessImageForUpload(originalImagePath: String): String? {
        val originalFile = File(originalImagePath)
        if (!originalFile.exists()) return null

        val decodedBitmap = BitmapFactory.decodeFile(originalImagePath) ?: return null
        val originalBitmap = applyExifOrientation(decodedBitmap, originalImagePath)

        val width = originalBitmap.width
        val height = originalBitmap.height

        val cropTop = (height * 0.20).toInt()
        val cropBottom = (height * 0.30).toInt()
        val cropHeight = height - cropTop - cropBottom

        if (cropHeight <= 0) return null

        val croppedBitmap = Bitmap.createBitmap(
            originalBitmap,
            0,
            cropTop,
            width,
            cropHeight
        )

        val resizedBitmap = Bitmap.createScaledBitmap(
            croppedBitmap,
            960,
            960,
            true
        )

        val processedFile = File(
            originalFile.parentFile,
            originalFile.nameWithoutExtension + "_q75_960.jpg"
        )

        FileOutputStream(processedFile).use { outputStream ->
            resizedBitmap.compress(
                Bitmap.CompressFormat.JPEG,
                75,
                outputStream
            )
        }

        if (croppedBitmap != originalBitmap) {
            croppedBitmap.recycle()
        }

        resizedBitmap.recycle()
        originalBitmap.recycle()

        return processedFile.absolutePath
    }

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int =
        runCatching {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager.defaultDisplay.rotation
        }.getOrDefault(Surface.ROTATION_0)

    private fun applyExifOrientation(bitmap: Bitmap, imagePath: String): Bitmap {
        val orientation = runCatching {
            ExifInterface(imagePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postScale(-1f, 1f)
                matrix.postRotate(270f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postScale(-1f, 1f)
                matrix.postRotate(90f)
            }
            else -> return bitmap
        }

        val oriented = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    private fun isLowLightImage(imagePath: String): Boolean {
        val bitmap = BitmapFactory.decodeFile(imagePath) ?: return false
        val sample = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        var luminanceTotal = 0.0
        for (x in 0 until sample.width) {
            for (y in 0 until sample.height) {
                val color = sample.getPixel(x, y)
                val red = android.graphics.Color.red(color)
                val green = android.graphics.Color.green(color)
                val blue = android.graphics.Color.blue(color)
                luminanceTotal += 0.2126 * red + 0.7152 * green + 0.0722 * blue
            }
        }
        val average = luminanceTotal / (sample.width * sample.height)
        if (sample !== bitmap) sample.recycle()
        bitmap.recycle()
        return average < LOW_LIGHT_LUMINANCE_THRESHOLD
    }

    private fun updateRideDistance(location: Location) {
        val maxAllowedAccuracyMeters = 25f
        val minimumSegmentMeters = 2.0
        val maximumAllowedSpeedMetersPerSecond = 20.0

        if (!location.hasAccuracy() ||
            location.accuracy > maxAllowedAccuracyMeters
        ) {
            Log.d(
                "BikeDistance",
                "거리 계산 제외: GPS 정확도=${location.accuracy}m"
            )
            return
        }

        val previousLocation = previousRideLocation

        if (previousLocation == null) {
            previousRideLocation = Location(location)

            Log.d(
                "BikeDistance",
                "거리 기준 위치 설정"
            )
            return
        }

        val segmentDistanceMeters =
            previousLocation.distanceTo(location).toDouble()

        // 2m 미만 이동은 누적하지 않고,
        // 기준 위치도 바꾸지 않아 다음 이동과 합산되도록 한다.
        if (segmentDistanceMeters < minimumSegmentMeters) {
            Log.d(
                "BikeDistance",
                "작은 이동 대기: 구간=${segmentDistanceMeters}m"
            )
            return
        }

        val elapsedTimeSeconds =
            if (
                location.elapsedRealtimeNanos >
                previousLocation.elapsedRealtimeNanos
            ) {
                (
                        location.elapsedRealtimeNanos -
                                previousLocation.elapsedRealtimeNanos
                        ) / 1_000_000_000.0
            } else {
                0.0
            }

        if (elapsedTimeSeconds <= 0.0) {
            Log.d(
                "BikeDistance",
                "거리 계산 제외: 시간 간격 오류"
            )
            return
        }

        val calculatedSpeedMetersPerSecond =
            segmentDistanceMeters / elapsedTimeSeconds

        if (
            calculatedSpeedMetersPerSecond >
            maximumAllowedSpeedMetersPerSecond
        ) {
            Log.d(
                "BikeDistance",
                "비정상 GPS 점프 제외: 구간=${segmentDistanceMeters}m, " +
                        "계산속도=${calculatedSpeedMetersPerSecond}m/s"
            )

            previousRideLocation = Location(location)
            return
        }

        totalRideDistanceMeters += segmentDistanceMeters

        RideTrackingState.updateTotalDistanceMeters(
            totalRideDistanceMeters
        )

        Log.d(
            "BikeDistance",
            "구간=${segmentDistanceMeters}m, " +
                    "누적=${totalRideDistanceMeters}m"
        )

        previousRideLocation = Location(location)
    }

    private fun stopImageCollectionJobs() {
        sendJob?.cancel()
        sensorFrameJob?.cancel()
        captureFrameChannel?.close()
        captureFrameChannel = null
    }

    private fun startQueueingEverySecond() {
        stopImageCollectionJobs()
        val frameChannel = Channel<CapturedFrame>(Channel.UNLIMITED)
        captureFrameChannel = frameChannel

        // Frames are processed in capture order. Waiting for the one-second
        // post-capture window here prevents samples from being shared by two
        // adjacent images while allowing the camera loop to keep a one-second cadence.
        sensorFrameJob = serviceScope.launch {
            for (frame in frameChannel) {
                val remaining = frame.capturedAt + IMAGE_SENSOR_WINDOW_MS - System.currentTimeMillis()
                if (remaining > 0) delay(remaining)
                val windowEnd = frame.capturedAt + IMAGE_SENSOR_WINDOW_MS
                val samplesForThisFrame = synchronized(sensorSampleBuffer) {
                    val selected = sensorSampleBuffer
                        .filter { it.sampled_at > frame.capturedAt && it.sampled_at <= windowEnd }
                        .sortedBy { it.sampled_at }
                    sensorSampleBuffer.removeAll { it.sampled_at <= windowEnd }
                    selected
                }
                val peakSample = selectPeakSample(samplesForThisFrame)
                Log.d("BikeSensorBuffer", "1초 묶음 샘플 개수 = ${samplesForThisFrame.size}")

                uploadRepository.enqueueOnly(
                    createCurrentSensorLogRequest(
                        capturedAt = frame.capturedAt,
                        imagePath = frame.imagePath,
                        samples = samplesForThisFrame,
                        peakSample = peakSample,
                        latitude = frame.latitude,
                        longitude = frame.longitude,
                        accuracy = frame.accuracy,
                        speed = frame.speed,
                        direction = frame.direction
                    )
                )
            }
        }

        sendJob = serviceScope.launch {
            var nextCaptureAt = System.currentTimeMillis()
            while (isActive) {
                val capturedAt = System.currentTimeMillis()
                val frame = CapturedFrame(
                    capturedAt = capturedAt,
                    latitude = latestLatitude,
                    longitude = latestLongitude,
                    accuracy = latestAccuracy,
                    speed = latestSpeed,
                    direction = latestDirection,
                    imagePath = captureImageAndGetPath(capturedAt)
                )
                frameChannel.send(frame)
                nextCaptureAt += IMAGE_CAPTURE_INTERVAL_MS
                val wait = nextCaptureAt - System.currentTimeMillis()
                if (wait > 0) delay(wait)
            }
        }
    }

    private fun selectPeakSample(samples: List<SensorSample>): SensorSample? =
        samples.maxByOrNull { it.impactDelta() }

    private fun SensorSample.impactDelta(): Double {
        val magnitude = kotlin.math.sqrt(
            accel_x * accel_x + accel_y * accel_y + accel_z * accel_z
        )
        return kotlin.math.abs(magnitude - EARTH_GRAVITY)
    }

    private fun startSamplingSensorEvery500ms() {
        sensorSampleJob?.cancel()

        sensorSampleJob = serviceScope.launch {
            while (isActive) {
                val sample = SensorSample(
                    sampled_at = System.currentTimeMillis(),

                    latitude = latestLatitude,
                    longitude = latestLongitude,
                    accuracy = latestAccuracy,
                    speed = latestSpeed,
                    direction = latestDirection,

                    accel_x = latestAccelX.toDouble(),
                    accel_y = latestAccelY.toDouble(),
                    accel_z = latestAccelZ.toDouble(),

                    gyro_x = latestGyroX.toDouble(),
                    gyro_y = latestGyroY.toDouble(),
                    gyro_z = latestGyroZ.toDouble(),

                    geomagnetic_x = latestGeomagneticX.toDouble(),
                    geomagnetic_y = latestGeomagneticY.toDouble(),
                    geomagnetic_z = latestGeomagneticZ.toDouble()
                )

                synchronized(sensorSampleBuffer) {
                    sensorSampleBuffer.add(sample)
                    Log.d("BikeSensorBuffer", "buffer size = ${sensorSampleBuffer.size}")
                }

                delay(500)
            }
        }
    }

    private fun startUploadingPendingEvery3Seconds() {
        uploadJob?.cancel()

        uploadJob = serviceScope.launch {
            while (isActive) {
                uploadRepository.uploadPendingOnly()
                delay(3000)
            }
        }
    }

    private fun createCurrentSensorLogRequest(
        capturedAt: Long,
        imagePath: String?,
        samples: List<SensorSample>,
        peakSample: SensorSample?,
        latitude: Double,
        longitude: Double,
        accuracy: Double,
        speed: Double,
        direction: Double
    ): SensorLogRequest {
        return SensorLogRequest(
            event_uuid = java.util.UUID.randomUUID().toString(),
            ride_session_id = RideTrackingState.currentRideSessionId(),
            captured_at = capturedAt,

            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            speed = speed,
            direction = direction,

            // 이미지 촬영 후 구간의 peak sample을 대표 센서값으로 전송한다.
            // 샘플이 없을 때 0을 보내고, 서버는 peak_sample_at이 없는
            // 신규 빈 구간을 충격 근거로 사용하지 않는다.
            gyro_x = peakSample?.gyro_x ?: 0.0,
            gyro_y = peakSample?.gyro_y ?: 0.0,
            gyro_z = peakSample?.gyro_z ?: 0.0,

            accel_x = peakSample?.accel_x ?: 0.0,
            accel_y = peakSample?.accel_y ?: 0.0,
            accel_z = peakSample?.accel_z ?: 0.0,

            geomagnetic_x = peakSample?.geomagnetic_x ?: 0.0,
            geomagnetic_y = peakSample?.geomagnetic_y ?: 0.0,
            geomagnetic_z = peakSample?.geomagnetic_z ?: 0.0,

            image_path = imagePath,
            sensor_samples = samples,
            peak_sample_at = peakSample?.sampled_at
        )
    }

    private fun createNotification(): android.app.Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("BikeEyes")
            .setContentText("센서 데이터를 수집 중입니다.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BikeEyes sensor logging",
            NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_PAUSE_RIDE = "com.bikeeyes.app.action.PAUSE_RIDE"
        const val ACTION_RESUME_RIDE = "com.bikeeyes.app.action.RESUME_RIDE"
        private const val CHANNEL_ID = "bikeeyes_sensor_logging"
        private const val NOTIFICATION_ID = 1001

        private const val STATIONARY_TIME_MS = 60_000L
        private const val STATIONARY_DISTANCE_M = 10f
        private const val STATIONARY_SPEED_MPS = 0.5

        private const val RESUME_DISTANCE_M = 15f
        private const val RESUME_SPEED_MPS = 1.0

        private const val MAX_GPS_ACCURACY_M = 150f
        private const val MOVING_DETECT_REQUIRED_COUNT = 3
        private const val LOW_LIGHT_LUMINANCE_THRESHOLD = 45.0
        private const val IMAGE_CAPTURE_INTERVAL_MS = 1_000L
        private const val IMAGE_SENSOR_WINDOW_MS = 1_000L
        private const val EARTH_GRAVITY = 9.80665
    }
}

private data class CapturedFrame(
    val capturedAt: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val speed: Double,
    val direction: Double,
    val imagePath: String?,
)

data class SensorLogRequest(
    val event_uuid: String? = null,
    val ride_session_id: String,
    val captured_at: Long,
    val peak_sample_at: Long? = null,

    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val speed: Double,
    val direction: Double,

    val gyro_x: Double,
    val gyro_y: Double,
    val gyro_z: Double,

    val accel_x: Double,
    val accel_y: Double,
    val accel_z: Double,

    val geomagnetic_x: Double,
    val geomagnetic_y: Double,
    val geomagnetic_z: Double,

    val image_path: String?,

    val sensor_samples: List<SensorSample> = emptyList()
)

data class SensorLogResponse(
    val id: Long,
    val message: String
)

data class RideResultResponse(
    val ride_session_id: String,
    val message: String
)

data class SensorSample(
    val sampled_at: Long,

    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Double?,
    val speed: Double?,
    val direction: Double?,

    val accel_x: Double,
    val accel_y: Double,
    val accel_z: Double,

    val gyro_x: Double,
    val gyro_y: Double,
    val gyro_z: Double,

    val geomagnetic_x: Double,
    val geomagnetic_y: Double,
    val geomagnetic_z: Double
)

data class ImageUploadResponse(
    val message: String,
    val image_path: String
)

interface SensorApi {
    @Streaming
    @GET("sensor-logs/{event_uuid}/image")
    suspend fun downloadSensorImage(
        @Path("event_uuid") eventUuid: String
    ): okhttp3.ResponseBody

    @Streaming
    @GET("hazard-zones/{zone_id}/representative-image")
    suspend fun downloadHazardZoneRepresentativeImage(
        @Path("zone_id") zoneId: String
    ): okhttp3.ResponseBody

    @GET("accident-events/{event_uuid}/images")
    suspend fun getAccidentImages(
        @Path("event_uuid") eventUuid: String
    ): AccidentImageListResponse

    @GET("hazard-zones")
    suspend fun getHazardZones(
        @Query("include_candidates") includeCandidates: Boolean = false,
        @Query("include_test_data") includeTestData: Boolean = false
    ): HazardZoneListResponse

    @GET("map/current-location")
    suspend fun getCurrentLocationName(
        @Query("longitude") longitude: Double,
        @Query("latitude") latitude: Double
    ): KakaoCurrentLocationResponse

    @GET("map/places")
    suspend fun searchPlaces(
        @Query("q") query: String,
        @Query("longitude") longitude: Double? = null,
        @Query("latitude") latitude: Double? = null,
        @Query("size") size: Int = 10
    ): KakaoPlaceSearchResponse

    @GET("map/bicycle-route")
    suspend fun getBicycleRoute(
        @Query("start_longitude") startLongitude: Double,
        @Query("start_latitude") startLatitude: Double,
        @Query("end_longitude") endLongitude: Double,
        @Query("end_latitude") endLatitude: Double,
        @Query("destination_name") destinationName: String,
        @Query("route_mode") routeMode: String = "BIKE_ONLY"
    ): BicycleRouteResponse

    @GET("map/walking-bike-route")
    suspend fun getWalkingBikeRoute(
        @Query("start_longitude") startLongitude: Double,
        @Query("start_latitude") startLatitude: Double,
        @Query("end_longitude") endLongitude: Double,
        @Query("end_latitude") endLatitude: Double,
        @Query("destination_name") destinationName: String,
        @Query("route_mode") routeMode: String = "BROAD_FIRST"
    ): BicycleRouteResponse

    @Multipart
    @POST("upload-image")
    suspend fun uploadImage(
        @Part file: MultipartBody.Part,
        @Part("event_uuid") eventUuid: RequestBody
    ): ImageUploadResponse

    @POST("sensor-log")
    suspend fun postSensorLog(
        @Body request: SensorLogRequest
    ): SensorLogResponse

    @POST("accident-event")
    suspend fun postAccidentEvent(@Body request: AccidentEventRequest): SensorLogResponse

    @POST("ride-result")
    suspend fun postRideResult(@Body request: RideResultRequest): RideResultResponse

    @DELETE("rides/{rideSessionId}")
    suspend fun deleteRide(@Path("rideSessionId") rideSessionId: String): RideDeleteResponse

    @GET("deleted-rides")
    suspend fun getDeletedRides(): List<DeletedRideItem>

    @POST("rides/{rideSessionId}/restore")
    suspend fun restoreRide(@Path("rideSessionId") rideSessionId: String): MessageResponse

    @DELETE("rides/{rideSessionId}/permanent")
    suspend fun permanentlyDeleteRide(
        @Path("rideSessionId") rideSessionId: String
    ): MessageResponse

    @GET("rides")
    suspend fun getRides(
        @retrofit2.http.Query("limit") limit: Int,
        @retrofit2.http.Query("offset") offset: Int
    ): ServerRideListResponse

    @GET("rides/{rideSessionId}")
    suspend fun getRide(@Path("rideSessionId") rideSessionId: String): ServerRideDetail

    @GET("rides/{rideSessionId}/sensor-logs")
    suspend fun getRideSensorLogs(
        @Path("rideSessionId") rideSessionId: String,
        @retrofit2.http.Query("limit") limit: Int,
        @retrofit2.http.Query("offset") offset: Int,
        @retrofit2.http.Query("images_only") imagesOnly: Boolean = false
    ): ServerSensorListResponse
}
