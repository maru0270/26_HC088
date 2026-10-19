import os


VALID_FEEDBACK_TYPES = {"DANGER", "CAUTION", "RESOLVED"}
CONFIRMATION_FEEDBACK_TYPES = {"DANGER", "CAUTION"}

BADGE_LEVELS = (
    ("도로 관찰자", int(os.getenv("BIKE_BADGE_OBSERVER_THRESHOLD", "5"))),
    ("안전 라이더", int(os.getenv("BIKE_BADGE_SAFE_RIDER_THRESHOLD", "20"))),
    ("BikeEyes 가디언", int(os.getenv("BIKE_BADGE_GUARDIAN_THRESHOLD", "50"))),
)


def contribution_type_for_feedback(feedback_type: str) -> str:
    if feedback_type in CONFIRMATION_FEEDBACK_TYPES:
        return "CONFIRMATION"
    if feedback_type == "RESOLVED":
        return "RESOLUTION"
    raise ValueError(f"unsupported feedback type: {feedback_type}")


def badge_progress(total: int) -> dict:
    current_name = None
    current_threshold = 0
    next_name = None
    next_threshold = None
    for name, threshold in BADGE_LEVELS:
        if total >= threshold:
            current_name = name
            current_threshold = threshold
        elif next_name is None:
            next_name = name
            next_threshold = threshold
    return {
        "current_badge": current_name,
        "current_threshold": current_threshold,
        "next_badge": next_name,
        "next_threshold": next_threshold,
        "remaining_to_next": max(0, (next_threshold or total) - total),
        "thresholds": [
            {"name": name, "threshold": threshold}
            for name, threshold in BADGE_LEVELS
        ],
    }


def award_establishment_contributions(cur, zone_id: str) -> int:
    """Award each distinct contributing user once for an AUTO confirmation."""
    cur.execute(
        """
        INSERT INTO user_safety_contributions (
            user_id, zone_id, contribution_type, created_at
        )
        SELECT DISTINCT
            s.user_id,
            z.zone_id,
            'ESTABLISHMENT',
            COALESCE(z.confirmed_at, timezone('Asia/Seoul', CURRENT_TIMESTAMP))
        FROM hazard_zones z
        JOIN hazard_zone_evidence e ON e.zone_id=z.zone_id
        JOIN sensor_logs s ON s.sensor_log_id=e.sensor_log_id
        WHERE z.zone_id=%s
          AND z.status='CONFIRMED'
          AND z.confirmation_source='AUTO'
          AND z.zone_category='USER_HAZARD'
          AND z.is_test_data=FALSE
          AND s.user_id IS NOT NULL
          AND (z.confirmed_at IS NULL OR e.linked_at<=z.confirmed_at)
        ON CONFLICT (user_id, zone_id, contribution_type) DO NOTHING
        """,
        (zone_id,),
    )
    return cur.rowcount
