-- 014 적용 전에 저장된 확정/무응답 사고에도 직전 90초 이미지를 연결한다.
-- SAFE(괜찮아요) 및 오탐 기록은 기존 정책대로 블랙박스 보존 대상에서 제외한다.
INSERT INTO accident_image_evidence (
    accident_event_id,
    sensor_log_id,
    captured_at
)
SELECT
    a.accident_event_id,
    s.sensor_log_id,
    s.captured_at
FROM accident_events a
JOIN sensor_logs s
  ON s.ride_session_id = a.ride_session_id
 AND s.captured_at BETWEEN a.detected_at - 90000 AND a.detected_at
 AND s.image_path IS NOT NULL
 AND (a.user_id IS NULL OR s.user_id = a.user_id)
WHERE a.ride_session_id IS NOT NULL
  AND a.confirmation_status IN ('ACCIDENT', 'NO_RESPONSE')
  AND a.is_false_positive = FALSE
ON CONFLICT (accident_event_id, sensor_log_id) DO NOTHING;

-- 연결된 이미지는 일반 AI 미탐지 이미지 정리 작업에서 삭제되지 않도록 보호한다.
UPDATE sensor_logs s
SET image_delete_after = NULL,
    image_retention_reason = 'ACCIDENT_BLACKBOX'
WHERE EXISTS (
    SELECT 1
    FROM accident_image_evidence e
    WHERE e.sensor_log_id = s.sensor_log_id
);

