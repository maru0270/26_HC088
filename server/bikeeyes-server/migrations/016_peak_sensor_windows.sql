-- Image-linked sensor windows now use the strongest post-capture sample as
-- the representative sensor set. Existing rows remain valid with NULL.
ALTER TABLE sensor_logs
    ADD COLUMN IF NOT EXISTS peak_sample_at BIGINT;

CREATE INDEX IF NOT EXISTS index_sensor_logs_peak_sample_at
    ON sensor_logs (peak_sample_at);

COMMENT ON COLUMN sensor_logs.peak_sample_at IS
    'Timestamp (ms) of the strongest impact sample in the 3-second window after captured_at';
