-- BikeEyes application/server contract alignment.
-- Safe to apply to existing databases; constraints are NOT VALID so legacy rows
-- remain available while all new writes are checked.

CREATE INDEX IF NOT EXISTS index_sensor_logs_ride_captured
    ON sensor_logs (ride_session_id, captured_at);
CREATE INDEX IF NOT EXISTS index_sensor_logs_captured_at
    ON sensor_logs (captured_at);
CREATE INDEX IF NOT EXISTS index_sensor_logs_device_captured
    ON sensor_logs (device_id, captured_at DESC);
CREATE INDEX IF NOT EXISTS index_accident_events_device_detected
    ON accident_events (device_id, detected_at DESC);
CREATE INDEX IF NOT EXISTS index_rides_device_ended
    ON rides (device_id, ended_at DESC);
CREATE INDEX IF NOT EXISTS index_rides_pending_deletion
    ON rides (deletion_due_at)
    WHERE deletion_due_at IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'users_role_check') THEN
        ALTER TABLE users ADD CONSTRAINT users_role_check
            CHECK (role IN ('USER', 'ADMIN')) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'rides_nonnegative_metrics_check') THEN
        ALTER TABLE rides ADD CONSTRAINT rides_nonnegative_metrics_check
            CHECK (
                ended_at >= started_at AND elapsed_seconds >= 0
                AND total_distance_meters >= 0 AND average_speed_kmh >= 0
                AND max_speed_kmh >= 0 AND collected_frame_count >= 0
                AND accident_count >= 0
            ) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'sensor_logs_accuracy_check') THEN
        ALTER TABLE sensor_logs ADD CONSTRAINT sensor_logs_accuracy_check
            CHECK (accuracy IS NULL OR accuracy >= 0) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'accident_confirmation_check') THEN
        ALTER TABLE accident_events ADD CONSTRAINT accident_confirmation_check
            CHECK (confirmation_status IN ('ACCIDENT', 'NO_RESPONSE', 'SAFE')) NOT VALID;
    END IF;
END
$$;
