package com.bikeeyes.app

import android.content.Context
import retrofit2.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthRepository(context: Context) {
    private val appContext = context.applicationContext
    private val session = AuthSessionStore(appContext)
    private val settings = UserSettingsStore(appContext)
    private val api get() = NetworkModule.dynamicAuthApi(appContext)

    suspend fun signup(
        nickname: String,
        email: String,
        phone: String,
        password: String,
        aiResearch: Boolean,
        longTermStorage: Boolean
    ) {
        val response = api.signup(
            SignupRequest(
                email = email.trim().lowercase(),
                nickname = nickname.trim(),
                phone = phone.trim().ifBlank { null },
                password = password,
                device_id = session.deviceId,
                device_name = session.deviceName,
                terms_required = true,
                privacy_required = true,
                collection_required = true,
                ai_research_optional = aiResearch,
                long_term_optional = longTermStorage
            )
        )
        session.save(response)
        settings.saveProfile(UserProfile(nickname.trim(), email.trim().lowercase(), phone.trim()))
    }

    suspend fun login(email: String, password: String) {
        val response = api.login(
            LoginRequest(
                email = email.trim().lowercase(),
                password = password,
                device_id = session.deviceId,
                device_name = session.deviceName
            )
        )
        session.save(response)
        val current = api.me()
        settings.saveProfile(
            UserProfile(current.nickname, current.email, current.phone.orEmpty(), current.role)
        )
        RideServerSyncRepository(appContext).sync()
    }

    suspend fun restoreSession(): Boolean {
        if (!session.hasSession()) return false
        if (session.refreshTokenExpired()) {
            session.clear()
            return false
        }
        return try {
            if (session.accessTokenNeedsRefresh()) refresh()
            val current = api.me()
            settings.saveProfile(
                UserProfile(current.nickname, current.email, current.phone.orEmpty(), current.role)
            )
            RideServerSyncRepository(appContext).sync()
            true
        } catch (error: Throwable) {
            if (isDefinitiveSessionRejection(error)) {
                session.clear()
                false
            } else {
                // 네트워크 단절·타임아웃·서버 장애는 세션 만료가 아니다.
                // 저장된 사용자 정보와 Room 데이터를 이용해 오프라인으로 계속 사용한다.
                true
            }
        }
    }

    suspend fun refresh() {
        val refreshToken = session.refreshToken() ?: error("로그인 정보가 없습니다.")
        val response = api.refresh(RefreshRequest(refreshToken, session.deviceId))
        session.save(response)
    }

    suspend fun logout() {
        val token = session.refreshToken()
        try {
            // Server-side revocation is best-effort. A user must still be able to
            // sign out locally when the development server or network is unavailable.
            if (token != null) runCatching { api.logout(LogoutRequest(token)) }
        } finally {
            session.clear()
        }
    }

    suspend fun changePassword(current: String, new: String) {
        api.changePassword(PasswordChangeRequest(current, new))
        session.clear()
    }

    suspend fun updateProfile(nickname: String, phone: String) {
        api.updateProfile(ProfileUpdateRequest(nickname.trim(), phone.trim().ifBlank { null }))
        val existing = settings.loadProfile()
        settings.saveProfile(existing.copy(nickname = nickname.trim(), phone = phone.trim()))
    }

    suspend fun deleteAccount(password: String) {
        api.deleteAccount(AccountDeleteRequest(password))
        session.clear()
        settings.clearProfile()
        withContext(Dispatchers.IO) {
            BikeEyesLocalDatabase.getInstance(appContext).clearAllTables()
        }
    }

    suspend fun resetPassword(email: String): Boolean =
        api.resetPassword(PasswordResetRequest(email.trim().lowercase())).delivered == true

    suspend fun consents(): ConsentResponse = api.consents()

    suspend fun updateConsents(aiResearch: Boolean, longTerm: Boolean) {
        api.updateConsents(ConsentUpdateRequest(aiResearch, longTerm))
    }

    suspend fun consentHistory(): List<ConsentHistoryResponse> = api.consentHistory()

    suspend fun dataSummary(): UserDataSummaryResponse = api.dataSummary()

    suspend fun hazardZoneFeedback(zoneId: String): HazardFeedbackResponse =
        api.hazardZoneFeedback(zoneId)

    suspend fun updateHazardZoneFeedback(
        zoneId: String,
        feedbackType: String
    ): HazardFeedbackResponse =
        api.updateHazardZoneFeedback(zoneId, HazardFeedbackRequest(feedbackType))

    suspend fun safetyContributions(): SafetyContributionResponse =
        api.safetyContributions()

    suspend fun weeklySafetyRanking(): WeeklySafetyRankingResponse =
        api.weeklySafetyRanking()

    suspend fun adminDiagnostics(): AdminDiagnosticsResponse = api.adminDiagnostics()

    suspend fun adminDashboard(): AdminDashboardResponse = api.adminDashboard()

    suspend fun adminUsers(
        query: String = "",
        status: String = "ALL"
    ): AdminUserListResponse = api.adminUsers(query = query, status = status)

    suspend fun adminUserDetail(userId: Long): AdminUserDetailResponse =
        api.adminUserDetail(userId)

    suspend fun adminRides(
        query: String = "",
        status: String = "ALL",
        year: Int? = null,
        month: Int? = null,
        day: Int? = null
    ): AdminRideListResponse = api.adminRides(
        query = query,
        status = status,
        year = year,
        month = month,
        day = day
    )

    suspend fun adminAccidents(
        query: String = "",
        responseStatus: String = "ALL",
        year: Int? = null,
        month: Int? = null,
        day: Int? = null,
        limit: Int = 50,
        offset: Int = 0
    ): AdminAccidentListResponse = api.adminAccidents(
        query = query,
        responseStatus = responseStatus,
        year = year,
        month = month,
        day = day,
        limit = limit,
        offset = offset
    )

    suspend fun adminRideDetail(rideSessionId: String): AdminRideDetailResponse =
        api.adminRideDetail(rideSessionId)

    suspend fun adminSensorImage(eventUuid: String): ByteArray =
        withContext(Dispatchers.IO) { api.adminSensorImage(eventUuid).bytes() }

    suspend fun adminHazardZones(
        query: String = "",
        status: String = "ALL",
        severity: String = "ALL",
        zoneCategory: String = "ALL",
        damageType: String = "ALL",
        year: Int? = null,
        month: Int? = null,
        day: Int? = null,
        includeTestData: Boolean = true,
        limit: Int = 500,
        offset: Int = 0
    ): AdminHazardZoneListResponse =
        api.adminHazardZones(
            query,
            status,
            severity,
            zoneCategory,
            damageType,
            year,
            month,
            day,
            includeTestData,
            limit,
            offset
        )

    suspend fun adminHazardZoneDetail(zoneId: String): AdminHazardZoneDetailResponse =
        api.adminHazardZoneDetail(zoneId)

    suspend fun updateAdminHazardZoneStatus(
        zoneId: String,
        status: String
    ): HazardZoneStatusUpdateResponse =
        api.updateAdminHazardZoneStatus(zoneId, HazardZoneStatusUpdateRequest(status))

    suspend fun adminReviewItems(
        status: String = "PENDING",
        year: Int? = null,
        month: Int? = null,
        day: Int? = null,
        limit: Int = 100,
        offset: Int = 0
    ): AdminReviewItemListResponse = api.adminReviewItems(status, year, month, day, limit, offset)

    suspend fun adminReviewItemDetail(reviewItemId: Long): AdminReviewItemDetail =
        api.adminReviewItemDetail(reviewItemId)

    suspend fun adminReviewItemImage(reviewItemId: Long): ByteArray =
        withContext(Dispatchers.IO) {
            api.adminReviewItemImage(reviewItemId).use { response -> response.bytes() }
        }

    suspend fun registerReviewItem(
        reviewItemId: Long,
        classes: List<String>
    ): ReviewItemActionResponse =
        api.registerReviewItem(reviewItemId, ReviewItemCandidateRequest(classes))

    suspend fun dismissReviewItem(reviewItemId: Long): ReviewItemActionResponse =
        api.dismissReviewItem(reviewItemId)

    suspend fun auditLogs(year: Int? = null, month: Int? = null, day: Int? = null): List<AuditLogResponse> =
        api.auditLogs(year, month, day)

    suspend fun syncIssues(year: Int? = null, month: Int? = null, day: Int? = null): SyncIssuesResponse =
        api.syncIssues(year, month, day)

    suspend fun systemErrors(year: Int? = null, month: Int? = null, day: Int? = null): List<SystemErrorResponse> =
        api.systemErrors(year, month, day)

    suspend fun adminOperations(): AdminOperationsResponse = api.adminOperations()

    suspend fun backupStatus(): BackupStatusResponse = api.backupStatus()

    suspend fun runBackup(): BackupStatusResponse = api.runBackup()

    suspend fun retentionStatus(): RetentionStatusResponse = api.retentionStatus()

    suspend fun runRetention(): RetentionRunResponse = api.runRetention()

    companion object {
        fun isDefinitiveSessionRejection(error: Throwable): Boolean =
            error is HttpException && error.code() in setOf(400, 401, 403)

        fun userMessage(error: Throwable): String {
            if (error is HttpException) {
                return when (error.code()) {
                    401 -> "이메일 또는 비밀번호가 올바르지 않습니다."
                    409 -> "이미 가입된 이메일입니다."
                    422 -> "입력 조건을 확인해주세요."
                    503 -> "이메일 발송 설정이 아직 준비되지 않았습니다. 서버 관리자에게 문의해주세요."
                    else -> "서버 오류가 발생했습니다. (${error.code()})"
                }
            }
            return "서버에 연결할 수 없습니다. 서버 주소와 네트워크를 확인해주세요."
        }

        fun isValidPassword(value: String): Boolean =
            value.length >= 8 && value.any { !it.isLetterOrDigit() && !it.isWhitespace() }
    }
}
