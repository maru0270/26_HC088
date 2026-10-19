from __future__ import annotations

import math
import os
import statistics
import uuid

from psycopg2.extras import Json

from app.user_safety import award_establishment_contributions


# Temporary operating values derived primarily from the 2026-06-27 ride.
# Keep these together so the controlled bicycle impact test can tune them later.
EARTH_GRAVITY = 9.80665
GPS_PRIMARY_ACCURACY_M = 15.0
GPS_MAX_EVIDENCE_ACCURACY_M = 25.0
AI_SENSOR_SUPPORT_DELTA = 14.0
SENSOR_ONLY_DELTA = 18.5
VERY_STRONG_IMPACT_DELTA = 30.0
SENSOR_EVIDENCE_MIN_SPEED_MPS = 1.5
IMAGE_SENSOR_WINDOW_MS = 1_000
IMPACT_SPEED_WINDOW_MS = 1_000
SAME_RIDE_DEDUPE_SECONDS = 10
SAME_RIDE_DEDUPE_DISTANCE_M = 10.0
POINT_HAZARD_RADIUS_M = 10.0
AREA_HAZARD_RADIUS_M = 20.0
MIN_DISTINCT_RIDES = int(os.getenv("BIKE_HAZARD_MIN_DISTINCT_RIDES", "3"))
MIN_DISTINCT_USERS = int(os.getenv("BIKE_HAZARD_MIN_DISTINCT_USERS", "2"))
REQUIRE_DISTINCT_USERS = os.getenv("BIKE_HAZARD_REQUIRE_DISTINCT_USERS", "false").lower() == "true"
HAZARD_STALE_DAYS = int(os.getenv("BIKE_HAZARD_STALE_DAYS", "90"))
HAZARD_ARCHIVE_DAYS = int(os.getenv("BIKE_HAZARD_ARCHIVE_DAYS", "180"))
SAME_CLASS_IOU_THRESHOLD = 0.50
ROAD_ANOMALY = "ROAD_ANOMALY"
USER_HAZARD = "USER_HAZARD"
FLOOD_VALID_HOURS = 24

CLASS_RULES = {
    "pothole": ("pothole", 0.50, POINT_HAZARD_RADIUS_M, "포트홀"),
    "manhole": ("manhole", 0.55, POINT_HAZARD_RADIUS_M, "맨홀"),
    "flood_area": ("flood_area", 0.50, AREA_HAZARD_RADIUS_M, "물웅덩이"),
    "bollard": ("bollard", 0.55, POINT_HAZARD_RADIUS_M, "볼라드"),
    "linear": ("crack", 0.40, AREA_HAZARD_RADIUS_M, "선형 균열"),
    "alligator": ("crack", 0.40, AREA_HAZARD_RADIUS_M, "거북등 균열"),
}

CLASS_RISK_BONUS = {
    "pothole": 15.0,
    "bollard": 10.0,
    "flood_area": 8.0,
    "crack": 8.0,
    "road_impact": 5.0,
    "manhole": 0.0,
}


def acceleration_delta(x: float, y: float, z: float) -> float:
    return abs(math.sqrt(x * x + y * y + z * z) - EARTH_GRAVITY)


def max_sensor_delta(sensor_samples, accel_x: float, accel_y: float, accel_z: float) -> float:
    values = [acceleration_delta(accel_x, accel_y, accel_z)]
    for sample in sensor_samples or []:
        try:
            values.append(
                acceleration_delta(
                    float(sample["accel_x"]),
                    float(sample["accel_y"]),
                    float(sample["accel_z"]),
                )
            )
        except (KeyError, TypeError, ValueError):
            continue
    return max(values)


def max_sensor_speed(sensor_samples, speed: float | None) -> float:
    values = [float(speed or 0.0)]
    for sample in sensor_samples or []:
        try:
            values.append(float(sample.get("speed") or 0.0))
        except (AttributeError, TypeError, ValueError):
            continue
    return max(values)


def _sampled_at_ms(sample) -> int | None:
    try:
        value = sample.get("sampled_at")
        return int(value) if value is not None else None
    except (AttributeError, TypeError, ValueError):
        return None


def image_matched_impact(
    sensor_samples,
    captured_at: int,
    accel_x: float,
    accel_y: float,
    accel_z: float,
    peak_sample_at: int | None = None,
) -> tuple[float, int]:
    """Return the strongest impact in the post-capture image sensor window.

    New bundles contain only samples captured after the image and optionally
    carry ``peak_sample_at``. Legacy rows are supported with a top-level
    snapshot fallback, but no pre-capture sample is considered a match.
    """
    captured_at = int(captured_at)
    window_end = captured_at + IMAGE_SENSOR_WINDOW_MS
    candidates = []
    for sample in sensor_samples or []:
        sampled_at = _sampled_at_ms(sample)
        if sampled_at is None or not (captured_at < sampled_at <= window_end):
            continue
        try:
            candidates.append(
                (
                    acceleration_delta(
                        float(sample["accel_x"]),
                        float(sample["accel_y"]),
                        float(sample["accel_z"]),
                    ),
                    sampled_at,
                )
            )
        except (KeyError, TypeError, ValueError):
            continue
    if candidates:
        return max(candidates, key=lambda candidate: candidate[0])

    if peak_sample_at is not None:
        return (
            acceleration_delta(accel_x, accel_y, accel_z),
            int(peak_sample_at),
        )

    # Compatibility path for historical sparse rows that predate peak metadata.
    return acceleration_delta(accel_x, accel_y, accel_z), captured_at


def median_speed_around_impact(
    sensor_samples,
    impact_at: int,
    captured_at: int,
    speed: float | None,
) -> float:
    """Return the median GPS speed measured one second before/after impact."""
    values: list[float] = []
    if abs(int(captured_at) - int(impact_at)) <= IMPACT_SPEED_WINDOW_MS:
        try:
            top_level_speed = float(speed)
            if top_level_speed >= 0.0:
                values.append(top_level_speed)
        except (TypeError, ValueError):
            pass
    for sample in sensor_samples or []:
        sampled_at = _sampled_at_ms(sample)
        if sampled_at is None:
            continue
        # Speed evidence must come from this image's own post-capture sample
        # window; a sample assigned to an adjacent image must not be reused.
        if not (int(captured_at) < sampled_at <= int(captured_at) + IMAGE_SENSOR_WINDOW_MS):
            continue
        if abs(sampled_at - int(impact_at)) > IMPACT_SPEED_WINDOW_MS:
            continue
        try:
            sample_speed = float(sample["speed"])
            if sample_speed >= 0.0:
                values.append(sample_speed)
        except (KeyError, TypeError, ValueError):
            continue
    return float(statistics.median(values)) if values else 0.0


def qualifies_for_user_hazard(
    class_name: str,
    impact_delta: float,
    impact_speed: float,
) -> bool:
    if class_name.lower() == "bollard":
        return True
    return (
        impact_delta >= AI_SENSOR_SUPPORT_DELTA
        and impact_speed >= SENSOR_EVIDENCE_MIN_SPEED_MPS
    )


def bbox_iou(first, second) -> float:
    if not first or not second or len(first) != 4 or len(second) != 4:
        return 0.0
    left = max(float(first[0]), float(second[0]))
    top = max(float(first[1]), float(second[1]))
    right = min(float(first[2]), float(second[2]))
    bottom = min(float(first[3]), float(second[3]))
    intersection = max(0.0, right - left) * max(0.0, bottom - top)
    first_area = max(0.0, float(first[2]) - float(first[0])) * max(0.0, float(first[3]) - float(first[1]))
    second_area = max(0.0, float(second[2]) - float(second[0])) * max(0.0, float(second[3]) - float(second[1]))
    union = first_area + second_area - intersection
    return intersection / union if union > 0 else 0.0


def merge_eligible_detections(detections):
    """Keep raw detections in DB, but suppress overlapping same-damage evidence."""
    accepted = []
    for detection in detections:
        rule = CLASS_RULES.get(str(detection[3]).lower())
        if rule is None or float(detection[4]) < rule[1]:
            continue
        duplicate = any(
            CLASS_RULES.get(str(existing[3]).lower(), (None,))[0] == rule[0]
            and bbox_iou(existing[5], detection[5]) >= SAME_CLASS_IOU_THRESHOLD
            for existing in accepted
        )
        if not duplicate:
            accepted.append(detection)
    return accepted


def eligible_user_hazard_damage_types(
    detections,
    impact_delta: float,
    impact_speed: float,
) -> set[str]:
    """Return damage types that satisfy the current rider-hazard policy."""
    return {
        CLASS_RULES[str(detection[3]).lower()][0]
        for detection in merge_eligible_detections(detections)
        if qualifies_for_user_hazard(
            str(detection[3]), impact_delta, impact_speed,
        )
    }


def _remove_ineligible_user_hazard_evidence(
    cur,
    sensor_log_id: int,
    eligible_damage_types: set[str],
) -> tuple[int, set[str]]:
    """Remove only automatically generated rider evidence that no longer qualifies."""
    cur.execute(
        """
        SELECT e.hazard_zone_evidence_id,z.zone_id::text,z.road_damage_type
        FROM hazard_zone_evidence e
        JOIN hazard_zones z ON z.zone_id=e.zone_id
        WHERE e.sensor_log_id=%s
          AND z.zone_category='USER_HAZARD'
          AND (
              e.ai_detection_id IS NOT NULL
              OR e.details ? 'temporary_rule_version'
          )
        """,
        (sensor_log_id,),
    )
    stale = [
        (evidence_id, zone_id)
        for evidence_id, zone_id, damage_type in cur.fetchall()
        if damage_type not in eligible_damage_types
    ]
    if not stale:
        return 0, set()
    cur.execute(
        "DELETE FROM hazard_zone_evidence WHERE hazard_zone_evidence_id=ANY(%s)",
        ([item[0] for item in stale],),
    )
    return cur.rowcount, {item[1] for item in stale}


def _refresh_or_archive_empty_zone(cur, zone_id: str) -> None:
    cur.execute(
        """
        SELECT z.road_damage_type,z.zone_category,COUNT(e.hazard_zone_evidence_id)
        FROM hazard_zones z
        LEFT JOIN hazard_zone_evidence e ON e.zone_id=z.zone_id
        WHERE z.zone_id=%s
        GROUP BY z.zone_id
        """,
        (zone_id,),
    )
    row = cur.fetchone()
    if row is None:
        return
    damage_type, zone_category, evidence_count = row
    if evidence_count:
        _refresh_zone(cur, zone_id, damage_type, zone_category)
        return
    cur.execute(
        """
        UPDATE hazard_zones SET
            status=CASE WHEN status='REJECTED' THEN status ELSE 'ARCHIVED' END,
            is_user_visible=FALSE,
            risk_score=0,
            distinct_ride_count=0,
            distinct_user_count=0,
            confidence=NULL,
            confirmation_source=CASE
                WHEN confirmation_source='AUTO' THEN NULL
                ELSE confirmation_source
            END,
            updated_at=CURRENT_TIMESTAMP,
            archived_at=CASE
                WHEN status='REJECTED' THEN archived_at
                ELSE CURRENT_TIMESTAMP
            END
        WHERE zone_id=%s
        """,
        (zone_id,),
    )


def apply_hazard_rules(cur, job_id: int) -> int:
    """Link one AI job to administrative anomalies and rider hazards.

    All eligible AI detections become ROAD_ANOMALY evidence. Bollards can also
    become USER_HAZARD evidence from AI alone. Other classes require the same
    image-time sensor window to contain a moving impact at or above the support
    threshold. Impact speed is the median speed within one second of the
    strongest image-matched impact, so a single GPS speed spike cannot promote
    a hazard by itself.
    Accident detection remains outside this server-side AI rule set.
    """
    cur.execute(
        """
        SELECT s.sensor_log_id,s.event_uuid,s.ride_session_id,s.captured_at,
               s.latitude,s.longitude,s.accuracy,s.speed,
               s.accel_x,s.accel_y,s.accel_z,s.sensor_samples,
               s.peak_sample_at,s.is_test_data
        FROM ai_analysis_jobs j
        JOIN sensor_logs s ON s.sensor_log_id=j.sensor_log_id
        WHERE j.ai_analysis_job_id=%s
        """,
        (job_id,),
    )
    sensor = cur.fetchone()
    if sensor is None or sensor[13] or sensor[4] is None or sensor[5] is None:
        return 0
    accuracy = float(sensor[6]) if sensor[6] is not None else float("inf")
    if accuracy > GPS_MAX_EVIDENCE_ACCURACY_M:
        return 0

    impact_delta, impact_at = image_matched_impact(
        sensor[11], sensor[3], sensor[8], sensor[9], sensor[10], sensor[12],
    )
    impact_speed = median_speed_around_impact(
        sensor[11], impact_at, sensor[3], sensor[7],
    )
    cur.execute(
        """
        SELECT ai_detection_id,stage,model_version,class_name,confidence,bbox_xyxy
        FROM ai_detections WHERE ai_analysis_job_id=%s
        ORDER BY confidence DESC,ai_detection_id
        """,
        (job_id,),
    )
    detections = merge_eligible_detections(cur.fetchall())
    eligible_damage_types = eligible_user_hazard_damage_types(
        detections, impact_delta, impact_speed,
    )
    _, affected_zone_ids = _remove_ineligible_user_hazard_evidence(
        cur, sensor[0], eligible_damage_types,
    )
    linked = 0
    for detection in detections:
        class_name = str(detection[3]).lower()
        rule = CLASS_RULES[class_name]
        common = dict(
            cur=cur,
            sensor=sensor,
            damage_type=rule[0],
            title=rule[3],
            radius=rule[2],
            impact_delta=impact_delta,
            impact_at=impact_at,
            impact_speed=impact_speed,
            ai_detection=detection,
            accuracy=accuracy,
        )
        linked += _link_evidence(zone_category=ROAD_ANOMALY, **common)

        if qualifies_for_user_hazard(class_name, impact_delta, impact_speed):
            linked += _link_evidence(zone_category=USER_HAZARD, **common)
    for affected_zone_id in affected_zone_ids:
        _refresh_or_archive_empty_zone(cur, affected_zone_id)
    return linked


def _link_evidence(
    *, cur, sensor, zone_category: str, damage_type: str, title: str, radius: float,
    impact_delta: float, impact_at: int, impact_speed: float,
    ai_detection, accuracy: float,
) -> int:
    latitude, longitude = float(sensor[4]), float(sensor[5])
    cluster_radius = min(30.0, radius + max(0.0, accuracy - GPS_PRIMARY_ACCURACY_M) * 0.5)
    detection_id = ai_detection[0] if ai_detection else None
    source_model = ai_detection[1] if ai_detection else "RULE_SENSOR"
    model_version = ai_detection[2] if ai_detection else "sensor-threshold-2026-06-27-v1"
    confidence = float(ai_detection[4]) if ai_detection else None
    details = {
        "zone_category": zone_category,
        "impact_delta_mps2": impact_delta,
        "impact_at": impact_at,
        "impact_speed_mps": impact_speed,
        "sensor_match_window_ms": IMAGE_SENSOR_WINDOW_MS,
        "impact_speed_window_ms": IMPACT_SPEED_WINDOW_MS,
        "sensor_support": (
            impact_delta >= AI_SENSOR_SUPPORT_DELTA
            and impact_speed >= SENSOR_EVIDENCE_MIN_SPEED_MPS
        ),
        "strong_sensor_impact": impact_delta >= SENSOR_ONLY_DELTA,
        "very_strong_impact": impact_delta >= VERY_STRONG_IMPACT_DELTA,
        "gps_accuracy_m": accuracy,
        "gps_primary_quality": accuracy <= GPS_PRIMARY_ACCURACY_M,
        # Kept for older admin clients; its value now follows the robust median
        # speed policy rather than the former maximum-speed policy.
        "max_speed_mps": impact_speed,
        "temporary_rule_version": "2026-06-27-v2-time-matched",
    }

    # Reprocessing can slightly move a zone centroid as earlier evidence is
    # refreshed. Always reuse the evidence already assigned to this sensor,
    # category and damage type before doing a new spatial lookup. This keeps a
    # historical backfill idempotent even when centroids change during the run.
    cur.execute(
        """
        SELECT e.hazard_zone_evidence_id,z.zone_id::text,
               SQRT(POWER((z.latitude-%s)*111000.0,2)+
                    POWER((z.longitude-%s)*91000.0,2)) distance
        FROM hazard_zone_evidence e
        JOIN hazard_zones z ON z.zone_id=e.zone_id
        WHERE e.sensor_log_id=%s
          AND z.zone_category=%s
          AND z.road_damage_type=%s
        ORDER BY e.hazard_zone_evidence_id
        LIMIT 1
        """,
        (latitude, longitude, sensor[0], zone_category, damage_type),
    )
    assigned = cur.fetchone()
    if assigned is not None:
        cur.execute(
            """
            UPDATE hazard_zone_evidence SET ai_detection_id=%s,source_model=%s,
                model_version=%s,confidence=%s,distance_meters=%s,details=%s,
                linked_at=CURRENT_TIMESTAMP
            WHERE hazard_zone_evidence_id=%s
            """,
            (
                detection_id, source_model, model_version, confidence,
                float(assigned[2]), Json(details), assigned[0],
            ),
        )
        _refresh_zone(cur, assigned[1], damage_type, zone_category)
        return 0

    cur.execute(
        """
        SELECT zone_id::text, status,
               SQRT(POWER((latitude-%s)*111000.0,2)+POWER((longitude-%s)*91000.0,2)) distance
        FROM hazard_zones
        WHERE road_damage_type=%s
          AND zone_category=%s
          AND status IN ('CANDIDATE','CONFIRMED','STALE','ARCHIVED','EXPIRED','REJECTED')
          AND ABS(latitude-%s)<=%s/111000.0
          AND ABS(longitude-%s)<=%s/91000.0
        ORDER BY distance LIMIT 1
        """,
        (
            latitude, longitude, damage_type, zone_category,
            latitude, cluster_radius, longitude, cluster_radius,
        ),
    )
    row = cur.fetchone()
    if row is not None and row[1] == "REJECTED" and float(row[2]) <= cluster_radius:
        # Keep raw detections for audit, but do not recreate a cluster that an
        # administrator explicitly rejected.
        return 0
    if row is None or float(row[2]) > cluster_radius:
        zone_id = str(uuid.uuid4())
        confidence = float(ai_detection[4]) if ai_detection else None
        source = ai_detection[1] if ai_detection else "RULE_SENSOR"
        cur.execute(
            """
            INSERT INTO hazard_zones(
                zone_id,title,road_damage_type,severity,latitude,longitude,
                radius_meters,detection_count,description,source_model,
                confidence,status,is_test_data,zone_category,is_user_visible,expires_at
            ) VALUES (
                %s,%s,%s,'LOW',%s,%s,%s,1,%s,%s,%s,'CANDIDATE',FALSE,%s,FALSE,
                CASE WHEN %s='flood_area'
                     THEN CURRENT_TIMESTAMP + INTERVAL '24 hours' END
            )
            """,
            (
                zone_id, title, damage_type, latitude, longitude, radius,
                "자동 수집 근거로 생성된 구간 후보", source, confidence,
                zone_category, damage_type,
            ),
        )
        distance = 0.0
    else:
        zone_id, distance = row[0], float(row[2])

    # AI reruns replace detection rows. Preserve the existing evidence while
    # attaching it to the new detection id rather than creating a duplicate.
    cur.execute(
        "SELECT hazard_zone_evidence_id FROM hazard_zone_evidence WHERE zone_id=%s AND sensor_log_id=%s",
        (zone_id, sensor[0]),
    )
    existing = cur.fetchone()
    if existing is not None:
        cur.execute(
            """
            UPDATE hazard_zone_evidence SET ai_detection_id=%s,source_model=%s,
                model_version=%s,confidence=%s,distance_meters=%s,details=%s,
                linked_at=CURRENT_TIMESTAMP
            WHERE hazard_zone_evidence_id=%s
            """,
            (
                detection_id, source_model, model_version, confidence,
                distance, Json(details), existing[0],
            ),
        )
        _refresh_zone(cur, zone_id, damage_type, zone_category)
        return 0

    # One evidence per zone/ride/time neighborhood. Multiple detections from a
    # burst image must not promote a zone by themselves.
    if sensor[2]:
        cur.execute(
            """
            SELECT 1 FROM hazard_zone_evidence e
            JOIN sensor_logs s ON s.sensor_log_id=e.sensor_log_id
            WHERE e.zone_id=%s AND s.ride_session_id=%s
              AND ABS(s.captured_at-%s)<=%s
              AND SQRT(POWER((s.latitude-%s)*111000.0,2)+
                       POWER((s.longitude-%s)*91000.0,2))<=%s
            LIMIT 1
            """,
            (
                zone_id, sensor[2], sensor[3], SAME_RIDE_DEDUPE_SECONDS * 1000,
                latitude, longitude, SAME_RIDE_DEDUPE_DISTANCE_M,
            ),
        )
        if cur.fetchone() is not None:
            return 0

    cur.execute(
        """
        INSERT INTO hazard_zone_evidence(
            zone_id,sensor_log_id,event_uuid,ai_detection_id,source_model,
            model_version,confidence,distance_meters,details
        ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)
        ON CONFLICT (zone_id,sensor_log_id) WHERE sensor_log_id IS NOT NULL
        DO NOTHING RETURNING hazard_zone_evidence_id
        """,
        (
            zone_id, sensor[0], sensor[1], detection_id, source_model,
            model_version, confidence, distance, Json(details),
        ),
    )
    if cur.fetchone() is None:
        return 0

    _refresh_zone(cur, zone_id, damage_type, zone_category)
    return 1


def calculate_risk_score(
    damage_type: str,
    average_confidence: float,
    max_impact_delta: float,
    distinct_ride_count: int,
    recency_factor: float = 1.0,
) -> float:
    confidence_score = max(0.0, min(1.0, average_confidence)) * 35.0
    sensor_score = max(0.0, min(1.0, max_impact_delta / VERY_STRONG_IMPACT_DELTA)) * 25.0
    repeat_score = max(0.0, min(1.0, distinct_ride_count / max(MIN_DISTINCT_RIDES, 1))) * 25.0
    recency_score = max(0.0, min(1.0, recency_factor)) * 15.0
    bonus = CLASS_RISK_BONUS.get(damage_type, 0.0)
    if damage_type == "crack":
        bonus = min(15.0, average_confidence * 15.0)
    return round(min(100.0, confidence_score + sensor_score + repeat_score + recency_score + bonus), 2)


def review_requirements_met(
    distinct_ride_count: int,
    distinct_user_count: int,
    average_confidence: float,
) -> bool:
    return (
        distinct_ride_count >= MIN_DISTINCT_RIDES
        and average_confidence >= 0.60
        and (not REQUIRE_DISTINCT_USERS or distinct_user_count >= MIN_DISTINCT_USERS)
    )


def _refresh_zone(cur, zone_id: str, damage_type: str, zone_category: str) -> None:
    evidence_window = (
        "AND e.linked_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'"
        if damage_type == "flood_area" else ""
    )
    cur.execute(
        f"""
        SELECT COUNT(*),COUNT(DISTINCT s.ride_session_id),COUNT(DISTINCT s.user_id),
               AVG(s.latitude),AVG(s.longitude),AVG(e.confidence),
               BOOL_OR(COALESCE((e.details->>'sensor_support')::boolean,FALSE)),
               MAX(COALESCE((e.details->>'impact_delta_mps2')::double precision,0))
        FROM hazard_zone_evidence e
        JOIN sensor_logs s ON s.sensor_log_id=e.sensor_log_id
        WHERE e.zone_id=%s
        {evidence_window}
        """,
        (zone_id,),
    )
    (
        evidence_count, ride_count, user_count, latitude, longitude,
        average_confidence, sensor_support, max_impact_delta,
    ) = cur.fetchone()
    average_confidence = float(average_confidence or 0.0)
    # Retained/anonymized evidence can have its location removed. Keep the
    # existing zone centroid in that case instead of writing NULL into the
    # NOT NULL latitude/longitude columns during a refresh or backfill.
    if latitude is None or longitude is None:
        cur.execute(
            "SELECT latitude,longitude FROM hazard_zones WHERE zone_id=%s",
            (zone_id,),
        )
        existing_location = cur.fetchone()
        if existing_location:
            latitude = existing_location[0]
            longitude = existing_location[1]
    if latitude is None or longitude is None:
        return
    risk_score = calculate_risk_score(
        damage_type, average_confidence, float(max_impact_delta or 0.0), int(ride_count),
    )
    severity = "HIGH" if risk_score >= 70 else "MEDIUM" if risk_score >= 40 else "LOW"
    requirements_met = review_requirements_met(
        int(ride_count), int(user_count), average_confidence,
    )
    next_status = "CONFIRMED" if requirements_met else "CANDIDATE"
    is_user_visible = zone_category == USER_HAZARD and requirements_met
    radius = AREA_HAZARD_RADIUS_M if damage_type in {"crack", "flood_area"} else POINT_HAZARD_RADIUS_M
    cur.execute(
        "SELECT status FROM hazard_zones WHERE zone_id=%s",
        (zone_id,),
    )
    previous_row = cur.fetchone()
    previous_status = previous_row[0] if previous_row else None
    cur.execute(
        """
        UPDATE hazard_zones SET latitude=%s,longitude=%s,radius_meters=%s,
            detection_count=%s,confidence=%s,severity=%s,
            risk_score=%s,distinct_ride_count=%s,distinct_user_count=%s,
            status=CASE
                WHEN status='REJECTED' THEN status
                WHEN status='CONFIRMED' AND confirmation_source='MANUAL' THEN status
                ELSE %s
            END,
            is_user_visible=CASE
                WHEN status='REJECTED' THEN FALSE
                WHEN status='CONFIRMED' AND confirmation_source='MANUAL'
                    THEN zone_category='USER_HAZARD'
                ELSE %s
            END,
            confirmation_source=CASE
                WHEN status='REJECTED' THEN confirmation_source
                WHEN status='CONFIRMED' AND confirmation_source='MANUAL'
                    THEN confirmation_source
                WHEN %s='CONFIRMED' THEN 'AUTO'
                ELSE NULL
            END,
            confirmed_at=CASE
                WHEN status='REJECTED' THEN confirmed_at
                WHEN status='CONFIRMED' AND confirmation_source='MANUAL'
                    THEN confirmed_at
                WHEN %s='CONFIRMED' THEN COALESCE(confirmed_at,CURRENT_TIMESTAMP)
                ELSE NULL
            END,
            expires_at=CASE
                WHEN %s='flood_area' THEN CURRENT_TIMESTAMP + INTERVAL '24 hours'
                ELSE NULL
            END,
            stale_at=NULL,archived_at=NULL,
            last_detected_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
        WHERE zone_id=%s
        RETURNING status,confirmation_source
        """,
        (
            latitude, longitude, radius, max(evidence_count, 1), average_confidence,
            severity, risk_score, ride_count, user_count, next_status,
            is_user_visible, next_status, next_status, damage_type, zone_id,
        ),
    )
    updated_row = cur.fetchone()
    if (
        previous_status != "CONFIRMED"
        and updated_row == ("CONFIRMED", "AUTO")
        and zone_category == USER_HAZARD
    ):
        award_establishment_contributions(cur, zone_id)


def refresh_hazard_zone_lifecycle(cur) -> dict[str, int]:
    """Hide old confirmed zones without deleting their evidence."""
    cur.execute(
        """
        UPDATE hazard_zones SET status='EXPIRED',is_user_visible=FALSE,
            updated_at=CURRENT_TIMESTAMP
        WHERE road_damage_type='flood_area'
          AND status IN ('CANDIDATE','CONFIRMED')
          AND expires_at IS NOT NULL AND expires_at <= CURRENT_TIMESTAMP
        """
    )
    expired = cur.rowcount
    cur.execute(
        """
        UPDATE hazard_zones SET status='ARCHIVED',is_user_visible=FALSE,
            archived_at=CURRENT_TIMESTAMP,
            updated_at=CURRENT_TIMESTAMP
        WHERE status='STALE'
          AND last_detected_at < CURRENT_TIMESTAMP-(%s * INTERVAL '1 day')
        """,
        (HAZARD_ARCHIVE_DAYS,),
    )
    archived = cur.rowcount
    cur.execute(
        """
        UPDATE hazard_zones SET status='STALE',is_user_visible=FALSE,
            stale_at=CURRENT_TIMESTAMP,
            updated_at=CURRENT_TIMESTAMP
        WHERE status='CONFIRMED'
          AND road_damage_type<>'flood_area'
          AND last_detected_at < CURRENT_TIMESTAMP-(%s * INTERVAL '1 day')
        """,
        (HAZARD_STALE_DAYS,),
    )
    return {"expired": expired, "stale": cur.rowcount, "archived": archived}
