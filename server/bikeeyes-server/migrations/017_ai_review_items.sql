CREATE TABLE IF NOT EXISTS ai_review_items (
    review_item_id BIGSERIAL PRIMARY KEY,
    sensor_log_id BIGINT REFERENCES sensor_logs(sensor_log_id) ON DELETE SET NULL,
    event_uuid VARCHAR(36),
    ride_session_id VARCHAR(36),
    image_path TEXT,
    captured_at BIGINT NOT NULL,
    peak_sample_at BIGINT,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    peak_impact_delta DOUBLE PRECISION NOT NULL,
    speed_median DOUBLE PRECISION NOT NULL,
    sensor_samples JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    selected_classes JSONB NOT NULL DEFAULT '[]'::jsonb,
    candidate_zone_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    no_issue_delete_after BIGINT,
    reviewed_by BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    reviewed_at TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ai_review_items_status_check
        CHECK (status IN ('PENDING', 'CANDIDATE_REGISTERED', 'NO_ISSUE')),
    CONSTRAINT ai_review_items_impact_check
        CHECK (peak_impact_delta >= 0),
    CONSTRAINT ai_review_items_speed_check
        CHECK (speed_median >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS index_ai_review_items_sensor
    ON ai_review_items(sensor_log_id)
    WHERE sensor_log_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS index_ai_review_items_event
    ON ai_review_items(event_uuid)
    WHERE event_uuid IS NOT NULL;

CREATE INDEX IF NOT EXISTS index_ai_review_items_status_created
    ON ai_review_items(status, created_at DESC);

CREATE INDEX IF NOT EXISTS index_ai_review_items_expiry
    ON ai_review_items(captured_at)
    WHERE status = 'PENDING';

COMMENT ON TABLE ai_review_items IS
    'AI 미탐지이면서 센서 충격 기준을 넘은 관리자 전용 재검토 항목. 보류 이미지는 촬영 시각 기준 원본 보관기간(365일)을 따른다.';

COMMENT ON COLUMN ai_review_items.selected_classes IS
    '관리자가 선택한 하나 이상의 위험요소 클래스 JSON 배열';

COMMENT ON COLUMN ai_review_items.no_issue_delete_after IS
    '해당 사항 없음 처리 시각부터 2분 후의 이미지 삭제 예정 시각';
