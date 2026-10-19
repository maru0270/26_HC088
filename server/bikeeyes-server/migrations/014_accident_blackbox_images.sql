ALTER TABLE sensor_logs
    ADD COLUMN IF NOT EXISTS image_delete_after BIGINT,
    ADD COLUMN IF NOT EXISTS image_retention_reason VARCHAR(32) NOT NULL DEFAULT 'STANDARD';

CREATE INDEX IF NOT EXISTS index_sensor_logs_image_delete_after
    ON sensor_logs(image_delete_after)
    WHERE image_delete_after IS NOT NULL AND image_path IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'sensor_logs_image_retention_reason_check'
    ) THEN
        ALTER TABLE sensor_logs
            ADD CONSTRAINT sensor_logs_image_retention_reason_check
            CHECK (image_retention_reason IN (
                'STANDARD', 'AI_NO_DETECTION', 'ACCIDENT_BLACKBOX'
            )) NOT VALID;
    END IF;
END
$$;

CREATE TABLE IF NOT EXISTS accident_image_evidence (
    accident_image_evidence_id BIGSERIAL PRIMARY KEY,
    accident_event_id BIGINT NOT NULL
        REFERENCES accident_events(accident_event_id) ON DELETE CASCADE,
    sensor_log_id BIGINT NOT NULL
        REFERENCES sensor_logs(sensor_log_id) ON DELETE CASCADE,
    captured_at BIGINT NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT accident_image_evidence_unique
        UNIQUE (accident_event_id, sensor_log_id)
);

CREATE INDEX IF NOT EXISTS index_accident_image_evidence_accident_time
    ON accident_image_evidence(accident_event_id, captured_at);

CREATE INDEX IF NOT EXISTS index_accident_image_evidence_sensor
    ON accident_image_evidence(sensor_log_id);

COMMENT ON TABLE accident_image_evidence IS
    '확정 사고 또는 무응답 사고 시점 직전 90초 동안 보존하는 블랙박스 이미지 연결';

COMMENT ON COLUMN sensor_logs.image_delete_after IS
    'AI 탐지 없음 이미지의 삭제 예정 시각(epoch milliseconds). 사고 근거 연결 시 NULL';
