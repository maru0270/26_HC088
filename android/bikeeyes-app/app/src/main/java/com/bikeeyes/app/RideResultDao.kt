package com.bikeeyes.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RideResultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: RideResultEntity): Long

    @Query("SELECT * FROM ride_results ORDER BY ended_at DESC LIMIT 1")
    suspend fun getLatest(): RideResultEntity?

    @Query("SELECT * FROM ride_results ORDER BY ended_at DESC")
    fun observeAll(): Flow<List<RideResultEntity>>

    @Query("SELECT * FROM ride_results ORDER BY ended_at ASC")
    suspend fun getAll(): List<RideResultEntity>

    @Query("SELECT DISTINCT ride_session_id FROM ride_results WHERE sync_status != 'SYNCED'")
    suspend fun getUnsyncedSessionIds(): List<String>

    @Query("SELECT * FROM ride_results WHERE ride_session_id = :sessionId LIMIT 1")
    fun observeBySessionId(sessionId: String): Flow<RideResultEntity?>

    @Query("UPDATE ride_results SET sync_status = :status WHERE ride_session_id = :sessionId")
    suspend fun updateSyncStatus(sessionId: String, status: String)

    @Query("DELETE FROM ride_results WHERE ride_session_id = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)
}
