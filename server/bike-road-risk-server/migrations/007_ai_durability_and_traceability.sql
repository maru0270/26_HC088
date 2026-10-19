ALTER TABLE ai_analysis_jobs
    ADD COLUMN IF NOT EXISTS image_path TEXT,
    ADD COLUMN IF NOT EXISTS source_width INTEGER,
    ADD COLUMN IF NOT EXISTS source_height INTEGER,
    ADD COLUMN IF NOT EXISTS config_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ai_analysis_jobs_source_size_check'
    ) THEN
        ALTER TABLE ai_analysis_jobs
            ADD CONSTRAINT ai_analysis_jobs_source_size_check CHECK (
                (source_width IS NULL AND source_height IS NULL)
                OR (source_width > 0 AND source_height > 0)
            );
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ai_analysis_jobs_attempt_count_check'
    ) THEN
        ALTER TABLE ai_analysis_jobs
            ADD CONSTRAINT ai_analysis_jobs_attempt_count_check CHECK (attempt_count >= 0);
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS index_ai_analysis_jobs_waiting
    ON ai_analysis_jobs(created_at, ai_analysis_job_id)
    WHERE status='WAITING';

ALTER TABLE hazard_zone_evidence
    ADD COLUMN IF NOT EXISTS ai_detection_id BIGINT
        REFERENCES ai_detections(ai_detection_id) ON DELETE SET NULL;

CREATE UNIQUE INDEX IF NOT EXISTS index_hazard_zone_evidence_zone_ai_detection
    ON hazard_zone_evidence(zone_id, ai_detection_id)
    WHERE ai_detection_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS index_hazard_zone_evidence_ai_detection
    ON hazard_zone_evidence(ai_detection_id)
    WHERE ai_detection_id IS NOT NULL;
