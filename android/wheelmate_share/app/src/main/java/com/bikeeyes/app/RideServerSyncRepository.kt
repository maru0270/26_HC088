package com.bikeeyes.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RideDownloadSyncStatus { IDLE, SYNCING, SUCCESS, FAILED }

data class RideDownloadSyncState(
    val status: RideDownloadSyncStatus = RideDownloadSyncStatus.IDLE,
    val downloadedCount: Int = 0,
    val totalServerCount: Int = 0,
    val message: String? = null,
    val completedAt: Long? = null
)

object RideServerSyncState {
    private val _state = MutableStateFlow(RideDownloadSyncState())
    val state = _state.asStateFlow()
    internal fun update(value: RideDownloadSyncState) { _state.value = value }
}

class RideServerSyncRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = BikeEyesLocalDatabase.getInstance(appContext)
    private val api = NetworkModule.dynamicSensorApi(appContext)
    private val authSession = AuthSessionStore(appContext)
    private val uploadRepository = SensorUploadRepository(
        api,
        database.localSyncLogDao(),
        database.rideResultDao(),
        database.accidentEventDao(),
        AuthRepository(appContext),
        authSession,
        database.rideDeletionDao()
    )

    suspend fun sync(): Result<Int> {
        if (!authSession.hasSession()) {
            return Result.failure(IllegalStateException("로그인이 필요합니다."))
        }
        RideServerSyncState.update(
            RideDownloadSyncState(
                status = RideDownloadSyncStatus.SYNCING,
                message = "미전송 기록을 서버에 전송하고 있습니다."
            )
        )
        return runCatching {
            uploadRepository.enqueueMissingRideResults(database.rideResultDao().getAll())
            uploadRepository.uploadPendingOnly()

            val tombstones = database.rideDeletionDao().getAllSessionIds().toHashSet()
            val pendingLocal = database.rideResultDao().getUnsyncedSessionIds().toHashSet()
            val serverSessionIds = hashSetOf<String>()
            var offset = 0
            var imported = 0
            var total = 0
            do {
                val response = api.getRides(limit = PAGE_SIZE, offset = offset)
                total = response.total
                serverSessionIds += response.items.map { it.ride_session_id }
                response.items
                    .asSequence()
                    .filterNot { it.ride_session_id in tombstones }
                    .filterNot { it.ride_session_id in pendingLocal }
                    .forEach {
                        database.rideResultDao().insert(it.toEntity())
                        imported++
                    }
                offset += response.items.size
            } while (offset < total && response.items.isNotEmpty())

            database.rideResultDao().getAll()
                .filter {
                    it.sync_status == RideSyncStatus.SYNCED.name &&
                        it.ride_session_id !in serverSessionIds &&
                        it.ride_session_id !in tombstones
                }
                .forEach { database.rideResultDao().deleteBySessionId(it.ride_session_id) }

            RideServerSyncState.update(
                RideDownloadSyncState(
                    status = RideDownloadSyncStatus.SUCCESS,
                    downloadedCount = imported,
                    totalServerCount = total,
                    message = "서버 주행 기록 동기화 완료",
                    completedAt = System.currentTimeMillis()
                )
            )
            imported
        }.onFailure {
            RideServerSyncState.update(
                RideDownloadSyncState(
                    status = RideDownloadSyncStatus.FAILED,
                    message = AuthRepository.userMessage(it),
                    completedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun getRideDetail(sessionId: String): ServerRideDetail = api.getRide(sessionId)

    suspend fun getDeletedRides(): List<DeletedRideItem> = api.getDeletedRides()

    suspend fun restoreRide(sessionId: String) {
        api.restoreRide(sessionId)
        database.rideDeletionDao().delete(sessionId)
        sync().getOrThrow()
    }

    suspend fun permanentlyDeleteRide(sessionId: String) {
        api.permanentlyDeleteRide(sessionId)
    }

    suspend fun getSensorLogs(
        sessionId: String,
        imagesOnly: Boolean = false
    ): List<ServerSensorLog> {
        val all = mutableListOf<ServerSensorLog>()
        var offset = 0
        do {
            val page = api.getRideSensorLogs(
                sessionId,
                SENSOR_PAGE_SIZE,
                offset,
                imagesOnly
            )
            all += page.items
            offset += page.items.size
        } while (
            page.items.isNotEmpty() &&
            offset < minOf(page.total.takeIf { it > 0 } ?: Int.MAX_VALUE, MAX_SENSOR_ITEMS)
        )
        return all
    }

    suspend fun getImageSensorLogs(sessionId: String): List<ServerSensorLog> =
        getSensorLogs(sessionId, imagesOnly = true)

    companion object {
        private const val PAGE_SIZE = 100
        private const val SENSOR_PAGE_SIZE = 500
        private const val MAX_SENSOR_ITEMS = 100_000
    }
}
