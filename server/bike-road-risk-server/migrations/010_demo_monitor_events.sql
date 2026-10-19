CREATE TABLE IF NOT EXISTS demo_monitor_events (
    demo_event_id BIGSERIAL PRIMARY KEY,
    ride_session_id VARCHAR(36),
    event_uuid VARCHAR(36),
    user_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    device_id VARCHAR(100),
    category VARCHAR(30) NOT NULL,
    event_type VARCHAR(60) NOT NULL,
    message VARCHAR(500) NOT NULL,
    status VARCHAR(30) NOT NULL,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_visible BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT demo_monitor_category_check CHECK (
        category IN ('RIDE','GPS','SENSOR','IMAGE','AI_BBOX','AI_SEG','ROAD_ANOMALY','HAZARD','ACCIDENT','SYNC','SYSTEM')
    )
);

CREATE INDEX IF NOT EXISTS index_demo_monitor_events_created
    ON demo_monitor_events(created_at DESC, demo_event_id DESC);

CREATE INDEX IF NOT EXISTS index_demo_monitor_events_ride
    ON demo_monitor_events(ride_session_id, created_at DESC)
    WHERE ride_session_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS index_demo_monitor_events_visible
    ON demo_monitor_events(created_at DESC)
    WHERE is_visible=TRUE;

COMMENT ON TABLE demo_monitor_events IS
    '간담회 시연 화면용 구조화 이벤트. 개발·오류 로그를 대체하지 않는다.';
