import uuid

import pytest
from pydantic import ValidationError

from app.main import AccidentEventRequest, RideResultRequest, SensorLogRequest


def sensor_payload():
    return {
        "event_uuid": str(uuid.uuid4()),
        "ride_session_id": str(uuid.uuid4()),
        "captured_at": 1,
        "latitude": 35.18,
        "longitude": 128.10,
        "accuracy": 5.0,
        "speed": 2.0,
        "direction": 90.0,
        "gyro_x": 0.1,
        "gyro_y": 0.2,
        "gyro_z": 0.3,
        "accel_x": 0.1,
        "accel_y": 0.2,
        "accel_z": 9.8,
        "geomagnetic_x": 1.0,
        "geomagnetic_y": 2.0,
        "geomagnetic_z": 3.0,
        "image_path": None,
        "sensor_samples": [],
    }


def test_sensor_contract_accepts_android_shape():
    request = SensorLogRequest(**sensor_payload())
    assert request.event_uuid
    assert request.sensor_samples == []


@pytest.mark.parametrize(
    ("field", "value"),
    [("latitude", 91.0), ("longitude", 181.0), ("accuracy", -1.0), ("speed", -1.0)],
)
def test_sensor_contract_rejects_invalid_ranges(field, value):
    payload = sensor_payload()
    payload[field] = value
    with pytest.raises(ValidationError):
        SensorLogRequest(**payload)


def test_sensor_contract_rejects_unknown_fields():
    payload = sensor_payload()
    payload["unexpected"] = "value"
    with pytest.raises(ValidationError):
        SensorLogRequest(**payload)


def test_sensor_contract_rejects_image_path_from_another_event():
    payload = sensor_payload()
    other_event = str(uuid.uuid4())
    payload["image_path"] = f"events/{other_event[:2]}/{other_event}.jpg"
    with pytest.raises(ValidationError):
        SensorLogRequest(**payload)


def test_ride_contract_rejects_reversed_time():
    with pytest.raises(ValidationError):
        RideResultRequest(
            ride_session_id=str(uuid.uuid4()),
            started_at=20,
            ended_at=10,
            elapsed_seconds=10,
            total_distance_meters=0,
            average_speed_kmh=0,
            max_speed_kmh=0,
            collected_frame_count=0,
            accident_count=0,
        )


def test_accident_contract_rejects_unknown_confirmation():
    payload = {
        "event_uuid": str(uuid.uuid4()),
        "detected_at": 1,
        "accel_x": 0,
        "accel_y": 0,
        "accel_z": 0,
        "gyro_x": 0,
        "gyro_y": 0,
        "gyro_z": 0,
        "acceleration_magnitude": 0,
        "gyro_magnitude": 0,
        "confirmation_status": "UNKNOWN",
        "is_false_positive": False,
    }
    with pytest.raises(ValidationError):
        AccidentEventRequest(**payload)
