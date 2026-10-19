package com.bikeeyes.app

/**
 * 2026-06-27 주행 데이터의 최장 안정 장착 구간(약 27.1초)에서 산정한 임시값.
 * 팀 검토와 실제 거치대 시험 후 이 값만 교체할 수 있도록 분리한다.
 */
object CameraMountAngleConfig {
    const val TARGET_PITCH_DEGREES = 1.0
    const val TARGET_ROLL_DEGREES = 59.0
    const val PITCH_TOLERANCE_DEGREES = 10.0
    const val ROLL_TOLERANCE_DEGREES = 12.0
    const val MAX_STABLE_GYRO_RAD_PER_SEC = 0.35
    const val REQUIRED_STABLE_DURATION_MS = 1_500L
}
