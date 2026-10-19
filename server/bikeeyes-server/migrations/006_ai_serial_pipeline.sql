CREATE TABLE IF NOT EXISTS ai_analysis_jobs (
    ai_analysis_job_id BIGSERIAL PRIMARY KEY,
    sensor_log_id BIGINT REFERENCES sensor_logs(sensor_log_id) ON DELETE CASCADE,
    event_uuid VARCHAR(36) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    image_sha256 CHAR(64),
    total_duration_ms INTEGER,
    stage_summary JSONB NOT NULL DEFAULT '[]'::jsonb,
    error_message TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITHOUT TIME ZONE,
    completed_at TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT ai_analysis_jobs_status_check
        CHECK (status IN ('WAITING', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'SKIPPED')),
    CONSTRAINT ai_analysis_jobs_duration_check
        CHECK (total_duration_ms IS NULL OR total_duration_ms >= 0)
);

CREATE INDEX IF NOT EXISTS index_ai_analysis_jobs_status_created
    ON ai_analysis_jobs(status, created_at DESC);

CREATE TABLE IF NOT EXISTS ai_detections (
    ai_detection_id BIGSERIAL PRIMARY KEY,
    ai_analysis_job_id BIGINT NOT NULL
        REFERENCES ai_analysis_jobs(ai_analysis_job_id) ON DELETE CASCADE,
    stage VARCHAR(20) NOT NULL,
    model_version VARCHAR(100) NOT NULL,
    class_name VARCHAR(100) NOT NULL,
    class_id INTEGER NOT NULL,
    confidence DOUBLE PRECISION NOT NULL,
    bbox_xyxy JSONB NOT NULL,
    polygon JSONB,
    raw_result JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ai_detections_stage_check CHECK (stage IN ('BBOX', 'SEGMENTATION')),
    CONSTRAINT ai_detections_confidence_check CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ai_detections_class_id_check CHECK (class_id >= 0)
);

CREATE INDEX IF NOT EXISTS index_ai_detections_job_stage
    ON ai_detections(ai_analysis_job_id, stage);

CREATE INDEX IF NOT EXISTS index_ai_detections_class_confidence
    ON ai_detections(class_name, confidence DESC);
