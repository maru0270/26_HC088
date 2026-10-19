ALTER TABLE hazard_zones DROP CONSTRAINT IF EXISTS hazard_zones_status_check;

ALTER TABLE hazard_zones
    ADD COLUMN IF NOT EXISTS risk_score DOUBLE PRECISION NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS distinct_ride_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS distinct_user_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS review_required_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS stale_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMP WITHOUT TIME ZONE;

ALTER TABLE hazard_zones
    ADD CONSTRAINT hazard_zones_status_check CHECK (
        status IN ('CANDIDATE', 'REVIEW_REQUIRED', 'CONFIRMED', 'STALE', 'ARCHIVED', 'REJECTED')
    );

ALTER TABLE hazard_zones DROP CONSTRAINT IF EXISTS hazard_zones_risk_score_check;
ALTER TABLE hazard_zones
    ADD CONSTRAINT hazard_zones_risk_score_check CHECK (risk_score BETWEEN 0 AND 100);

CREATE INDEX IF NOT EXISTS index_hazard_zones_review_queue
    ON hazard_zones(status, risk_score DESC, updated_at DESC)
    WHERE status IN ('CANDIDATE', 'REVIEW_REQUIRED');

CREATE INDEX IF NOT EXISTS index_hazard_zones_lifecycle
    ON hazard_zones(status, last_detected_at);
