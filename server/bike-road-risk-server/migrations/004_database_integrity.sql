-- Strengthen the live schema without discarding legacy collection data.
-- Legacy sensor rows may not have event_uuid, so that column intentionally
-- remains nullable. New API writes always provide an idempotency UUID.

UPDATE sensor_logs
SET sensor_samples = '[]'::jsonb
WHERE sensor_samples IS NULL;

ALTER TABLE sensor_logs
    ALTER COLUMN accel_x SET NOT NULL,
    ALTER COLUMN accel_y SET NOT NULL,
    ALTER COLUMN accel_z SET NOT NULL,
    ALTER COLUMN gyro_x SET NOT NULL,
    ALTER COLUMN gyro_y SET NOT NULL,
    ALTER COLUMN gyro_z SET NOT NULL,
    ALTER COLUMN geomagnetic_x SET NOT NULL,
    ALTER COLUMN geomagnetic_y SET NOT NULL,
    ALTER COLUMN geomagnetic_z SET NOT NULL,
    ALTER COLUMN sensor_samples SET DEFAULT '[]'::jsonb,
    ALTER COLUMN sensor_samples SET NOT NULL,
    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP,
    ALTER COLUMN created_at SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'rides_user_device_fkey'
    ) THEN
        ALTER TABLE rides
            ADD CONSTRAINT rides_user_device_fkey
            FOREIGN KEY (user_id, device_id)
            REFERENCES devices(user_id, device_id)
            ON DELETE SET NULL
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'sensor_logs_user_device_fkey'
    ) THEN
        ALTER TABLE sensor_logs
            ADD CONSTRAINT sensor_logs_user_device_fkey
            FOREIGN KEY (user_id, device_id)
            REFERENCES devices(user_id, device_id)
            ON DELETE SET NULL
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'accident_events_user_device_fkey'
    ) THEN
        ALTER TABLE accident_events
            ADD CONSTRAINT accident_events_user_device_fkey
            FOREIGN KEY (user_id, device_id)
            REFERENCES devices(user_id, device_id)
            ON DELETE SET NULL
            NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'sensor_logs_location_pair_check'
    ) THEN
        ALTER TABLE sensor_logs
            ADD CONSTRAINT sensor_logs_location_pair_check
            CHECK (
                (latitude IS NULL AND longitude IS NULL)
                OR
                (
                    latitude BETWEEN -90 AND 90
                    AND longitude BETWEEN -180 AND 180
                )
            ) NOT VALID;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'sensor_logs_nonnegative_motion_check'
    ) THEN
        ALTER TABLE sensor_logs
            ADD CONSTRAINT sensor_logs_nonnegative_motion_check
            CHECK (
                (accuracy IS NULL OR accuracy >= 0)
                AND (speed IS NULL OR speed >= 0)
            ) NOT VALID;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS index_sensor_logs_unmatched_ride
    ON sensor_logs (ride_session_id, captured_at)
    WHERE ride_session_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS hazard_zone_evidence (
    hazard_zone_evidence_id BIGSERIAL PRIMARY KEY,
    zone_id UUID NOT NULL REFERENCES hazard_zones(zone_id) ON DELETE CASCADE,
    sensor_log_id BIGINT REFERENCES sensor_logs(sensor_log_id) ON DELETE SET NULL,
    event_uuid VARCHAR(36),
    source_model VARCHAR(100),
    model_version VARCHAR(100),
    confidence DOUBLE PRECISION,
    distance_meters DOUBLE PRECISION,
    linked_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT hazard_zone_evidence_confidence_check
        CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    CONSTRAINT hazard_zone_evidence_distance_check
        CHECK (distance_meters IS NULL OR distance_meters >= 0),
    CONSTRAINT hazard_zone_evidence_source_check
        CHECK (sensor_log_id IS NOT NULL OR event_uuid IS NOT NULL)
);

CREATE UNIQUE INDEX IF NOT EXISTS index_hazard_zone_evidence_zone_sensor
    ON hazard_zone_evidence (zone_id, sensor_log_id)
    WHERE sensor_log_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS index_hazard_zone_evidence_event
    ON hazard_zone_evidence (event_uuid)
    WHERE event_uuid IS NOT NULL;

