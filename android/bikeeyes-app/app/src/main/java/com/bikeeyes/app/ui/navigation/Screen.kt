package com.bikeeyes.app.ui.navigation

sealed class Screen(
    val route: String,
    val title: String
) {
    data object Login : Screen("login", "로그인")
    data object PasswordReset : Screen("password_reset", "비밀번호 찾기")

    data object Signup : Screen("signup", "회원가입")
    data object SignupComplete : Screen("signup_complete", "회원가입 완료")
    data object Permission : Screen("permission", "권한 요청")
    data object Home : Screen("home", "홈")

    data object CameraGuide : Screen("camera_guide", "카메라 방향 안내")
    data object Route : Screen("route", "경로")

    data object RouteSearchResult : Screen("route_search_result","경로 검색 결과")
    data object Ride : Screen("ride", "주행")
    data object RideSummary : Screen("ride_summary", "주행 종료 요약")
    data object RideDetail : Screen("ride_detail/{sessionId}", "주행 기록 상세") {
        fun createRoute(sessionId: String) = "ride_detail/$sessionId"
    }

    data object History : Screen("history", "기록")
    data object Danger : Screen("danger", "위험지도")
    data object RiskZoneDetail : Screen("risk_zone_detail/{zoneId}", "위험구간 상세") {
        fun createRoute(zoneId: String) = "risk_zone_detail/$zoneId"
    }
    data object RoadRecordDetail : Screen("road_record_detail/{recordId}", "노면 기록 상세") {
        fun createRoute(recordId: Long) = "road_record_detail/$recordId"
    }
    data object ServerRoadRecordDetail : Screen(
        "server_road_record_detail/{sessionId}/{serverRecordKey}",
        "노면 기록 상세"
    ) {
        fun createRoute(sessionId: String, serverRecordKey: String) =
            "server_road_record_detail/$sessionId/$serverRecordKey"
    }
    data object RideRoadRecords : Screen("ride_road_records/{sessionId}", "주행 노면 기록") {
        fun createRoute(sessionId: String) = "ride_road_records/$sessionId"
    }
    data object AccidentDetail : Screen("accident_detail/{eventUuid}", "사고 기록 상세") {
        fun createRoute(eventUuid: String) = "accident_detail/$eventUuid"
    }
    data object RideAccidents : Screen("ride_accidents/{sessionId}", "주행 사고 기록") {
        fun createRoute(sessionId: String) = "ride_accidents/$sessionId"
    }
    data object MyPage : Screen("mypage", "마이")
    data object NotificationSettings : Screen("notification_settings", "알림 설정")
    data object ConsentSettings : Screen("consent_settings", "개인정보 동의 관리")
    data object AdminDiagnostics : Screen("admin_diagnostics", "관리자 진단")
    data object AdminUsers : Screen("admin_users", "관리자 사용자 관리")
    data object AdminSystem : Screen("admin_system", "관리자 시스템 진단")
    data object AdminRecords : Screen("admin_records", "관리자 기록 관리")
    data object AdminRides : Screen("admin_rides", "관리자 주행 기록")
    data object AdminRideDetail : Screen("admin_ride_detail/{sessionId}", "관리자 주행 상세") {
        fun createRoute(sessionId: String) = "admin_ride_detail/$sessionId"
    }
    data object AdminRoadRecords : Screen("admin_road_records", "관리자 위험 감지 기록")
    data object AdminAccidents : Screen("admin_accidents", "관리자 사고 기록")
    data object AdminAccidentDetail : Screen(
        "admin_accident_detail/{sessionId}/{responseFilter}?eventUuid={eventUuid}", "사고 감지 기록 상세"
    ) {
        fun createRoute(
            sessionId: String,
            responseFilter: String = "ALL",
            eventUuid: String? = null
        ): String {
            val baseRoute = "admin_accident_detail/$sessionId/$responseFilter"
            return if (eventUuid.isNullOrBlank()) baseRoute else "$baseRoute?eventUuid=$eventUuid"
        }
    }
    data object AdminRoadAnomalyZones : Screen("admin_road_anomaly_zones", "관리자 노면이상 구간 관리")
    data object AdminHazardZones : Screen("admin_hazard_zones", "관리자 위험구간 관리")
    data object AdminReviewItems : Screen("admin_review_items", "재검토 항목 관리")
    data object ServerSettings : Screen("server_settings", "서버 주소 설정")
    data object AccountEdit : Screen("account_edit", "계정 정보 수정")
    data object PasswordChange : Screen("password_change", "비밀번호 변경")
    data object AccountDelete : Screen("account_delete", "회원 탈퇴")
}
