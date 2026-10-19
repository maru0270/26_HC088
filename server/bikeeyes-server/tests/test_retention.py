from pathlib import Path

import app.main as main


def test_stored_image_path_accepts_child(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "IMAGE_UPLOAD_DIR", tmp_path.resolve())
    expected = (tmp_path / "events" / "ab" / "image.jpg").resolve()
    assert main._stored_image_path("events/ab/image.jpg") == expected
    assert main._stored_image_path(str(expected)) == expected
    assert main._image_storage_key(str(expected)) == "events/ab/image.jpg"


def test_stored_image_path_rejects_traversal(tmp_path, monkeypatch):
    monkeypatch.setattr(main, "IMAGE_UPLOAD_DIR", tmp_path.resolve())
    assert main._stored_image_path("../outside.jpg") is None
    assert main._stored_image_path(str(Path(tmp_path).anchor) + "outside.jpg") is None


def test_existing_image_path_falls_back_to_raw_when_annotation_is_missing(
    tmp_path, monkeypatch
):
    monkeypatch.setattr(main, "IMAGE_UPLOAD_DIR", tmp_path.resolve())
    raw = tmp_path / "events" / "ab" / "image.jpg"
    raw.parent.mkdir(parents=True)
    raw.write_bytes(b"raw")

    assert main._first_existing_stored_image_path(
        "annotated/2026/08/14/missing.jpg", "events/ab/image.jpg"
    ) == raw.resolve()


def test_retention_policy_constants_match_confirmed_policy():
    assert main.RAW_RETENTION_DAYS == 365
    assert main.SYSTEM_LOG_RETENTION_DAYS == 90
    assert main.RIDE_RECOVERY_DAYS == 7
    assert main.RETENTION_INTERVAL_SECONDS == 24 * 60 * 60


def test_raw_retention_keeps_exact_one_year_boundary():
    now_ms = 2_000_000_000_000
    cutoff = main.retention_cutoff_ms(now_ms, main.RAW_RETENTION_DAYS)
    assert main.is_retention_expired(cutoff, now_ms, main.RAW_RETENTION_DAYS) is False
    assert main.is_retention_expired(cutoff - 1, now_ms, main.RAW_RETENTION_DAYS) is True


def test_system_log_boundary_is_retained_until_it_becomes_older():
    now_ms = 2_000_000_000_000
    log_cutoff = main.retention_cutoff_ms(now_ms, main.SYSTEM_LOG_RETENTION_DAYS)
    assert main.is_retention_expired(
        log_cutoff, now_ms, main.SYSTEM_LOG_RETENTION_DAYS
    ) is False
    assert main.is_retention_expired(
        log_cutoff - 1, now_ms, main.SYSTEM_LOG_RETENTION_DAYS
    ) is True


def test_ride_recovery_is_finalized_at_exact_due_time():
    now_ms = 2_000_000_000_000
    assert main.is_due_at(now_ms, now_ms) is True
    assert main.is_due_at(now_ms + 1, now_ms) is False


def test_retention_scheduler_is_enabled_by_default():
    assert main.RETENTION_ENABLED is True


def test_individual_ride_deletion_anonymizes_collected_data_sql_contract():
    import inspect

    source = inspect.getsource(main.finalize_ride_deletion)
    assert "user_id=NULL" in source
    assert "ride_session_id=NULL" in source
    assert "latitude=NULL" in source
    assert "DELETE FROM rides" in source
    assert "image_path=NULL" not in source
