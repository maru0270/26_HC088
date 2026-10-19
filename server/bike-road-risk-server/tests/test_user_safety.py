import pytest

from app.user_safety import badge_progress, contribution_type_for_feedback


def test_feedback_maps_to_non_repeatable_contribution_type():
    assert contribution_type_for_feedback("DANGER") == "CONFIRMATION"
    assert contribution_type_for_feedback("CAUTION") == "CONFIRMATION"
    assert contribution_type_for_feedback("RESOLVED") == "RESOLUTION"


def test_unknown_feedback_type_is_rejected():
    with pytest.raises(ValueError):
        contribution_type_for_feedback("UNKNOWN")


def test_badge_progress_uses_configurable_level_order():
    before_first = badge_progress(0)
    assert before_first["current_badge"] is None
    assert before_first["next_badge"] == "도로 관찰자"
    assert before_first["remaining_to_next"] == 5

    guardian = badge_progress(50)
    assert guardian["current_badge"] == "BikeEyes 가디언"
    assert guardian["next_badge"] is None
    assert guardian["remaining_to_next"] == 0
