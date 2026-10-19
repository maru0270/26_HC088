CREATE TABLE IF NOT EXISTS retention_runs (
    retention_run_id BIGSERIAL PRIMARY KEY,
    trigger_type VARCHAR(20) NOT NULL,
    requested_by BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITHOUT TIME ZONE,
    result JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS index_retention_runs_started
    ON retention_runs (started_at DESC);

CREATE TABLE IF NOT EXISTS file_deletion_queue (
    relative_path TEXT PRIMARY KEY,
    reason VARCHAR(50) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS index_file_deletion_queue_retry
    ON file_deletion_queue (next_retry_at);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'retention_runs_status_check') THEN
        ALTER TABLE retention_runs ADD CONSTRAINT retention_runs_status_check
            CHECK (status IN ('RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'SKIPPED')) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'retention_runs_trigger_check') THEN
        ALTER TABLE retention_runs ADD CONSTRAINT retention_runs_trigger_check
            CHECK (trigger_type IN ('STARTUP', 'SCHEDULED', 'MANUAL')) NOT VALID;
    END IF;
END
$$;
