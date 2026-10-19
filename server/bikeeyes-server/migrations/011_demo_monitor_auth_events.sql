ALTER TABLE demo_monitor_events
    DROP CONSTRAINT IF EXISTS demo_monitor_category_check;

ALTER TABLE demo_monitor_events
    ADD CONSTRAINT demo_monitor_category_check CHECK (
        category IN (
            'AUTH','RIDE','GPS','SENSOR','IMAGE','AI_BBOX','AI_SEG',
            'ROAD_ANOMALY','HAZARD','ACCIDENT','SYNC','SYSTEM'
        )
    );

