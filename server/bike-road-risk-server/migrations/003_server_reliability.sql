ALTER TABLE system_logs ADD COLUMN IF NOT EXISTS request_id VARCHAR(100);
ALTER TABLE system_logs ADD COLUMN IF NOT EXISTS level VARCHAR(20) NOT NULL DEFAULT 'INFO';
ALTER TABLE system_logs ADD COLUMN IF NOT EXISTS endpoint VARCHAR(255);
ALTER TABLE system_logs ADD COLUMN IF NOT EXISTS status_code INTEGER;
ALTER TABLE system_logs ADD COLUMN IF NOT EXISTS details JSONB NOT NULL DEFAULT '{}'::jsonb;

CREATE INDEX IF NOT EXISTS index_system_logs_request_id
    ON system_logs(request_id) WHERE request_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS index_system_logs_level_created_at
    ON system_logs(level, created_at DESC);
CREATE INDEX IF NOT EXISTS index_sensor_logs_ride_session
    ON sensor_logs(ride_session_id);
