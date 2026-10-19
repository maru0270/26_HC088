from pathlib import Path

from app import ai_service


class FakeCursor:
    def __init__(self):
        self.calls = []

    def execute(self, statement, parameters):
        self.calls.append((statement, parameters))


def test_classified_path_contains_class_date_and_event_suffix():
    relative = ai_service._classified_relative_path(
        1787502896000,
        "abcdef12-3456-7890-abcd-ef1234567890",
        "Flood Area",
    )
    assert relative.startswith("classified/flood_area/")
    assert relative.endswith("(flood_area)_abcdef12.jpg")


def test_each_detected_class_gets_its_own_copy(tmp_path, monkeypatch):
    monkeypatch.setattr(ai_service, "IMAGE_UPLOAD_DIR", tmp_path)
    source = tmp_path / "events" / "ab" / "event.jpg"
    source.parent.mkdir(parents=True)
    source.write_bytes(b"same-original")
    cursor = FakeCursor()

    paths = ai_service.store_classified_image_copies(
        cursor,
        job_id=1,
        sensor_log_id=2,
        event_uuid="abcdef12-3456-7890-abcd-ef1234567890",
        captured_at=1787502896000,
        source_image_path="events/ab/event.jpg",
        class_names={"pothole", "bollard"},
    )

    assert len(paths) == 2
    assert {Path(path).parts[1] for path in paths} == {"pothole", "bollard"}
    assert all((tmp_path / path).read_bytes() == b"same-original" for path in paths)


def test_no_detection_is_stored_in_etc(tmp_path, monkeypatch):
    monkeypatch.setattr(ai_service, "IMAGE_UPLOAD_DIR", tmp_path)
    source = tmp_path / "events" / "ab" / "event.jpg"
    source.parent.mkdir(parents=True)
    source.write_bytes(b"same-original")

    paths = ai_service.store_classified_image_copies(
        FakeCursor(),
        job_id=1,
        sensor_log_id=2,
        event_uuid="abcdef12-3456-7890-abcd-ef1234567890",
        captured_at=1787502896000,
        source_image_path="events/ab/event.jpg",
        class_names=set(),
    )

    assert len(paths) == 1
    assert Path(paths[0]).parts[1] == "etc"

