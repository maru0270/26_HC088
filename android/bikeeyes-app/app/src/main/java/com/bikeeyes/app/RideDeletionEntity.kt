package com.bikeeyes.app

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

@Entity(tableName = "ride_deletions")
data class RideDeletionEntity(
    @PrimaryKey val ride_session_id: String,
    val deleted_at: Long
)

@Dao
interface RideDeletionDao {
    @Upsert
    suspend fun upsert(entity: RideDeletionEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM ride_deletions WHERE ride_session_id=:sessionId)")
    suspend fun exists(sessionId: String): Boolean

    @Query("SELECT ride_session_id FROM ride_deletions")
    suspend fun getAllSessionIds(): List<String>

    @Query("DELETE FROM ride_deletions WHERE ride_session_id=:sessionId")
    suspend fun delete(sessionId: String)
}
