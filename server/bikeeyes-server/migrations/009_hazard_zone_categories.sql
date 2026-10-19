ALTER TABLE hazard_zones DROP CONSTRAINT IF EXISTS hazard_zones_status_check;

ALTER TABLE hazard_zones
    ADD COLUMN IF NOT EXISTS zone_category VARCHAR(30) NOT NULL DEFAULT 'ROAD_ANOMALY',
    ADD COLUMN IF NOT EXISTS is_user_visible BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS confirmation_source VARCHAR(20),
    ADD COLUMN IF NOT EXISTS confirmation_cancelled_at TIMESTAMP WITHOUT TIME ZONE;

ALTER TABLE hazard_zones DROP CONSTRAINT IF EXISTS hazard_zones_category_check;
ALTER TABLE hazard_zones
    ADD CONSTRAINT hazard_zones_category_check CHECK (
        zone_category IN ('ROAD_ANOMALY', 'USER_HAZARD')
    );

ALTER TABLE hazard_zones DROP CONSTRAINT IF EXISTS hazard_zones_status_check;
UPDATE hazard_zones
SET zone_category='ROAD_ANOMALY', is_user_visible=FALSE,
    status=CASE WHEN status='REVIEW_REQUIRED' THEN 'CANDIDATE' ELSE status END;

ALTER TABLE hazard_zones
    ADD CONSTRAINT hazard_zones_status_check CHECK (
        status IN ('CANDIDATE', 'CONFIRMED', 'STALE', 'ARCHIVED', 'EXPIRED', 'REJECTED')
    );

ALTER TABLE hazard_zones DROP CONSTRAINT IF EXISTS hazard_zones_confirmation_source_check;
ALTER TABLE hazard_zones
    ADD CONSTRAINT hazard_zones_confirmation_source_check CHECK (
        confirmation_source IS NULL OR confirmation_source IN ('AUTO', 'MANUAL')
    );

UPDATE hazard_zones
SET status='CONFIRMED',confirmation_source='AUTO',
    confirmed_at=COALESCE(confirmed_at,CURRENT_TIMESTAMP)
WHERE zone_category='ROAD_ANOMALY'
  AND status='CANDIDATE'
  AND distinct_ride_count>=3 AND confidence>=0.60;

-- Preserve qualifying legacy rider hazards without duplicating raw sensor or AI rows.
-- The deterministic UUID keeps the migration idempotent.
WITH eligible_zones AS (
    SELECT DISTINCT z.*,
        (
            substr(md5(z.zone_id::text || ':USER_HAZARD'),1,8) || '-' ||
            substr(md5(z.zone_id::text || ':USER_HAZARD'),9,4) || '-' ||
            substr(md5(z.zone_id::text || ':USER_HAZARD'),13,4) || '-' ||
            substr(md5(z.zone_id::text || ':USER_HAZARD'),17,4) || '-' ||
            substr(md5(z.zone_id::text || ':USER_HAZARD'),21,12)
        )::uuid AS user_hazard_zone_id
    FROM hazard_zones z
    WHERE z.zone_category='ROAD_ANOMALY'
      AND (
          z.road_damage_type='bollard'
          OR EXISTS (
              SELECT 1
              FROM hazard_zone_evidence e
              JOIN sensor_logs s ON s.sensor_log_id=e.sensor_log_id
              WHERE e.zone_id=z.zone_id
                AND COALESCE((e.details->>'sensor_support')::boolean,FALSE)
                AND COALESCE(s.speed,0) >= 1.5
          )
      )
)
INSERT INTO hazard_zones (
    zone_id,title,road_damage_type,severity,latitude,longitude,radius_meters,
    detection_count,description,first_detected_at,last_detected_at,source_model,
    confidence,status,is_test_data,updated_at,risk_score,distinct_ride_count,
    distinct_user_count,confirmed_at,stale_at,archived_at,zone_category,
    is_user_visible,expires_at,confirmation_source
)
SELECT
    user_hazard_zone_id,title,road_damage_type,severity,latitude,longitude,radius_meters,
    detection_count,description,first_detected_at,last_detected_at,source_model,
    confidence,'CANDIDATE',
    is_test_data,updated_at,risk_score,0,0,NULL,
    stale_at,archived_at,'USER_HAZARD',
    FALSE,
    CASE WHEN road_damage_type='flood_area' THEN last_detected_at + INTERVAL '24 hours' END,
    NULL
FROM eligible_zones
ON CONFLICT (zone_id) DO NOTHING;

WITH zone_pairs AS (
    SELECT z.zone_id AS road_zone_id,
        (
            substr(md5(z.zone_id::text || ':USER_HAZARD'),1,8) || '-' ||
            substr(md5(z.zone_id::text || ':USER_HAZARD'),9,4) || '-' ||
            substr(md5(z.zone_id::text || ':USER_HAZARD'),13,4) || '-' ||
            substr(md5(z.zone_id::text || ':USER_HAZARD'),17,4) || '-' ||
            substr(md5(z.zone_id::text || ':USER_HAZARD'),21,12)
        )::uuid AS user_zone_id,
        z.road_damage_type
    FROM hazard_zones z
    WHERE z.zone_category='ROAD_ANOMALY'
)
INSERT INTO hazard_zone_evidence (
    zone_id,sensor_log_id,event_uuid,source_model,model_version,confidence,
    distance_meters,linked_at,details,ai_detection_id
)
SELECT
    p.user_zone_id,e.sensor_log_id,e.event_uuid,e.source_model,e.model_version,
    e.confidence,e.distance_meters,e.linked_at,e.details,e.ai_detection_id
FROM zone_pairs p
JOIN hazard_zones target ON target.zone_id=p.user_zone_id
JOIN hazard_zone_evidence e ON e.zone_id=p.road_zone_id
LEFT JOIN sensor_logs s ON s.sensor_log_id=e.sensor_log_id
WHERE p.road_damage_type='bollard'
   OR (
       COALESCE((e.details->>'sensor_support')::boolean,FALSE)
       AND COALESCE(s.speed,0) >= 1.5
   )
ON CONFLICT DO NOTHING;

WITH stats AS (
    SELECT e.zone_id,COUNT(*) AS evidence_count,
           COUNT(DISTINCT s.ride_session_id) AS ride_count,
           COUNT(DISTINCT s.user_id) AS user_count,
           AVG(e.confidence) AS average_confidence,
           AVG(s.latitude) AS latitude,AVG(s.longitude) AS longitude
    FROM hazard_zone_evidence e
    JOIN hazard_zones z ON z.zone_id=e.zone_id
    JOIN sensor_logs s ON s.sensor_log_id=e.sensor_log_id
    WHERE z.zone_category='USER_HAZARD'
      AND (z.road_damage_type<>'flood_area'
           OR e.linked_at>=CURRENT_TIMESTAMP-INTERVAL '24 hours')
    GROUP BY e.zone_id
)
UPDATE hazard_zones z SET
    detection_count=GREATEST(stats.evidence_count,1),
    distinct_ride_count=stats.ride_count,
    distinct_user_count=stats.user_count,
    confidence=stats.average_confidence,
    latitude=stats.latitude,longitude=stats.longitude,
    status=CASE WHEN stats.ride_count>=3 AND stats.average_confidence>=0.60
                THEN 'CONFIRMED' ELSE 'CANDIDATE' END,
    is_user_visible=CASE WHEN stats.ride_count>=3 AND stats.average_confidence>=0.60
                         THEN TRUE ELSE FALSE END,
    confirmation_source=CASE WHEN stats.ride_count>=3 AND stats.average_confidence>=0.60
                             THEN 'AUTO' END,
    confirmed_at=CASE WHEN stats.ride_count>=3 AND stats.average_confidence>=0.60
                      THEN CURRENT_TIMESTAMP END
FROM stats WHERE z.zone_id=stats.zone_id;

CREATE INDEX IF NOT EXISTS index_hazard_zones_category_status_location
    ON hazard_zones(zone_category,status,latitude,longitude);

CREATE INDEX IF NOT EXISTS index_hazard_zones_user_visible
    ON hazard_zones(last_detected_at DESC)
    WHERE zone_category='USER_HAZARD' AND status='CONFIRMED' AND is_user_visible=TRUE;

CREATE INDEX IF NOT EXISTS index_hazard_zones_expiry
    ON hazard_zones(expires_at)
    WHERE expires_at IS NOT NULL AND status IN ('CANDIDATE','CONFIRMED');
