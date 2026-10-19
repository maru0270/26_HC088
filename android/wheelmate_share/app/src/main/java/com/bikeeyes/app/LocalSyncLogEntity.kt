package com.bikeeyes.app

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "local_sync_logs",
    indices = [
        Index("ride_session_id"),
        Index(value = ["event_uuid", "data_type"]),
        Index(value = ["sync_status", "created_at"]),
        Index(value = ["sync_status", "next_retry_at", "created_at"])
    ]
)
data class LocalSyncLogEntity(
    @PrimaryKey(autoGenerate = true)
    val local_sync_id: Long = 0,
    val event_uuid: String,
    val ride_session_id: String?,
    val data_type: String,
    val payload: String,
    val sync_status: String,
    val retry_count: Int,
    val last_error_message: String?,
    val is_crash_related: Boolean,
    val created_at: Long,
    val last_retry_at: Long?,
    val synced_at: Long?,
    val is_recovered: Boolean,
    val recovered_at: Long?,
    val next_retry_at: Long? = null,
    val error_category: String? = null,
    val http_status: Int? = null
)
