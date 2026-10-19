from app.hazard_rules import (
    AI_SENSOR_SUPPORT_DELTA,
    CLASS_RULES,
    MIN_DISTINCT_RIDES,
    MIN_DISTINCT_USERS,
    REQUIRE_DISTINCT_USERS,
    SENSOR_ONLY_DELTA,
    VERY_STRONG_IMPACT_DELTA,
    acceleration_delta,
    bbox_iou,
    eligible_user_hazard_damage_types,
    image_matched_impact,
    median_speed_around_impact,
    merge_eligible_detections,
    max_sensor_delta,
    qualifies_for_user_hazard,
    review_requirements_met,
)


def test_temporary_threshold_order_matches_ride_analysis():
    assert AI_SENSOR_SUPPORT_DELTA == 14.0
    assert SENSOR_ONLY_DELTA == 18.5
    assert VERY_STRONG_IMPACT_DELTA == 30.0
    assert AI_SENSOR_SUPPORT_DELTA < SENSOR_ONLY_DELTA < VERY_STRONG_IMPACT_DELTA


def test_max_sensor_delta_uses_high_frequency_samples():
    samples = [
        {"accel_x": 9.80665, "accel_y": 0.0, "accel_z": 0.0},
        {"accel_x": 30.0, "accel_y": 0.0, "accel_z": 0.0},
    ]
    assert max_sensor_delta(samples, 9.80665, 0.0, 0.0) == acceleration_delta(30.0, 0.0, 0.0)


def test_ai_impact_uses_only_samples_after_image_within_three_seconds():
    captured_at = 10_000
    samples = [
        {
            "sampled_at": 10_500,
            "accel_x": 24.0,
            "accel_y": 0.0,
            "accel_z": 0.0,
            "speed": 2.0,
        },
        {
            "sampled_at": 8_999,
            "accel_x": 40.0,
            "accel_y": 0.0,
            "accel_z": 0.0,
            "speed": 8.0,
        },
        {
            "sampled_at": 13_001,
            "accel_x": 50.0,
            "accel_y": 0.0,
            "accel_z": 0.0,
            "speed": 8.0,
        },
    ]
    impact_delta, impact_at = image_matched_impact(
        samples, captured_at, 9.80665, 0.0, 0.0,
    )
    assert impact_at == 10_500
    assert impact_delta == acceleration_delta(24.0, 0.0, 0.0)


def test_peak_timestamp_fallback_is_used_when_sample_payload_is_sparse():
    impact_delta, impact_at = image_matched_impact(
        [], 10_000, 20.0, 0.0, 0.0, peak_sample_at=11_500,
    )
    assert impact_at == 11_500
    assert impact_delta == acceleration_delta(20.0, 0.0, 0.0)


def test_ai_impact_keeps_top_level_image_snapshot_as_fallback():
    impact_delta, impact_at = image_matched_impact(
        [{"accel_x": 40.0, "accel_y": 0.0, "accel_z": 0.0}],
        10_000,
        20.0,
        0.0,
        0.0,
    )
    assert impact_at == 10_000
    assert impact_delta == acceleration_delta(20.0, 0.0, 0.0)


def test_impact_speed_is_median_within_one_second_not_maximum():
    samples = [
        # Only samples in the image's own post-capture one-second window are
        # eligible. The median must not be replaced by the 100/200 spikes.
        {"sampled_at": 10_500, "speed": 2.0},
        {"sampled_at": 10_600, "speed": 2.5},
        {"sampled_at": 10_900, "speed": 100.0},
        {"sampled_at": 11_000, "speed": 200.0},
    ]
    assert median_speed_around_impact(samples, 10_500, 10_000, 3.0) == 3.0


def test_eligible_user_damage_types_follow_current_sensor_policy():
    detections = [
        (1, "BBOX", "bbox-v1", "pothole", 0.80, [0, 0, 100, 100]),
        (2, "BBOX", "bbox-v1", "bollard", 0.80, [200, 0, 300, 100]),
    ]
    assert eligible_user_hazard_damage_types(detections, 13.9, 3.0) == {"bollard"}
    assert eligible_user_hazard_damage_types(detections, 14.0, 1.5) == {
        "pothole", "bollard",
    }


def test_class_candidate_thresholds_match_operating_policy():
    assert CLASS_RULES["linear"][1] == 0.40
    assert CLASS_RULES["alligator"][1] == 0.40
    assert CLASS_RULES["pothole"][1] == 0.50
    assert CLASS_RULES["flood_area"][1] == 0.50
    assert CLASS_RULES["manhole"][1] == 0.55
    assert CLASS_RULES["bollard"][1] == 0.55


def test_distinct_user_gate_exists_but_is_disabled_by_default():
    assert MIN_DISTINCT_RIDES == 3
    assert MIN_DISTINCT_USERS == 2
    assert REQUIRE_DISTINCT_USERS is False
    assert review_requirements_met(3, 1, 0.60)
    assert not review_requirements_met(2, 2, 0.90)


def test_bollard_is_visual_user_hazard_without_sensor_impact():
    assert qualifies_for_user_hazard("bollard", 0.0, 0.0)


def test_other_classes_require_moving_sensor_impact():
    for class_name in ("pothole", "manhole", "flood_area", "linear", "alligator"):
        assert not qualifies_for_user_hazard(class_name, 13.99, 10.0)
        assert not qualifies_for_user_hazard(class_name, 14.0, 1.49)
        assert qualifies_for_user_hazard(class_name, 14.0, 1.5)


def test_same_damage_overlapping_detections_are_merged_for_evidence():
    detections = [
        (1, "BBOX", "bbox-v1", "pothole", 0.80, [0, 0, 100, 100]),
        (2, "BBOX", "bbox-v1", "pothole", 0.70, [10, 10, 90, 90]),
        (3, "BBOX", "bbox-v1", "manhole", 0.70, [10, 10, 90, 90]),
    ]
    assert bbox_iou(detections[0][5], detections[1][5]) > 0.50
    assert [item[0] for item in merge_eligible_detections(detections)] == [1, 3]
