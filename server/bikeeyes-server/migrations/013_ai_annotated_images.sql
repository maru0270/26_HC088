ALTER TABLE ai_analysis_jobs
    ADD COLUMN IF NOT EXISTS annotated_image_path TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS index_ai_analysis_jobs_annotated_path
    ON ai_analysis_jobs(annotated_image_path)
    WHERE annotated_image_path IS NOT NULL;

CREATE TABLE IF NOT EXISTS ai_detection_class_results (
    ai_detection_class_result_id BIGSERIAL PRIMARY KEY,
    ai_analysis_job_id BIGINT NOT NULL
        REFERENCES ai_analysis_jobs(ai_analysis_job_id) ON DELETE CASCADE,
    stage VARCHAR(20) NOT NULL,
    class_name VARCHAR(100) NOT NULL,
    detected BOOLEAN NOT NULL,
    detection_count INTEGER NOT NULL DEFAULT 0,
    max_confidence DOUBLE PRECISION,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ai_detection_class_results_stage_check
        CHECK (stage IN ('BBOX', 'SEGMENTATION')),
    CONSTRAINT ai_detection_class_results_count_check
        CHECK (detection_count >= 0),
    CONSTRAINT ai_detection_class_results_confidence_check
        CHECK (max_confidence IS NULL OR max_confidence BETWEEN 0 AND 1),
    CONSTRAINT ai_detection_class_results_job_stage_class_unique
        UNIQUE (ai_analysis_job_id, stage, class_name)
);

CREATE INDEX IF NOT EXISTS index_ai_detection_class_results_job
    ON ai_detection_class_results(ai_analysis_job_id, stage);

COMMENT ON COLUMN ai_analysis_jobs.annotated_image_path IS
    '탐지 도형만 합성한 단일 판독 이미지. 클래스명과 신뢰도는 조회 시 권한에 따라 동적으로 표시한다.';

