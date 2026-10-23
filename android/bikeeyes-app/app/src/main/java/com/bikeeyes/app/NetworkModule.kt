package com.bikeeyes.app

import android.content.Context
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NetworkModule {
    private val plainClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile private var cachedBaseUrl: String? = null
    @Volatile private var cachedApi: SensorApi? = null
    @Volatile private var cachedAuthApi: AuthApi? = null

    fun dynamicSensorApi(context: Context): SensorApi =
        DynamicSensorApi(context.applicationContext)

    fun dynamicAuthApi(context: Context): AuthApi =
        DynamicAuthApi(context.applicationContext)

    fun onServerAddressChanged() {
        synchronized(this) {
            cachedBaseUrl = null
            cachedApi = null
            cachedAuthApi = null
        }
        SensorUploadRepository.resetNetworkBackoff()
    }

    private fun currentApi(context: Context): SensorApi {
        val baseUrl = UserSettingsStore(context).loadServerBaseUrl()
        val existing = cachedApi
        if (existing != null && cachedBaseUrl == baseUrl) return existing
        return synchronized(this) {
            if (cachedApi == null || cachedBaseUrl != baseUrl) {
                val authenticatedClient = plainClient.newBuilder()
                    .addInterceptor { chain ->
                        val token = AuthSessionStore(context).accessToken()
                        val requestBuilder = chain.request().newBuilder()
                            // 무료 ngrok 주소에서는 이 헤더가 없으면 API JSON 대신 안내 HTML을 반환한다.
                            .header("ngrok-skip-browser-warning", "1")
                        if (!token.isNullOrBlank()) {
                            requestBuilder.header("Authorization", "Bearer $token")
                        }
                        chain.proceed(requestBuilder.build())
                    }
                    .build()
                cachedApi = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(authenticatedClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(SensorApi::class.java)
                cachedAuthApi = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(authenticatedClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(AuthApi::class.java)
                cachedBaseUrl = baseUrl
            }
            cachedApi!!
        }
    }

    private fun currentAuthApi(context: Context): AuthApi {
        currentApi(context)
        return cachedAuthApi!!
    }

    suspend fun testConnection(inputUrl: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = UserSettingsStore.normalizeServerBaseUrl(inputUrl)
            val request = Request.Builder()
                .url(baseUrl + "docs")
                .header("ngrok-skip-browser-warning", "1")
                .get()
                .build()
            val startedAt = System.currentTimeMillis()
            plainClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
            }
            System.currentTimeMillis() - startedAt
        }
    }

    private class DynamicSensorApi(
        private val context: Context
    ) : SensorApi {
        override suspend fun downloadSensorImage(eventUuid: String): okhttp3.ResponseBody =
            currentApi(context).downloadSensorImage(eventUuid)

        override suspend fun downloadHazardZoneRepresentativeImage(zoneId: String): okhttp3.ResponseBody =
            currentApi(context).downloadHazardZoneRepresentativeImage(zoneId)

        override suspend fun getAccidentImages(eventUuid: String): AccidentImageListResponse =
            currentApi(context).getAccidentImages(eventUuid)

        override suspend fun getHazardZones(
            includeCandidates: Boolean,
            includeTestData: Boolean
        ): HazardZoneListResponse =
            currentApi(context).getHazardZones(includeCandidates, includeTestData)

        override suspend fun getCurrentLocationName(
            longitude: Double,
            latitude: Double
        ): KakaoCurrentLocationResponse =
            currentApi(context).getCurrentLocationName(longitude, latitude)

        override suspend fun searchPlaces(
            query: String,
            longitude: Double?,
            latitude: Double?,
            size: Int
        ): KakaoPlaceSearchResponse =
            currentApi(context).searchPlaces(query, longitude, latitude, size)

        override suspend fun getBicycleRoute(
            startLongitude: Double,
            startLatitude: Double,
            endLongitude: Double,
            endLatitude: Double,
            destinationName: String,
            routeMode: String
        ): BicycleRouteResponse =
            currentApi(context).getBicycleRoute(
                startLongitude,
                startLatitude,
                endLongitude,
                endLatitude,
                destinationName,
                routeMode
            )

        override suspend fun getWalkingBikeRoute(
            startLongitude: Double,
            startLatitude: Double,
            endLongitude: Double,
            endLatitude: Double,
            destinationName: String,
            routeMode: String
        ): BicycleRouteResponse =
            currentApi(context).getWalkingBikeRoute(
                startLongitude,
                startLatitude,
                endLongitude,
                endLatitude,
                destinationName,
                routeMode
            )

        override suspend fun uploadImage(
            file: MultipartBody.Part,
            eventUuid: RequestBody
        ): ImageUploadResponse = currentApi(context).uploadImage(file, eventUuid)

        override suspend fun postSensorLog(request: SensorLogRequest): SensorLogResponse =
            currentApi(context).postSensorLog(request)

        override suspend fun postAccidentEvent(request: AccidentEventRequest): SensorLogResponse =
            currentApi(context).postAccidentEvent(request)

        override suspend fun postRideResult(request: RideResultRequest): RideResultResponse =
            currentApi(context).postRideResult(request)

        override suspend fun deleteRide(rideSessionId: String): RideDeleteResponse =
            currentApi(context).deleteRide(rideSessionId)
        override suspend fun getDeletedRides() = currentApi(context).getDeletedRides()
        override suspend fun restoreRide(rideSessionId: String) =
            currentApi(context).restoreRide(rideSessionId)
        override suspend fun permanentlyDeleteRide(rideSessionId: String) =
            currentApi(context).permanentlyDeleteRide(rideSessionId)
        override suspend fun getRides(limit: Int, offset: Int) =
            currentApi(context).getRides(limit, offset)
        override suspend fun getRide(rideSessionId: String) =
            currentApi(context).getRide(rideSessionId)
        override suspend fun getRideSensorLogs(
            rideSessionId: String,
            limit: Int,
            offset: Int,
            imagesOnly: Boolean
        ) = currentApi(context).getRideSensorLogs(rideSessionId, limit, offset, imagesOnly)
    }

    private class DynamicAuthApi(private val context: Context) : AuthApi {
        override suspend fun signup(request: SignupRequest) = currentAuthApi(context).signup(request)
        override suspend fun login(request: LoginRequest) = currentAuthApi(context).login(request)
        override suspend fun refresh(request: RefreshRequest) = currentAuthApi(context).refresh(request)
        override suspend fun logout(request: LogoutRequest) = currentAuthApi(context).logout(request)
        override suspend fun me() = currentAuthApi(context).me()
        override suspend fun hazardZoneFeedback(zoneId: String) =
            currentAuthApi(context).hazardZoneFeedback(zoneId)
        override suspend fun updateHazardZoneFeedback(
            zoneId: String,
            request: HazardFeedbackRequest
        ) = currentAuthApi(context).updateHazardZoneFeedback(zoneId, request)
        override suspend fun safetyContributions() =
            currentAuthApi(context).safetyContributions()
        override suspend fun weeklySafetyRanking() =
            currentAuthApi(context).weeklySafetyRanking()
        override suspend fun updateProfile(request: ProfileUpdateRequest) =
            currentAuthApi(context).updateProfile(request)
        override suspend fun changePassword(request: PasswordChangeRequest) =
            currentAuthApi(context).changePassword(request)
        override suspend fun resetPassword(request: PasswordResetRequest) =
            currentAuthApi(context).resetPassword(request)
        override suspend fun consents() = currentAuthApi(context).consents()
        override suspend fun updateConsents(request: ConsentUpdateRequest) =
            currentAuthApi(context).updateConsents(request)
        override suspend fun consentHistory() = currentAuthApi(context).consentHistory()
        override suspend fun dataSummary() = currentAuthApi(context).dataSummary()
        override suspend fun adminDiagnostics() = currentAuthApi(context).adminDiagnostics()
        override suspend fun adminDashboard() = currentAuthApi(context).adminDashboard()
        override suspend fun adminUsers(
            query: String,
            status: String,
            limit: Int,
            offset: Int
        ) = currentAuthApi(context).adminUsers(query, status, limit, offset)
        override suspend fun adminUserDetail(userId: Long) =
            currentAuthApi(context).adminUserDetail(userId)
        override suspend fun adminRides(
            query: String,
            status: String,
            year: Int?,
            month: Int?,
            day: Int?,
            limit: Int,
            offset: Int
        ) = currentAuthApi(context).adminRides(query, status, year, month, day, limit, offset)
        override suspend fun adminAccidents(
            query: String,
            responseStatus: String,
            year: Int?,
            month: Int?,
            day: Int?,
            limit: Int,
            offset: Int
        ) = currentAuthApi(context).adminAccidents(
            query, responseStatus, year, month, day, limit, offset
        )
        override suspend fun adminRideDetail(rideSessionId: String) =
            currentAuthApi(context).adminRideDetail(rideSessionId)
        override suspend fun adminSensorImage(eventUuid: String) =
            currentAuthApi(context).adminSensorImage(eventUuid)
        override suspend fun adminHazardZones(
            query: String,
            status: String,
            severity: String,
            zoneCategory: String,
            damageType: String,
            year: Int?,
            month: Int?,
            day: Int?,
            includeTestData: Boolean,
            limit: Int,
            offset: Int
        ) = currentAuthApi(context).adminHazardZones(
            query, status, severity, zoneCategory, damageType, year, month, day, includeTestData, limit, offset
        )
        override suspend fun adminHazardZoneDetail(zoneId: String) =
            currentAuthApi(context).adminHazardZoneDetail(zoneId)
        override suspend fun updateAdminHazardZoneStatus(
            zoneId: String,
            request: HazardZoneStatusUpdateRequest
        ) = currentAuthApi(context).updateAdminHazardZoneStatus(zoneId, request)
        override suspend fun adminReviewItems(status: String, year: Int?, month: Int?, day: Int?, limit: Int, offset: Int) =
            currentAuthApi(context).adminReviewItems(status, year, month, day, limit, offset)
        override suspend fun adminReviewItemDetail(reviewItemId: Long) =
            currentAuthApi(context).adminReviewItemDetail(reviewItemId)
        override suspend fun adminReviewItemImage(reviewItemId: Long) =
            currentAuthApi(context).adminReviewItemImage(reviewItemId)
        override suspend fun registerReviewItem(
            reviewItemId: Long,
            request: ReviewItemCandidateRequest
        ) = currentAuthApi(context).registerReviewItem(reviewItemId, request)
        override suspend fun dismissReviewItem(reviewItemId: Long) =
            currentAuthApi(context).dismissReviewItem(reviewItemId)
        override suspend fun auditLogs(year: Int?, month: Int?, day: Int?, limit: Int, offset: Int) =
            currentAuthApi(context).auditLogs(year, month, day, limit, offset)
        override suspend fun syncIssues(year: Int?, month: Int?, day: Int?, limit: Int) =
            currentAuthApi(context).syncIssues(year, month, day, limit)
        override suspend fun systemErrors(year: Int?, month: Int?, day: Int?, limit: Int) =
            currentAuthApi(context).systemErrors(year, month, day, limit)
        override suspend fun adminOperations() =
            currentAuthApi(context).adminOperations()
        override suspend fun backupStatus() =
            currentAuthApi(context).backupStatus()
        override suspend fun runBackup() =
            currentAuthApi(context).runBackup()
        override suspend fun retentionStatus() = currentAuthApi(context).retentionStatus()
        override suspend fun runRetention() = currentAuthApi(context).runRetention()
        override suspend fun deleteAccount(request: AccountDeleteRequest) =
            currentAuthApi(context).deleteAccount(request)
    }
}
