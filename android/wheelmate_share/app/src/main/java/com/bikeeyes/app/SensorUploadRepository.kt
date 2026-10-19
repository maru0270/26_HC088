package com.bikeeyes.app

import com.google.gson.Gson
import java.util.UUID
import java.io.File
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import kotlin.math.abs
import kotlin.math.sqrt

class SensorUploadRepository(
    private val sensorApi: SensorApi,
    private val localSyncLogDao: LocalSyncLogDao,
    private val rideResultDao: RideResultDao,
    private val accidentEventDao: AccidentEventDao,
    private val authRepository: AuthRepository? = null,
    private val authSessionStore: AuthSessionStore? = null,
    private val rideDeletionDao: RideDeletionDao? = null
) {
    private val gson = Gson()

    suspend fun enqueueOnly(request: SensorLogRequest) {

        Log.d(
            "BikeSensorBuffer",
            "enqueueOnly sensor_samples = ${request.sensor_samples.size}"
        )

        /*
         * A ride can be stopped while the final collection bundle is being
         * persisted. Keep the counter update and Room insert in one
         * non-cancellable section so the ride summary cannot miss a bundle
         * that was already accepted for local storage.
         */
        withContext(NonCancellable) {
            RideTrackingState.incrementCollectedFrameCount()
            savePendingLog(request)
        }
    }
    suspend fun uploadPendingOnly() = uploadMutex.withLock {
        if (System.currentTimeMillis() < nextAllowedUploadAt) return@withLock
        // Logged-out rides remain in Room and are resumed after the next successful login.
        if (authSessionStore != null && !authSessionStore.hasSession()) return@withLock
        if (authSessionStore?.hasSession() == true &&
            authSessionStore.accessTokenNeedsRefresh()
        ) {
            runCatching { authRepository?.refresh() }
                .onFailure { error ->
                    if (AuthRepository.isDefinitiveSessionRejection(error)) {
                        authSessionStore.clear()
                        AuthExpiryState.notifyExpired()
                    }
                    Log.w("BikeAuth", "토큰 갱신 실패로 이번 동기화를 보류합니다.", error)
                    return@withLock
                }
        }
        localSyncLogDao.releaseAuthRequired(System.currentTimeMillis())
        uploadPendingLogs()
        deleteSyncedLocalImages()
        deleteOldSyncedLogs()
    }
    suspend fun enqueueAccident(request: AccidentEventRequest) {
        val now = System.currentTimeMillis()
        localSyncLogDao.insert(LocalSyncLogEntity(event_uuid=request.event_uuid, ride_session_id=request.ride_session_id,
            data_type="ACCIDENT_EVENT", payload=gson.toJson(request), sync_status="PENDING", retry_count=0,
            last_error_message=null, is_crash_related=true, created_at=now, last_retry_at=null, synced_at=null,
            is_recovered=false, recovered_at=null))
    }

    suspend fun enqueueRideResult(request: RideResultRequest) {
        val now = System.currentTimeMillis()
        localSyncLogDao.insert(
            LocalSyncLogEntity(
                event_uuid = request.ride_session_id,
                ride_session_id = request.ride_session_id,
                data_type = "RIDE_RESULT",
                payload = gson.toJson(request),
                sync_status = "PENDING",
                retry_count = 0,
                last_error_message = null,
                is_crash_related = false,
                created_at = now,
                last_retry_at = null,
                synced_at = null,
                is_recovered = false,
                recovered_at = null
            )
        )
    }

    suspend fun deleteRide(sessionId: String) {
        val now = System.currentTimeMillis()
        rideDeletionDao?.upsert(RideDeletionEntity(sessionId, now))
        localSyncLogDao.insert(
            LocalSyncLogEntity(
                event_uuid = "delete-$sessionId",
                ride_session_id = sessionId,
                data_type = "RIDE_DELETE",
                payload = sessionId,
                sync_status = "PENDING",
                retry_count = 0,
                last_error_message = null,
                is_crash_related = false,
                created_at = now,
                last_retry_at = null,
                synced_at = null,
                is_recovered = false,
                recovered_at = null
            )
        )
        rideResultDao.deleteBySessionId(sessionId)
        uploadPendingOnly()
    }

    suspend fun enqueueMissingRideResults(results: List<RideResultEntity>) {
        for (result in results) {
            val sessionId = result.ride_session_id
            if (sessionId.isBlank()) continue
            if (result.sync_status == RideSyncStatus.SYNCED.name) continue
            if (rideDeletionDao?.exists(sessionId) == true) continue
            val alreadyQueued = localSyncLogDao.countByTypeAndSession(
                dataType = "RIDE_RESULT",
                sessionId = sessionId
            ) > 0
            if (!alreadyQueued) {
                enqueueRideResult(result.toRequest())
                rideResultDao.updateSyncStatus(
                    sessionId,
                    if (RideTrackingState.serverReachable.value) {
                        RideSyncStatus.UPLOADING.name
                    } else {
                        RideSyncStatus.PENDING.name
                    }
                )
            }
        }
    }

    private suspend fun savePendingLog(request: SensorLogRequest) {
        val now = System.currentTimeMillis()
        localSyncLogDao.insert(
            LocalSyncLogEntity(
                event_uuid = request.event_uuid ?: UUID.randomUUID().toString(),
                ride_session_id = request.ride_session_id,
                data_type = "SENSOR_LOG",
                payload = gson.toJson(request),
                sync_status = "PENDING",
                retry_count = 0,
                last_error_message = null,
                is_crash_related = false,
                created_at = now,
                last_retry_at = null,
                synced_at = null,
                is_recovered = false,
                recovered_at = null
            )
        )
    }

    suspend fun deleteSyncedLocalImages() {
        val deleteBefore = System.currentTimeMillis() - LOCAL_IMAGE_DELETE_DELAY_MS

        val syncedLogs = localSyncLogDao.getSyncedLogsForImageDelete(
            deleteBefore = deleteBefore,
            limit = IMAGE_DELETE_LIMIT
        )

        for (log in syncedLogs) {
            val request = gson.fromJson(log.payload, SensorLogRequest::class.java)
            val localImagePath = request.image_path

            if (!localImagePath.isNullOrBlank()) {
                val imageFile = File(localImagePath)

                if (imageFile.exists()) {
                    imageFile.delete()
                }
            }
        }
    }

    private suspend fun uploadPendingLogs() {
        val now = System.currentTimeMillis()
        val pendingLogs = localSyncLogDao.getPendingLogs(
            limit = UPLOAD_LIMIT,
            now = now,
            retryingBefore = now - STALE_RETRYING_TIMEOUT_MS
        )

        for (pendingLog in pendingLogs) {
            localSyncLogDao.markRetrying(
                id = pendingLog.local_sync_id,
                lastRetryAt = System.currentTimeMillis()
            )

            try {
                if (pendingLog.data_type == "RIDE_DELETE") {
                    sensorApi.deleteRide(pendingLog.payload)
                    RideTrackingState.reportServerSuccess()
                    localSyncLogDao.markSynced(
                        pendingLog.local_sync_id,
                        System.currentTimeMillis()
                    )
                    continue
                }
                if (pendingLog.data_type == "ACCIDENT_EVENT") {
                    sensorApi.postAccidentEvent(gson.fromJson(pendingLog.payload, AccidentEventRequest::class.java))
                    RideTrackingState.reportServerSuccess()
                    localSyncLogDao.markSynced(pendingLog.local_sync_id, System.currentTimeMillis())
                    accidentEventDao.updateSyncStatus(pendingLog.event_uuid, "SYNCED")
                    updateRideSyncStatusIfComplete(pendingLog.ride_session_id)
                    continue
                }
                if (pendingLog.data_type == "RIDE_RESULT") {
                    sensorApi.postRideResult(
                        gson.fromJson(pendingLog.payload, RideResultRequest::class.java)
                    )
                    RideTrackingState.reportServerSuccess()
                    localSyncLogDao.markSynced(
                        pendingLog.local_sync_id,
                        System.currentTimeMillis()
                    )
                    updateRideSyncStatusIfComplete(pendingLog.ride_session_id)
                    continue
                }
                val request = gson.fromJson(pendingLog.payload, SensorLogRequest::class.java)
                // Upgrade any legacy pending Room payload before upload. This
                // keeps unsynced historical bundles compatible with the new
                // post-capture window contract without changing accident logs.
                val normalizedRequest = normalizeImageSensorWindow(request)

                val serverImagePath = uploadImageIfExists(
                    normalizedRequest.image_path,
                    pendingLog.event_uuid
                )
                @Suppress("SENSELESS_COMPARISON")
                val normalizedSessionId =
                    if (request.ride_session_id == null || request.ride_session_id.isBlank()) {
                        pendingLog.ride_session_id ?: "legacy-${pendingLog.local_sync_id}"
                    } else {
                        request.ride_session_id
                    }

                val requestForServer = normalizedRequest.copy(
                    event_uuid = pendingLog.event_uuid,
                    ride_session_id = normalizedSessionId,
                    image_path = serverImagePath ?: normalizedRequest.image_path
                )

                sensorApi.postSensorLog(requestForServer)
                RideTrackingState.reportServerSuccess()
                localSyncLogDao.markSynced(
                    id = pendingLog.local_sync_id,
                    syncedAt = System.currentTimeMillis()
                )
                updateRideSyncStatusIfComplete(pendingLog.ride_session_id)
            } catch (exception: Exception) {
                var decision = UploadRetryPolicy.classify(
                    exception,
                    pendingLog.retry_count + 1
                )
                if (decision.kind == UploadFailureKind.AUTH) {
                    val repository = authRepository
                    val refreshResult = if (repository != null) {
                        runCatching { repository.refresh() }
                    } else {
                        Result.failure(IllegalStateException("인증 저장소가 없습니다."))
                    }
                    val refreshed = refreshResult.isSuccess
                    if (refreshed) {
                        decision = UploadFailureDecision(
                            UploadFailureKind.TRANSIENT,
                            "AUTH_REFRESHED",
                            401,
                            0L
                        )
                    } else {
                        val refreshError = refreshResult.exceptionOrNull()
                        if (refreshError != null &&
                            AuthRepository.isDefinitiveSessionRejection(refreshError)
                        ) {
                            authSessionStore?.clear()
                            AuthExpiryState.notifyExpired()
                        }
                    }
                }
                RideTrackingState.reportServerFailure(
                    exception.message ?: exception.javaClass.simpleName
                )
                Log.e(
                    "BikeUpload",
                    "전송 실패: id=${pendingLog.local_sync_id}, " +
                            "type=${pendingLog.data_type}, session=${pendingLog.ride_session_id}",
                    exception
                )
                val errorMessage =
                    (exception.message ?: exception.javaClass.simpleName).take(1000)
                when (decision.kind) {
                    UploadFailureKind.AUTH -> localSyncLogDao.markAuthRequired(
                        pendingLog.local_sync_id,
                        errorMessage
                    )
                    UploadFailureKind.PERMANENT -> localSyncLogDao.markPermanentFailure(
                        pendingLog.local_sync_id,
                        errorMessage,
                        decision.category,
                        decision.httpStatus
                    )
                    UploadFailureKind.TRANSIENT -> localSyncLogDao.markTransientFailure(
                        pendingLog.local_sync_id,
                        errorMessage,
                        decision.category,
                        decision.httpStatus,
                        System.currentTimeMillis() +
                            (decision.retryDelayMs ?: NETWORK_RETRY_DELAY_MS)
                    )
                }
                if (pendingLog.data_type == "ACCIDENT_EVENT") {
                    accidentEventDao.updateSyncStatus(pendingLog.event_uuid, "FAILED")
                }
                pendingLog.ride_session_id?.let { sessionId ->
                    rideResultDao.updateSyncStatus(sessionId, RideSyncStatus.PENDING.name)
                }
                if (decision.kind == UploadFailureKind.AUTH) return
                if (decision.kind == UploadFailureKind.TRANSIENT &&
                    decision.category == "NETWORK"
                ) {
                    Log.w("BikeUpload", "네트워크 오류로 현재 업로드 배치를 중단하고 다음 주기에 재시도합니다.")
                    break
                }
                continue
            }
        }
    }

    private fun normalizeImageSensorWindow(request: SensorLogRequest): SensorLogRequest {
        val end = request.captured_at + IMAGE_SENSOR_WINDOW_MS
        val samples = request.sensor_samples
            .filter { it.sampled_at > request.captured_at && it.sampled_at <= end }
            .sortedBy { it.sampled_at }
        val peak = samples.maxByOrNull { sample ->
            abs(
                sqrt(
                    sample.accel_x * sample.accel_x +
                        sample.accel_y * sample.accel_y +
                        sample.accel_z * sample.accel_z
                ) - EARTH_GRAVITY
            )
        } ?: return request
        return request.copy(
            sensor_samples = samples,
            peak_sample_at = peak.sampled_at,
            accel_x = peak.accel_x,
            accel_y = peak.accel_y,
            accel_z = peak.accel_z,
            gyro_x = peak.gyro_x,
            gyro_y = peak.gyro_y,
            gyro_z = peak.gyro_z,
            geomagnetic_x = peak.geomagnetic_x,
            geomagnetic_y = peak.geomagnetic_y,
            geomagnetic_z = peak.geomagnetic_z
        )
    }

    private suspend fun updateRideSyncStatusIfComplete(sessionId: String?) {
        if (sessionId.isNullOrBlank()) return

        if (localSyncLogDao.countUnsyncedByRideSession(sessionId) == 0) {
            rideResultDao.updateSyncStatus(sessionId, RideSyncStatus.SYNCED.name)
            RideTrackingState.updateLastRideSyncStatus(
                rideSessionId = sessionId,
                syncStatus = RideSyncStatus.SYNCED
            )
        }
    }
    private suspend fun uploadImageIfExists(
        localImagePath: String?,
        eventUuid: String
    ): String? {
        Log.d("BikeUpload", "로컬 이미지 존재 여부 = ${!localImagePath.isNullOrBlank()}")

        if (localImagePath.isNullOrBlank()) {
            Log.e("BikeUpload", "image_path가 비어 있음")
            return null
        }

        val imageFile = File(localImagePath)

        Log.d("BikeUpload", "imageFile.exists = ${imageFile.exists()}")

        if (!imageFile.exists()) {
            Log.e("BikeUpload", "이미지 파일이 휴대폰에 없음")
            return null
        }

        val requestBody = imageFile.asRequestBody("image/jpeg".toMediaTypeOrNull())

        val multipartBody = MultipartBody.Part.createFormData(
            name = "file",
            filename = imageFile.name,
            body = requestBody
        )

        Log.d("BikeUpload", "이미지 업로드 시작")

        val eventUuidBody = eventUuid.toRequestBody("text/plain".toMediaTypeOrNull())
        val response = sensorApi.uploadImage(multipartBody, eventUuidBody)

        Log.d("BikeUpload", "이미지 업로드 성공")

        return response.image_path
    }

    private suspend fun deleteOldSyncedLogs() {
        val retentionStart = System.currentTimeMillis() - SYNCED_RETENTION_MS
        localSyncLogDao.deleteSyncedBefore(retentionStart)
    }

    companion object {
        private val uploadMutex = Mutex()
        @Volatile private var nextAllowedUploadAt = 0L
        private const val NETWORK_RETRY_DELAY_MS = 30_000L
        private const val UPLOAD_LIMIT = 100
        private const val STALE_RETRYING_TIMEOUT_MS = 30_000L
        private const val SYNCED_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        private const val LOCAL_IMAGE_DELETE_DELAY_MS = 24L * 60 * 60 * 1000
        private const val IMAGE_DELETE_LIMIT = 100
        private const val IMAGE_SENSOR_WINDOW_MS = 1_000L
        private const val EARTH_GRAVITY = 9.80665

        fun resetNetworkBackoff() {
            nextAllowedUploadAt = 0L
        }
    }
}
