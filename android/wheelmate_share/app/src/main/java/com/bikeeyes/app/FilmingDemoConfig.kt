package com.bikeeyes.app

/**
 * 간담회 촬영 전용 설정입니다.
 * 촬영이 끝나면 [enabled]만 false로 바꾸면 실제 GPS 기반 동작으로 복귀합니다.
 */
object FilmingDemoConfig {
    const val enabled = false
    const val alertDelayMs = 10_000L
    const val alertDistanceMeters = 15f

    // 촬영 시작 직후에는 정지 상태를 보여주고, 2초 시점에 실제 주행 속도에 도달합니다.
    const val targetSpeedKmh = 13.2f
    private const val startDelayMs = 500L
    private const val accelerationDurationMs = 1_500L

    fun speedMetersPerSecondAt(elapsedMs: Long): Float {
        val targetMetersPerSecond = targetSpeedKmh / 3.6f
        val progress = (
            (elapsedMs - startDelayMs).toFloat() /
                accelerationDurationMs.toFloat()
            ).coerceIn(0f, 1f)
        return targetMetersPerSecond * progress
    }

    fun displaySpeedKmhAt(elapsedMs: Long): Float {
        val baseSpeed = speedMetersPerSecondAt(elapsedMs) * 3.6f
        return if (elapsedMs >= startDelayMs + accelerationDurationMs) {
            targetSpeedKmh +
                (kotlin.math.sin(elapsedMs / 850.0) * 0.2).toFloat()
        } else {
            baseSpeed
        }
    }

    fun distanceMetersAt(elapsedMs: Long): Double {
        val targetMetersPerSecond = targetSpeedKmh / 3.6
        val movingSeconds =
            (elapsedMs - startDelayMs).coerceAtLeast(0L) / 1_000.0
        val accelerationSeconds = accelerationDurationMs / 1_000.0

        return if (movingSeconds <= accelerationSeconds) {
            val acceleration = targetMetersPerSecond / accelerationSeconds
            0.5 * acceleration * movingSeconds * movingSeconds
        } else {
            val accelerationDistance =
                0.5 * targetMetersPerSecond * accelerationSeconds
            val constantSpeedSeconds = movingSeconds - accelerationSeconds
            accelerationDistance + targetMetersPerSecond * constantSpeedSeconds
        }
    }

    /**
     * 사천시 장애인 종합복지관 앞(사천시 용현면 진삼로 443 인근) 촬영용 마커입니다.
     * 같은 지점에 두 핀이 겹쳐 가려지지 않도록 도로 중심 좌우로 약 5m만 분리했습니다.
     * 실제 위험구간/사고 데이터나 서버 DB에는 저장되지 않습니다.
     */
    val hazardMarker = FilmingRoutePoint(34.99631586, 128.060442)
    val accidentMarker = FilmingRoutePoint(34.99631586, 128.060442)

    val routePoints = listOf(
        FilmingRoutePoint(34.9968056, 128.0604871), // 사천시 장애인 종합복지관
        FilmingRoutePoint(34.9954200, 128.0606100),
        FilmingRoutePoint(34.9935400, 128.0609100),
        FilmingRoutePoint(34.9919328, 128.0612572),
        FilmingRoutePoint(34.9895200, 128.0619300),
        FilmingRoutePoint(34.9866100, 128.0626500),
        FilmingRoutePoint(34.9835800, 128.0634400),
        FilmingRoutePoint(34.9804200, 128.0641600),
        FilmingRoutePoint(34.9776094, 128.0639566),
        FilmingRoutePoint(34.9753416, 128.0656565),
        FilmingRoutePoint(34.9739033, 128.0655213),
        FilmingRoutePoint(34.9723662, 128.0664153),
        FilmingRoutePoint(34.9711503, 128.0672010),
        FilmingRoutePoint(34.9701190, 128.0683937)  // 탑서리
    )
}

data class FilmingRoutePoint(
    val latitude: Double,
    val longitude: Double
)
