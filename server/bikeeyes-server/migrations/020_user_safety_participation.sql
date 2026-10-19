CREATE TABLE IF NOT EXISTS hazard_zone_feedback (
    feedback_id BIGSERIAL PRIMARY KEY,
    zone_id UUID NOT NULL REFERENCES hazard_zones(zone_id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    feedback_type VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
        DEFAULT timezone('Asia/Seoul', CURRENT_TIMESTAMP),
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
        DEFAULT timezone('Asia/Seoul', CURRENT_TIMESTAMP),
    CONSTRAINT hazard_zone_feedback_type_check
        CHECK (feedback_type IN ('DANGER', 'CAUTION', 'RESOLVED')),
    CONSTRAINT hazard_zone_feedback_user_zone_unique UNIQUE (user_id, zone_id)
);

CREATE INDEX IF NOT EXISTS index_hazard_zone_feedback_zone_updated
    ON hazard_zone_feedback(zone_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS index_hazard_zone_feedback_user_updated
    ON hazard_zone_feedback(user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS user_safety_contributions (
    contribution_id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    zone_id UUID NOT NULL REFERENCES hazard_zones(zone_id) ON DELETE CASCADE,
    contribution_type VARCHAR(30) NOT NULL,
    source_feedback_id BIGINT REFERENCES hazard_zone_feedback(feedback_id) ON DELETE SET NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL
        DEFAULT timezone('Asia/Seoul', CURRENT_TIMESTAMP),
    CONSTRAINT user_safety_contribution_type_check
        CHECK (contribution_type IN ('CONFIRMATION', 'ESTABLISHMENT', 'RESOLUTION')),
    CONSTRAINT user_safety_contribution_once_unique
        UNIQUE (user_id, zone_id, contribution_type)
);

CREATE INDEX IF NOT EXISTS index_user_safety_contributions_user_created
    ON user_safety_contributions(user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS index_user_safety_contributions_weekly
    ON user_safety_contributions(created_at DESC, user_id);

-- Backfill only automatically confirmed real USER_HAZARD zones. Evidence linked
-- after the original confirmation moment is intentionally excluded.
INSERT INTO user_safety_contributions (
    user_id, zone_id, contribution_type, created_at
)
SELECT DISTINCT
    s.user_id,
    z.zone_id,
    'ESTABLISHMENT',
    COALESCE(z.confirmed_at, z.updated_at)
FROM hazard_zones z
JOIN hazard_zone_evidence e ON e.zone_id = z.zone_id
JOIN sensor_logs s ON s.sensor_log_id = e.sensor_log_id
JOIN users u ON u.user_id = s.user_id
WHERE z.status = 'CONFIRMED'
  AND z.confirmation_source = 'AUTO'
  AND z.zone_category = 'USER_HAZARD'
  AND z.is_test_data = FALSE
  AND s.user_id IS NOT NULL
  AND (z.confirmed_at IS NULL OR e.linked_at <= z.confirmed_at)
ON CONFLICT (user_id, zone_id, contribution_type) DO NOTHING;

COMMENT ON TABLE hazard_zone_feedback IS
    '확정 USER_HAZARD에 대한 사용자별 현재 현장 의견. AI 근거 및 confidence와 분리한다.';

COMMENT ON TABLE user_safety_contributions IS
    '사용자 안전지도 기여 이력. 사용자-위험구간-기여유형별 최대 1건으로 중복 집계를 막는다.';
