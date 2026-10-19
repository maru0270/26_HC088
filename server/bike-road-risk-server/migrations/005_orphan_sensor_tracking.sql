-- Track sensor collections whose ride summary has not arrived yet.
-- Raw sensor rows remain untouched and continue to follow the one-year policy.

CREATE TABLE IF NOT EXISTS orphan_sensor_sessions (
    ride_session_id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    device_id VARCHAR(100),
    first_captured_at BIGINT NOT NULL,
    last_captured_at BIGINT NOT NULL,
    sensor_count INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    grace_due_at BIGINT NOT NULL,
    detected_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT orphan_sensor_sessions_status_check
        CHECK (status IN ('WAITING', 'ORPHAN', 'RESOLVED')),
    CONSTRAINT orphan_sensor_sessions_count_check CHECK (sensor_count > 0),
    CONSTRAINT orphan_sensor_sessions_time_check
        CHECK (last_captured_at >= first_captured_at)
);

CREATE INDEX IF NOT EXISTS index_orphan_sensor_sessions_status_due
    ON orphan_sensor_sessions (status, grace_due_at);

INSERT INTO orphan_sensor_sessions (
    ride_session_id, user_id, device_id,
    first_captured_at, last_captured_at, sensor_count,
    status, grace_due_at
)
SELECT
    s.ride_session_id,
    max(s.user_id),
    max(s.device_id),
    min(s.captured_at),
    max(s.captured_at),
    count(*)::integer,
    CASE
        WHEN max(s.captured_at) <=
             (extract(epoch FROM CURRENT_TIMESTAMP - INTERVAL '24 hours') * 1000)::bigint
        THEN 'ORPHAN'
        ELSE 'WAITING'
    END,
    max(s.captured_at) + 86400000
FROM sensor_logs s
WHERE s.ride_session_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM rides r
      WHERE r.ride_session_id = s.ride_session_id
  )
GROUP BY s.ride_session_id
ON CONFLICT (ride_session_id) DO NOTHING;

