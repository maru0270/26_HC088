package com.bikeeyes.app

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

data class SignupRequest(
    val email: String,
    val nickname: String,
    val phone: String?,
    val password: String,
    val device_id: String,
    val device_name: String,
    val terms_required: Boolean,
    val privacy_required: Boolean,
    val collection_required: Boolean,
    val ai_research_optional: Boolean,
    val long_term_optional: Boolean
)

data class LoginRequest(
    val email: String,
    val password: String,
    val device_id: String,
    val device_name: String
)

data class RefreshRequest(val refresh_token: String, val device_id: String)
data class LogoutRequest(val refresh_token: String)
data class PasswordChangeRequest(val current_password: String, val new_password: String)
data class PasswordResetRequest(val email: String)

data class AuthTokenResponse(
    val user_id: Long,
    val nickname: String? = null,
    val must_change_password: Boolean = false,
    val access_token: String,
    val refresh_token: String,
    val token_type: String,
    val access_expires_in: Long,
    val refresh_expires_in: Long
)

data class CurrentUserResponse(
    val user_id: Long,
    val email: String,
    val nickname: String,
    val phone: String?,
    val must_change_password: Boolean,
    val role: String = "USER",
    val device_id: String
)

data class HazardFeedbackRequest(val feedback_type: String)

data class HazardFeedbackCurrent(
    val feedback_type: String,
    val created_at: String,
    val updated_at: String
)

data class HazardFeedbackAggregate(
    val danger: Int,
    val caution: Int,
    val resolved: Int,
    val total: Int
)

data class HazardFeedbackResponse(
    val zone_id: String,
    val can_submit: Boolean,
    val my_feedback: HazardFeedbackCurrent?,
    val aggregate: HazardFeedbackAggregate,
    val changed: Boolean = false,
    val contribution_created: Boolean = false
)

data class SafetyBadgeThreshold(val name: String, val threshold: Int)

data class SafetyBadgeProgress(
    val current_badge: String?,
    val current_threshold: Int,
    val next_badge: String?,
    val next_threshold: Int?,
    val remaining_to_next: Int,
    val thresholds: List<SafetyBadgeThreshold>
)

data class SafetyContributionRecent(
    val contribution_type: String,
    val zone_id: String,
    val zone_title: String,
    val created_at: String
)

data class SafetyContributionResponse(
    val confirmation_count: Int,
    val establishment_count: Int,
    val resolution_count: Int,
    val total_count: Int,
    val badge: SafetyBadgeProgress,
    val recent: List<SafetyContributionRecent>
)

data class WeeklySafetyRankingItem(
    val rank: Int,
    val nickname: String,
    val contribution_count: Int
)

data class WeeklySafetyRankingResponse(
    val timezone: String,
    val starts_at: String,
    val ends_at: String,
    val items: List<WeeklySafetyRankingItem>
)

data class UserDataSummaryResponse(
    val rides: Int,
    val sensor_logs: Int,
    val accident_events: Int,
    val recoverable_rides: Int,
    val retention: Map<String, Int>
)

data class AdminDiagnosticsResponse(
    val users: Int,
    val rides: Int,
    val pending_deletions: Int,
    val sensor_logs: Int,
    val accident_events: Int,
    val last_system_log_at: String?,
    val image_storage_directory: String,
    val image_max_bytes: Long,
    val orphan_sensor_waiting: Int = 0,
    val orphan_sensor_confirmed: Int = 0,
    val orphan_sensor_resolved: Int = 0,
    val legacy_sensor_without_uuid: Int = 0,
    val referenced_images: Int = 0,
    val missing_image_files: Int = 0,
    val latest_migration: String? = null
)

data class AdminDashboardResponse(
    val active_users: Int,
    val new_users_today: Int,
    val registered_devices: Int,
    val rides: Int,
    val rides_today: Int,
    val sensor_logs: Int,
    val images: Int,
    val accident_events: Int,
    val candidate_hazard_zones: Int,
    val confirmed_hazard_zones: Int,
    val pending_ride_deletions: Int,
    val warnings_last_24_hours: Int
)

data class AdminUserItem(
    val user_id: Long,
    val email: String,
    val nickname: String,
    val phone: String?,
    val role: String,
    val is_active: Boolean,
    val created_at: String,
    val updated_at: String,
    val device_count: Int,
    val last_seen_at: String?,
    val ride_count: Int,
    val sensor_log_count: Int,
    val accident_count: Int
)

data class AdminUserListResponse(
    val total: Int,
    val items: List<AdminUserItem>
)

data class AdminUserRideItem(
    val ride_session_id: String,
    val started_at: Long,
    val ended_at: Long,
    val elapsed_seconds: Long,
    val total_distance_meters: Double,
    val collected_frame_count: Int,
    val accident_count: Int
)

data class AdminUserDetailResponse(
    val user_id: Long,
    val email: String,
    val nickname: String,
    val phone: String?,
    val role: String,
    val is_active: Boolean,
    val created_at: String,
    val updated_at: String,
    val device_count: Int,
    val last_seen_at: String?,
    val ride_count: Int,
    val recoverable_ride_count: Int,
    val sensor_log_count: Int,
    val image_count: Int,
    val accident_count: Int,
    val recent_rides: List<AdminUserRideItem>
)

data class AdminRideItem(
    val ride_session_id: String,
    val user_id: Long?,
    val email: String?,
    val nickname: String?,
    val device_id: String?,
    val started_at: Long,
    val ended_at: Long,
    val elapsed_seconds: Long,
    val total_distance_meters: Double,
    val average_speed_kmh: Double,
    val max_speed_kmh: Double,
    val expected_sensor_logs: Int,
    val expected_accidents: Int,
    val deleted_at: String?,
    val received_sensor_logs: Int,
    val image_count: Int,
    val received_accidents: Int,
    val sync_status: String
)

data class AdminRideListResponse(
    val total: Int,
    val items: List<AdminRideItem>
)

data class AdminRideSummary(
    val ride_session_id: String,
    val user_id: Long?,
    val email: String?,
    val nickname: String?,
    val device_id: String?,
    val started_at: Long,
    val ended_at: Long,
    val elapsed_seconds: Long,
    val total_distance_meters: Double,
    val average_speed_kmh: Double,
    val max_speed_kmh: Double,
    val expected_sensor_logs: Int,
    val expected_accidents: Int,
    val deleted_at: String?
)

data class AdminSensorRecord(
    val event_uuid: String?,
    val captured_at: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Double?,
    val speed: Double?,
    val accel_x: Double,
    val accel_y: Double,
    val accel_z: Double,
    val gyro_x: Double,
    val gyro_y: Double,
    val gyro_z: Double,
    val geomagnetic_x: Double,
    val geomagnetic_y: Double,
    val geomagnetic_z: Double,
    val has_image: Boolean
)

data class AdminAccidentRecord(
    val event_uuid: String,
    val detected_at: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Double?,
    val speed: Double?,
    val acceleration_magnitude: Double,
    val gyro_magnitude: Double,
    val confirmation_status: String,
    val is_false_positive: Boolean
)

data class AdminRideDetailResponse(
    val ride: AdminRideSummary,
    val sensor_total: Int,
    val sensors_limited: Boolean,
    val sensors: List<AdminSensorRecord>,
    val accidents: List<AdminAccidentRecord>
)

data class AdminHazardZoneItem(
    val zone_id: String,
    val title: String,
    val road_damage_type: String,
    val severity: String,
    val latitude: Double,
    val longitude: Double,
    val radius_meters: Double,
    val detection_count: Int,
    val description: String,
    val first_detected_at: String,
    val last_detected_at: String,
    val source_model: String?,
    val confidence: Double?,
    val status: String,
    val is_test_data: Boolean,
    val updated_at: String,
    val zone_category: String = "ROAD_ANOMALY",
    val is_user_visible: Boolean = false,
    val expires_at: String? = null,
    val confirmation_source: String? = null,
    val confirmation_cancelled_at: String? = null
)

data class AdminHazardZoneListResponse(
    val total: Int,
    val items: List<AdminHazardZoneItem>
)

data class AdminHazardEvidence(
    val event_uuid: String?,
    val ride_session_id: String?,
    val captured_at: Long,
    val peak_sample_at: Long? = null,
    val latitude: Double,
    val longitude: Double,
    val accel_x: Double,
    val accel_y: Double,
    val accel_z: Double,
    val gyro_x: Double,
    val gyro_y: Double,
    val gyro_z: Double,
    val has_image: Boolean,
    val distance_meters: Double
)

data class AdminHazardZoneDetailResponse(
    val zone: AdminHazardZoneItem,
    val evidence: List<AdminHazardEvidence>,
    val feedback_summary: HazardFeedbackAggregate,
    val recent_feedback: List<AdminHazardFeedback>
)

data class AdminHazardFeedback(
    val feedback_type: String,
    val nickname: String,
    val updated_at: String
)

data class HazardZoneStatusUpdateRequest(val status: String)

data class HazardZoneStatusUpdateResponse(
    val message: String,
    val zone_id: String,
    val status: String,
    val is_test_data: Boolean
)

data class AdminReviewItem(
    val review_item_id: Long,
    val sensor_log_id: Long?,
    val event_uuid: String?,
    val ride_session_id: String?,
    val image_path: String?,
    val captured_at: Long,
    val peak_sample_at: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val peak_impact_delta: Double,
    val speed_median: Double,
    val status: String,
    val selected_classes: List<String> = emptyList(),
    val candidate_zone_ids: List<String> = emptyList(),
    val reviewed_at: String? = null,
    val created_at: String = ""
)

data class AdminReviewItemListResponse(
    val items: List<AdminReviewItem>,
    val pending_count: Int,
    val status: String
)

/** 재검토 항목을 열었을 때 표시할 이미지와 촬영 당시 센서 묶음. */
data class AdminReviewItemDetail(
    val review_item_id: Long,
    val sensor_log_id: Long?,
    val event_uuid: String?,
    val ride_session_id: String?,
    val image_path: String?,
    val captured_at: Long,
    val peak_sample_at: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val peak_impact_delta: Double,
    val speed_median: Double,
    val sensor_samples: List<SensorSample> = emptyList(),
    val status: String,
    val selected_classes: List<String> = emptyList(),
    val candidate_zone_ids: List<String> = emptyList(),
    val no_issue_delete_after: Long? = null,
    val reviewed_by: Long? = null,
    val reviewed_at: String? = null,
    val created_at: String = ""
)

data class ReviewItemCandidateRequest(val classes: List<String>)

data class ReviewItemActionResponse(
    val message: String,
    val zone_ids: List<String> = emptyList(),
    val image_delete_after: Long? = null
)

data class SyncIssueItem(
    val ride_session_id: String,
    val expected_sensor_logs: Int,
    val received_sensor_logs: Int,
    val missing_sensor_logs: Int,
    val expected_accidents: Int,
    val received_accidents: Int,
    val missing_accidents: Int,
    val last_sensor_received_at: String?,
    val ended_at: Long
)

data class SyncIssuesResponse(
    val incomplete_ride_count: Int,
    val items: List<SyncIssueItem>
)

data class SystemErrorResponse(
    val system_log_id: Long,
    val request_id: String?,
    val level: String,
    val endpoint: String?,
    val status_code: Int?,
    val message: String,
    val created_at: String
)

data class BackupStatusResponse(
    val status: String,
    val trigger: String? = null,
    val started_at: Long? = null,
    val completed_at: Long? = null,
    val file_name: String? = null,
    val size_bytes: Long? = null,
    val sha256: String? = null,
    val toc_entries: Int? = null,
    val retention_days: Int? = null,
    val error: String? = null
)

data class OperationalHealthResponse(
    val database: String,
    val database_error: String?,
    val database_latency_ms: Double,
    val storage: String,
    val storage_free_bytes: Long,
    val storage_total_bytes: Long,
    val minimum_free_bytes: Long,
    val backup: BackupStatusResponse
)

data class OperationsLast24Hours(
    val server_errors: Int,
    val request_errors: Int,
    val logged_failures: Int,
    val incomplete_rides: Int
)

data class AdminOperationsResponse(
    val health: OperationalHealthResponse,
    val last_24_hours: OperationsLast24Hours,
    val alerts: List<String>
)

data class AuditLogResponse(
    val audit_log_id: Long,
    val actor_user_id: Long?,
    val action: String,
    val target_type: String,
    val target_id: String?,
    val created_at: String
)

data class RetentionRunSummary(
    val run_id: Long,
    val trigger_type: String,
    val status: String,
    val started_at: String,
    val completed_at: String?,
    val result: Map<String, Any?> = emptyMap(),
    val error_message: String?
)

data class RetentionStatusResponse(
    val latest: RetentionRunSummary?,
    val queued_file_deletions: Int,
    val interval_seconds: Long,
    val raw_retention_days: Int,
    val system_log_retention_days: Int,
    val ride_recovery_days: Int
)

data class RetentionRunResponse(
    val status: String,
    val run_id: Long? = null,
    val sensor_rows: Int = 0,
    val accident_locations: Int = 0,
    val system_logs: Int = 0,
    val refresh_tokens: Int = 0,
    val rides_finalized: Int = 0,
    val image_files_deleted: Int = 0,
    val image_delete_failures: Int = 0,
    val errors: List<String> = emptyList()
)

data class MessageResponse(
    val message: String,
    val delivered: Boolean? = null
)

data class ProfileUpdateRequest(val nickname: String, val phone: String?)
data class AccountDeleteRequest(val password: String)
data class ConsentUpdateRequest(
    val ai_research_optional: Boolean,
    val long_term_optional: Boolean,
    val policy_version: String = "2026-07-23"
)
data class ConsentResponse(
    val terms_required: Boolean,
    val privacy_required: Boolean,
    val collection_required: Boolean,
    val ai_research_optional: Boolean,
    val long_term_optional: Boolean,
    val policy_version: String,
    val consented_at: String,
    val updated_at: String
)
data class ConsentHistoryResponse(
    val ai_research_optional: Boolean,
    val long_term_optional: Boolean,
    val policy_version: String,
    val changed_at: String
)

interface AuthApi {
    @POST("auth/signup")
    suspend fun signup(@Body request: SignupRequest): AuthTokenResponse

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): AuthTokenResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body request: RefreshRequest): AuthTokenResponse

    @POST("auth/logout")
    suspend fun logout(@Body request: LogoutRequest): MessageResponse

    @GET("auth/me")
    suspend fun me(): CurrentUserResponse

    @GET("hazard-zones/{zoneId}/feedback")
    suspend fun hazardZoneFeedback(
        @retrofit2.http.Path("zoneId") zoneId: String
    ): HazardFeedbackResponse

    @retrofit2.http.PUT("hazard-zones/{zoneId}/feedback")
    suspend fun updateHazardZoneFeedback(
        @retrofit2.http.Path("zoneId") zoneId: String,
        @Body request: HazardFeedbackRequest
    ): HazardFeedbackResponse

    @GET("auth/me/safety-contributions")
    suspend fun safetyContributions(): SafetyContributionResponse

    @GET("safety-contributions/weekly-ranking")
    suspend fun weeklySafetyRanking(): WeeklySafetyRankingResponse

    @retrofit2.http.PATCH("auth/me")
    suspend fun updateProfile(@Body request: ProfileUpdateRequest): MessageResponse

    @POST("auth/password/change")
    suspend fun changePassword(@Body request: PasswordChangeRequest): MessageResponse

    @POST("auth/password/reset")
    suspend fun resetPassword(@Body request: PasswordResetRequest): MessageResponse

    @GET("auth/consents")
    suspend fun consents(): ConsentResponse

    @retrofit2.http.PATCH("auth/consents")
    suspend fun updateConsents(@Body request: ConsentUpdateRequest): MessageResponse

    @GET("auth/consents/history")
    suspend fun consentHistory(): List<ConsentHistoryResponse>

    @GET("auth/data-summary")
    suspend fun dataSummary(): UserDataSummaryResponse

    @GET("admin/diagnostics")
    suspend fun adminDiagnostics(): AdminDiagnosticsResponse

    @GET("admin/dashboard")
    suspend fun adminDashboard(): AdminDashboardResponse

    @GET("admin/users")
    suspend fun adminUsers(
        @retrofit2.http.Query("q") query: String = "",
        @retrofit2.http.Query("status") status: String = "ALL",
        @retrofit2.http.Query("limit") limit: Int = 50,
        @retrofit2.http.Query("offset") offset: Int = 0
    ): AdminUserListResponse

    @GET("admin/users/{userId}")
    suspend fun adminUserDetail(
        @retrofit2.http.Path("userId") userId: Long
    ): AdminUserDetailResponse

    @GET("admin/rides")
    suspend fun adminRides(
        @retrofit2.http.Query("q") query: String = "",
        @retrofit2.http.Query("status") status: String = "ALL",
        @retrofit2.http.Query("limit") limit: Int = 50,
        @retrofit2.http.Query("offset") offset: Int = 0
    ): AdminRideListResponse

    @GET("admin/rides/{rideSessionId}")
    suspend fun adminRideDetail(
        @retrofit2.http.Path("rideSessionId") rideSessionId: String
    ): AdminRideDetailResponse

    @retrofit2.http.Streaming
    @GET("admin/sensor-logs/{eventUuid}/image")
    suspend fun adminSensorImage(
        @retrofit2.http.Path("eventUuid") eventUuid: String
    ): okhttp3.ResponseBody

    @GET("admin/hazard-zones")
    suspend fun adminHazardZones(
        @retrofit2.http.Query("q") query: String = "",
        @retrofit2.http.Query("status") status: String = "ALL",
        @retrofit2.http.Query("severity") severity: String = "ALL",
        @retrofit2.http.Query("zone_category") zoneCategory: String = "ALL",
        @retrofit2.http.Query("include_test_data") includeTestData: Boolean = true,
        @retrofit2.http.Query("limit") limit: Int = 500,
        @retrofit2.http.Query("offset") offset: Int = 0
    ): AdminHazardZoneListResponse

    @GET("admin/hazard-zones/{zoneId}")
    suspend fun adminHazardZoneDetail(
        @retrofit2.http.Path("zoneId") zoneId: String
    ): AdminHazardZoneDetailResponse

    @retrofit2.http.PATCH("admin/hazard-zones/{zoneId}/status")
    suspend fun updateAdminHazardZoneStatus(
        @retrofit2.http.Path("zoneId") zoneId: String,
        @Body request: HazardZoneStatusUpdateRequest
    ): HazardZoneStatusUpdateResponse

    @GET("admin/review-items")
    suspend fun adminReviewItems(
        @retrofit2.http.Query("status") status: String = "PENDING",
        @retrofit2.http.Query("limit") limit: Int = 100,
        @retrofit2.http.Query("offset") offset: Int = 0
    ): AdminReviewItemListResponse

    @GET("admin/review-items/{reviewItemId}")
    suspend fun adminReviewItemDetail(
        @retrofit2.http.Path("reviewItemId") reviewItemId: Long
    ): AdminReviewItemDetail

    @retrofit2.http.Streaming
    @retrofit2.http.GET("admin/review-items/{reviewItemId}/image")
    suspend fun adminReviewItemImage(
        @retrofit2.http.Path("reviewItemId") reviewItemId: Long
    ): okhttp3.ResponseBody

    @POST("admin/review-items/{reviewItemId}/register")
    suspend fun registerReviewItem(
        @retrofit2.http.Path("reviewItemId") reviewItemId: Long,
        @Body request: ReviewItemCandidateRequest
    ): ReviewItemActionResponse

    @POST("admin/review-items/{reviewItemId}/dismiss")
    suspend fun dismissReviewItem(
        @retrofit2.http.Path("reviewItemId") reviewItemId: Long
    ): ReviewItemActionResponse

    @GET("admin/audit-logs")
    suspend fun auditLogs(
        @retrofit2.http.Query("limit") limit: Int = 100,
        @retrofit2.http.Query("offset") offset: Int = 0
    ): List<AuditLogResponse>

    @GET("admin/sync/issues")
    suspend fun syncIssues(
        @retrofit2.http.Query("limit") limit: Int = 100
    ): SyncIssuesResponse

    @GET("admin/system-errors")
    suspend fun systemErrors(
        @retrofit2.http.Query("limit") limit: Int = 100
    ): List<SystemErrorResponse>

    @GET("admin/operations")
    suspend fun adminOperations(): AdminOperationsResponse

    @GET("admin/backups/status")
    suspend fun backupStatus(): BackupStatusResponse

    @POST("admin/backups/run")
    suspend fun runBackup(): BackupStatusResponse

    @GET("admin/retention/status")
    suspend fun retentionStatus(): RetentionStatusResponse

    @POST("admin/retention/run")
    suspend fun runRetention(): RetentionRunResponse

    @retrofit2.http.HTTP(method = "DELETE", path = "auth/me", hasBody = true)
    suspend fun deleteAccount(@Body request: AccountDeleteRequest): MessageResponse
}
