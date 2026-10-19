CREATE TABLE IF NOT EXISTS ai_classified_images (
    ai_classified_image_id BIGSERIAL PRIMARY KEY,
    ai_analysis_job_id BIGINT NOT NULL
        REFERENCES ai_analysis_jobs(ai_analysis_job_id) ON DELETE CASCADE,
    sensor_log_id BIGINT
        REFERENCES sensor_logs(sensor_log_id) ON DELETE CASCADE,
    event_uuid VARCHAR(36) NOT NULL,
    class_name VARCHAR(100) NOT NULL,
    source_image_path TEXT NOT NULL,
    classified_image_path TEXT NOT NULL UNIQUE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ai_classified_images_class_name_check CHECK (class_name <> ''),
    CONSTRAINT ai_classified_images_job_class_unique
        UNIQUE (ai_analysis_job_id, class_name)
);

CREATE INDEX IF NOT EXISTS index_ai_classified_images_class_created
    ON ai_classified_images(class_name, created_at DESC);

CREATE INDEX IF NOT EXISTS index_ai_classified_images_event
    ON ai_classified_images(event_uuid);

COMMENT ON TABLE ai_classified_images IS
    'AI 판독 원본의 클래스별 사본 경로. 탐지 없음은 class_name=etc로 저장';

