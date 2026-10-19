package com.bikeeyes.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalSyncLogDao {
    @Insert
    suspend fun insert(log: LocalSyncLogEntity): Long

    @Query(
        """
        SELECT * FROM local_sync_logs
        WHERE data_type = 'SENSOR_LOG'
        ORDER BY created_at DESC
        """
    )
    fun observeSensorLogs(): Flow<List<LocalSyncLogEntity>>

    @Query(
        """
        SELECT * FROM local_sync_logs
        WHERE data_type = 'SENSOR_LOG' AND ride_session_id = :sessionId
        ORDER BY created_at DESC
        """
    )
    fun observeSensorLogsBySession(sessionId: String): Flow<List<LocalSyncLogEntity>>

    @Query(
        """
        SELECT * FROM local_sync_logs
        WHERE local_sync_id = :id AND data_type = 'SENSOR_LOG'
        LIMIT 1
        """
    )
    fun observeSensorLog(id: Long): Flow<LocalSyncLogEntity?>

    @Query(
        """
        SELECT * FROM local_sync_logs
        WHERE (sync_status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= :now))
           OR (
                sync_status IN ('FAILED', 'RETRYING')
                AND (next_retry_at IS NULL OR next_retry_at <= :now)
                AND (sync_status != 'RETRYING' OR last_retry_at IS NULL OR last_retry_at < :retryingBefore)
           )
        ORDER BY is_crash_related DESC, created_at ASC
        LIMIT :limit
        """
    )
    suspend fun getPendingLogs(
        limit: Int,
        now: Long,
        retryingBefore: Long
    ): List<LocalSyncLogEntity>

    @Query(
        """
    SELECT * FROM local_sync_logs
    WHERE sync_status = 'SYNCED'
      AND synced_at IS NOT NULL
      AND synced_at < :deleteBefore
      AND payload LIKE '%image_path%'
    ORDER BY synced_at ASC
    LIMIT :limit
    """
    )
    suspend fun getSyncedLogsForImageDelete(
        deleteBefore: Long,
        limit: Int
    ): List<LocalSyncLogEntity>

    @Query(
        """
        UPDATE local_sync_logs
        SET sync_status = 'RETRYING',
            retry_count = retry_count + 1,
            last_retry_at = :lastRetryAt,
            next_retry_at = NULL
        WHERE local_sync_id = :id
        """
    )
    suspend fun markRetrying(id: Long, lastRetryAt: Long)

    @Query(
        """
        UPDATE local_sync_logs
        SET sync_status = 'FAILED',
            last_error_message = :message
        WHERE local_sync_id = :id
        """
    )
    suspend fun markFailed(id: Long, message: String)

    @Query(
        """
        UPDATE local_sync_logs SET
            sync_status = 'FAILED',
            last_error_message = :message,
            error_category = :category,
            http_status = :httpStatus,
            next_retry_at = :nextRetryAt
        WHERE local_sync_id = :id
        """
    )
    suspend fun markTransientFailure(
        id: Long,
        message: String,
        category: String,
        httpStatus: Int?,
        nextRetryAt: Long
    )

    @Query(
        """
        UPDATE local_sync_logs SET
            sync_status = 'AUTH_REQUIRED',
            last_error_message = :message,
            error_category = 'AUTH',
            http_status = 401,
            next_retry_at = NULL
        WHERE local_sync_id = :id
        """
    )
    suspend fun markAuthRequired(id: Long, message: String)

    @Query(
        """
        UPDATE local_sync_logs SET
            sync_status = 'PERMANENT_FAILURE',
            last_error_message = :message,
            error_category = :category,
            http_status = :httpStatus,
            next_retry_at = NULL
        WHERE local_sync_id = :id
        """
    )
    suspend fun markPermanentFailure(
        id: Long,
        message: String,
        category: String,
        httpStatus: Int?
    )

    @Query(
        """
        UPDATE local_sync_logs SET
            sync_status = 'PENDING',
            next_retry_at = :now,
            last_error_message = NULL,
            error_category = NULL,
            http_status = NULL
        WHERE sync_status = 'AUTH_REQUIRED'
        """
    )
    suspend fun releaseAuthRequired(now: Long): Int

    @Query(
        """
        UPDATE local_sync_logs SET
            sync_status = 'PENDING',
            next_retry_at = :now,
            last_error_message = NULL,
            error_category = NULL,
            http_status = NULL
        WHERE sync_status IN ('FAILED', 'AUTH_REQUIRED', 'PERMANENT_FAILURE')
        """
    )
    suspend fun resetFailuresForManualRetry(now: Long): Int

    @Query(
        """
        SELECT * FROM local_sync_logs
        WHERE sync_status IN ('FAILED', 'AUTH_REQUIRED', 'PERMANENT_FAILURE')
        ORDER BY created_at DESC LIMIT :limit
        """
    )
    suspend fun getRecentFailures(limit: Int = 20): List<LocalSyncLogEntity>

    @Query(
        """
        UPDATE local_sync_logs
        SET sync_status = 'SYNCED',
            synced_at = :syncedAt,
            next_retry_at = NULL,
            last_error_message = NULL,
            error_category = NULL,
            http_status = NULL,
            is_recovered = 1,
            recovered_at = :syncedAt
        WHERE local_sync_id = :id
        """
    )
    suspend fun markSynced(id: Long, syncedAt: Long)

    @Query("SELECT COUNT(*) FROM local_sync_logs WHERE sync_status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("SELECT MIN(created_at) FROM local_sync_logs WHERE sync_status != 'SYNCED'")
    suspend fun oldestUnsyncedAt(): Long?

    @Query(
        """
        SELECT COUNT(*) FROM local_sync_logs
        WHERE data_type = :dataType AND ride_session_id = :sessionId
        """
    )
    suspend fun countByTypeAndSession(dataType: String, sessionId: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM local_sync_logs
        WHERE ride_session_id = :sessionId
          AND sync_status != 'SYNCED'
        """
    )
    suspend fun countUnsyncedByRideSession(sessionId: String): Int

    @Query(
        """
        DELETE FROM local_sync_logs
        WHERE sync_status = 'SYNCED'
          AND synced_at IS NOT NULL
          AND synced_at < :syncedBefore
        """
    )


    suspend fun deleteSyncedBefore(syncedBefore: Long)
}
