import io
import os
import uuid
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient
from PIL import Image

import app.auth as auth
import app.db as db
import app.main as main
import app.operations as operations


pytestmark = pytest.mark.skipif(
    os.getenv("BIKE_RUN_POSTGRES_INTEGRATION", "").lower() != "true",
    reason="set BIKE_RUN_POSTGRES_INTEGRATION=true to use an isolated PostgreSQL schema",
)


@pytest.fixture
def postgres_schema(monkeypatch, tmp_path):
    original_get_connection = db.get_connection
    schema = "bikeeyes_test_" + uuid.uuid4().hex
    admin = original_get_connection()
    try:
        with admin.cursor() as cur:
            cur.execute(f'CREATE SCHEMA "{schema}"')
        admin.commit()

        def isolated_connection():
            connection = original_get_connection()
            with connection.cursor() as cur:
                cur.execute(f'SET search_path TO "{schema}"')
            connection.commit()
            return connection

        monkeypatch.setattr(db, "get_connection", isolated_connection)
        monkeypatch.setattr(main, "get_connection", isolated_connection)
        monkeypatch.setattr(auth, "get_connection", isolated_connection)
        monkeypatch.setattr(operations, "get_connection", isolated_connection)
        monkeypatch.setattr(main, "RETENTION_ENABLED", False)
        monkeypatch.setattr(main, "BACKUP_ENABLED", False)
        image_dir = tmp_path / "images"
        image_dir.mkdir()
        monkeypatch.setattr(main, "IMAGE_UPLOAD_DIR", image_dir)
        db.ensure_schema()
        yield isolated_connection
    finally:
        with admin.cursor() as cur:
            cur.execute(f'DROP SCHEMA IF EXISTS "{schema}" CASCADE')
        admin.commit()
        admin.close()


def _signup(client: TestClient, **consent_overrides):
    consent = {
        "ai_research_optional": False,
        "long_term_optional": False,
    }
    consent.update(consent_overrides)
    response = client.post(
        "/auth/signup",
        json={
            "email": f"integration-{uuid.uuid4().hex}@example.com",
            "nickname": "통합테스트",
            "phone": None,
            "password": "password!",
            "device_id": "integration-device",
            "device_name": "pytest",
            "terms_required": True,
            "privacy_required": True,
            "collection_required": True,
            **consent,
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


def _signup_and_headers(client: TestClient):
    tokens = _signup(client)
    return {"Authorization": f"Bearer {tokens['access_token']}"}


def _sensor_payload(session_id: str, event_id: str):
    return {
        "event_uuid": event_id,
        "ride_session_id": session_id,
        "sensor_samples": [],
        "captured_at": 2_000_000_000_000,
        "latitude": 35.18,
        "longitude": 128.10,
        "accuracy": 5.0,
        "speed": 3.0,
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
    }


def test_offline_sensor_then_ride_reconciles_and_deletion_recovers(
    postgres_schema,
):
    session_id = str(uuid.uuid4())
    event_id = str(uuid.uuid4())
    with TestClient(main.app) as client:
        headers = _signup_and_headers(client)
        sensor = _sensor_payload(session_id, event_id)

        assert client.post("/sensor-log", json=sensor, headers=headers).status_code == 200
        assert client.post("/sensor-log", json=sensor, headers=headers).status_code == 200

        connection = postgres_schema()
        with connection.cursor() as cur:
            cur.execute(
                "SELECT count(*) FROM sensor_logs WHERE event_uuid=%s",
                (event_id,),
            )
            assert cur.fetchone()[0] == 1
            cur.execute(
                "SELECT status FROM orphan_sensor_sessions WHERE ride_session_id=%s",
                (session_id,),
            )
            assert cur.fetchone()[0] == "WAITING"
        connection.close()

        ride_response = client.post(
            "/ride-result",
            headers=headers,
            json={
                "ride_session_id": session_id,
                "started_at": 1_999_999_970_000,
                "ended_at": 2_000_000_000_000,
                "elapsed_seconds": 30,
                "total_distance_meters": 10.0,
                "average_speed_kmh": 1.2,
                "max_speed_kmh": 2.0,
                "collected_frame_count": 1,
                "accident_count": 0,
            },
        )
        assert ride_response.status_code == 200, ride_response.text

        connection = postgres_schema()
        with connection.cursor() as cur:
            cur.execute(
                "SELECT status FROM orphan_sensor_sessions WHERE ride_session_id=%s",
                (session_id,),
            )
            assert cur.fetchone()[0] == "RESOLVED"
        connection.close()

        connection = postgres_schema()
        with connection.cursor() as cur:
            cur.execute("UPDATE users SET role='ADMIN'")
        connection.commit()
        connection.close()
        diagnostics = client.get("/admin/diagnostics", headers=headers)
        assert diagnostics.status_code == 200, diagnostics.text
        diagnostics_body = diagnostics.json()
        assert diagnostics_body["orphan_sensor_resolved"] == 1
        assert diagnostics_body["missing_image_files"] == 0
        assert diagnostics_body["latest_migration"] == "015_backfill_accident_blackbox_images.sql"

        zone_id = str(uuid.uuid4())
        connection = postgres_schema()
        with connection.cursor() as cur:
            cur.execute(
                """
                INSERT INTO hazard_zones (
                    zone_id,title,road_damage_type,severity,
                    latitude,longitude,radius_meters,detection_count
                ) VALUES (%s,'통합 위험구간','OTHER','LOW',35.18,128.10,30,1)
                """,
                (zone_id,),
            )
        connection.commit()
        connection.close()
        auto_link = client.post(
            f"/admin/hazard-zones/{zone_id}/evidence/auto-link",
            headers=headers,
            json={"radius_meters": 30, "include_test_data": False, "max_items": 10},
        )
        assert auto_link.status_code == 200, auto_link.text
        assert auto_link.json()["linked_count"] == 1
        evidence = client.get(
            f"/admin/hazard-zones/{zone_id}/evidence", headers=headers
        )
        assert evidence.status_code == 200, evidence.text
        assert evidence.json()["total"] == 1
        assert evidence.json()["items"][0]["event_uuid"] == event_id

        connection = postgres_schema()
        with connection.cursor() as cur:
            cur.execute(
                "UPDATE hazard_zones SET zone_category='USER_HAZARD' WHERE zone_id=%s",
                (zone_id,),
            )
        connection.commit()
        connection.close()
        confirmed = client.patch(
            f"/admin/hazard-zones/{zone_id}/status",
            headers=headers,
            json={"status": "CONFIRMED"},
        )
        assert confirmed.status_code == 200, confirmed.text
        public_zones = client.get("/hazard-zones").json()["zones"]
        assert [zone["zone_id"] for zone in public_zones] == [zone_id]
        cancelled = client.patch(
            f"/admin/hazard-zones/{zone_id}/status",
            headers=headers,
            json={"status": "REJECTED"},
        )
        assert cancelled.status_code == 200, cancelled.text
        assert client.get("/hazard-zones").json()["zones"] == []

        assert client.delete(f"/rides/{session_id}", headers=headers).status_code == 200
        assert client.post(f"/rides/{session_id}/restore", headers=headers).status_code == 200
        assert client.delete(f"/rides/{session_id}", headers=headers).status_code == 200
        assert client.delete(
            f"/rides/{session_id}/permanent", headers=headers
        ).status_code == 200

        connection = postgres_schema()
        with connection.cursor() as cur:
            cur.execute(
                "SELECT count(*) FROM rides WHERE ride_session_id=%s",
                (session_id,),
            )
            assert cur.fetchone()[0] == 0
            cur.execute(
                """
                SELECT user_id, device_id, ride_session_id, is_anonymized
                FROM sensor_logs WHERE event_uuid=%s
                """,
                (event_id,),
            )
            assert cur.fetchone() == (None, None, None, True)
        connection.close()


def test_retention_boundaries_preserve_exact_cutoff_and_finalize_exact_due(
    postgres_schema,
):
    now_ms = 2_000_000_000_000
    raw_cutoff = main.retention_cutoff_ms(now_ms, main.RAW_RETENTION_DAYS)
    now_datetime = datetime.fromtimestamp(now_ms / 1000)
    log_cutoff = now_datetime - timedelta(days=main.SYSTEM_LOG_RETENTION_DAYS)

    connection = postgres_schema()
    with connection.cursor() as cur:
        cur.execute(
            """
            INSERT INTO users (email, name, nickname, password_hash)
            VALUES ('retention@example.com','retention','retention','hash')
            RETURNING user_id
            """
        )
        user_id = cur.fetchone()[0]
        cur.execute(
            """
            INSERT INTO devices (user_id, device_id)
            VALUES (%s,'retention-device')
            """,
            (user_id,),
        )
        sensor_values = (
            0.0, 0.0, 9.8, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0
        )
        for captured_at in (raw_cutoff, raw_cutoff - 1):
            cur.execute(
                """
                INSERT INTO sensor_logs (
                    event_uuid, captured_at,
                    accel_x, accel_y, accel_z,
                    gyro_x, gyro_y, gyro_z,
                    geomagnetic_x, geomagnetic_y, geomagnetic_z
                ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                """,
                (str(uuid.uuid4()), captured_at, *sensor_values),
            )
        cur.execute(
            """
            INSERT INTO system_logs (log_type,message,created_at)
            VALUES ('TEST','exact',%s),('TEST','expired',%s)
            """,
            (log_cutoff, log_cutoff - timedelta(microseconds=1)),
        )
        for suffix, due_at in (
            ("due", now_datetime),
            ("future", now_datetime + timedelta(microseconds=1)),
        ):
            session_id = str(uuid.uuid4())
            cur.execute(
                """
                INSERT INTO rides (
                    ride_session_id, started_at, ended_at, elapsed_seconds,
                    total_distance_meters, average_speed_kmh, max_speed_kmh,
                    collected_frame_count, accident_count,
                    user_id, device_id, is_test_data,
                    deleted_at, deletion_due_at
                ) VALUES (%s,1,2,1,0,0,0,0,0,%s,'retention-device',FALSE,%s,%s)
                """,
                (
                    session_id,
                    user_id,
                    now_datetime - timedelta(days=7),
                    due_at,
                ),
            )
            if suffix == "due":
                due_session = session_id
            else:
                future_session = session_id
    connection.commit()
    connection.close()

    result = main.apply_retention_policy(now_ms, "MANUAL", None)
    assert result["status"] == "COMPLETED", result

    connection = postgres_schema()
    with connection.cursor() as cur:
        cur.execute("SELECT captured_at FROM sensor_logs ORDER BY captured_at")
        assert [row[0] for row in cur.fetchall()] == [raw_cutoff]
        cur.execute(
            "SELECT message FROM system_logs WHERE log_type='TEST' ORDER BY message"
        )
        assert [row[0] for row in cur.fetchall()] == ["exact"]
        cur.execute(
            "SELECT count(*) FROM rides WHERE ride_session_id=%s",
            (due_session,),
        )
        assert cur.fetchone()[0] == 0
        cur.execute(
            "SELECT count(*) FROM rides WHERE ride_session_id=%s",
            (future_session,),
        )
        assert cur.fetchone()[0] == 1
    connection.close()


def test_account_deletion_removes_image_and_invalidates_tokens(postgres_schema):
    session_id = str(uuid.uuid4())
    event_id = str(uuid.uuid4())
    image_buffer = io.BytesIO()
    Image.new("RGB", (2, 2), color=(32, 160, 96)).save(
        image_buffer, format="JPEG"
    )
    jpeg = image_buffer.getvalue()

    with TestClient(main.app) as client:
        tokens = _signup(client, long_term_optional=True)
        headers = {"Authorization": f"Bearer {tokens['access_token']}"}

        upload = client.post(
            "/upload-image",
            headers=headers,
            data={"event_uuid": event_id},
            files={"file": ("capture.jpg", jpeg, "image/jpeg")},
        )
        assert upload.status_code == 200, upload.text
        image_path = upload.json()["image_path"]
        image_file = main._stored_image_path(image_path)
        assert image_file is not None and image_file.is_file()

        sensor = _sensor_payload(session_id, event_id)
        sensor["image_path"] = image_path
        assert client.post("/sensor-log", json=sensor, headers=headers).status_code == 200
        assert client.get(
            f"/sensor-logs/{event_id}/image", headers=headers
        ).status_code == 200

        deleted = client.request(
            "DELETE",
            "/auth/me",
            headers=headers,
            json={"password": "password!"},
        )
        assert deleted.status_code == 200, deleted.text

        connection = postgres_schema()
        with connection.cursor() as cur:
            cur.execute("SELECT count(*) FROM users")
            assert cur.fetchone()[0] == 0
            cur.execute("SELECT count(*) FROM refresh_tokens")
            assert cur.fetchone()[0] == 0
            cur.execute(
                """
                SELECT user_id, device_id, ride_session_id, image_path, is_anonymized
                FROM sensor_logs WHERE event_uuid=%s
                """,
                (event_id,),
            )
            assert cur.fetchone() == (None, None, None, None, True)
            cur.execute(
                "SELECT count(*) FROM file_deletion_queue WHERE relative_path=%s",
                (image_path,),
            )
            assert cur.fetchone()[0] == 1
        connection.close()

        result = {"image_files_deleted": 0, "image_delete_failures": 0}
        connection = postgres_schema()
        main._process_file_deletion_queue(connection, result)
        connection.close()
        assert result == {"image_files_deleted": 1, "image_delete_failures": 0}
        assert not image_file.exists()

        refresh = client.post(
            "/auth/refresh",
            json={
                "refresh_token": tokens["refresh_token"],
                "device_id": "integration-device",
            },
        )
        assert refresh.status_code == 401
        assert client.get("/auth/me", headers=headers).status_code == 401
        assert client.get(
            f"/sensor-logs/{event_id}/image", headers=headers
        ).status_code == 401


@pytest.mark.parametrize("status", ["SAFE", "ACCIDENT", "NO_RESPONSE"])
def test_accident_images_show_remaining_context_without_changing_retention(
    postgres_schema, status,
):
    session_id = str(uuid.uuid4())
    accident_uuid = str(uuid.uuid4())
    detected_at = 2_000_000_000_000
    # No startup workers: only this test's isolated schema and temporary image root.
    client = TestClient(main.app)
    owner = _signup_and_headers(client)
    stranger = _signup_and_headers(client)
    admin_headers = _signup_and_headers(client)
    admin_id = client.get("/auth/me", headers=admin_headers).json()["user_id"]
    stranger_id = client.get("/auth/me", headers=stranger).json()["user_id"]
    image_ids = {}
    for label, offset, same_session in (
        ("too_old", -90_001, True), ("boundary", -90_000, True),
        ("recent", -1_000, True), ("exact", 0, True), ("after", 1, True),
        ("other_ride", -1_000, False), ("missing", -2_000, True),
        ("other_user", -3_000, True),
    ):
        event_uuid = str(uuid.uuid4())
        image_ids[label] = event_uuid
        relative_path = f"events/{event_uuid[:2]}/{event_uuid}.jpg"
        target = main.IMAGE_UPLOAD_DIR / relative_path
        target.parent.mkdir(parents=True, exist_ok=True)
        Image.new("RGB", (32, 32), "green").save(target, "JPEG")
        payload = _sensor_payload(session_id if same_session else str(uuid.uuid4()), event_uuid)
        payload.update(captured_at=detected_at+offset, image_path=relative_path)
        result = client.post("/sensor-log", headers=owner, json=payload)
        assert result.status_code == 200, result.text
        if label == "missing":
            target.unlink()

    result = client.post("/accident-event", headers=owner, json={
        "event_uuid": accident_uuid, "ride_session_id": session_id,
        "detected_at": detected_at, "latitude": 35.18, "longitude": 128.10,
        "accuracy": 5.0, "speed": 0.0,
        "accel_x": 0.0, "accel_y": 0.0, "accel_z": 9.8,
        "gyro_x": 0.0, "gyro_y": 0.0, "gyro_z": 0.0,
        "acceleration_magnitude": 9.8, "gyro_magnitude": 0.0,
        "confirmation_status": status, "is_false_positive": status == "SAFE",
    })
    assert result.status_code == 200, result.text

    connection = postgres_schema()
    with connection.cursor() as cur:
        cur.execute("UPDATE users SET role='ADMIN' WHERE user_id=%s", (admin_id,))
        # Simulate historical images with no blackbox evidence link.
        cur.execute("DELETE FROM accident_image_evidence")
        cur.execute("UPDATE sensor_logs SET image_delete_after=%s, image_retention_reason='AI_NO_DETECTION'",
                    (detected_at+120_000,))
        cur.execute("UPDATE sensor_logs SET user_id=%s WHERE event_uuid=%s",
                    (stranger_id, image_ids["other_user"]))
    connection.commit()
    connection.close()

    url = f"/accident-events/{accident_uuid}/images"
    assert client.get(url).status_code == 401
    assert client.get(url, headers=stranger).status_code == 403
    expected = [image_ids[k] for k in ("boundary", "recent", "exact")]
    for headers in (owner, admin_headers):
        response = client.get(url, headers=headers)
        assert response.status_code == 200, response.text
        body = response.json()
        assert body["lookback_seconds"] == 90
        assert [r["event_uuid"] for r in body["items"]] == expected
        first_image = body["items"][0]
        assert first_image["accel_x"] == 0.1
        assert first_image["accel_y"] == 0.2
        assert first_image["accel_z"] == 9.8
        assert first_image["gyro_x"] == 0.1
        assert first_image["gyro_y"] == 0.2
        assert first_image["gyro_z"] == 0.3
        assert first_image["geomagnetic_x"] == 1.0
        assert first_image["geomagnetic_y"] == 2.0
        assert first_image["geomagnetic_z"] == 3.0
        assert first_image["speed"] == 3.0
        assert first_image["sensor_samples"] == []
    for event_uuid in expected:
        response = client.get(f"/admin/sensor-logs/{event_uuid}/image", headers=admin_headers)
        assert response.status_code == 200, response.text
        Image.open(io.BytesIO(response.content)).verify()
        assert client.get(f"/sensor-logs/{event_uuid}/image", headers=owner).status_code == 200

    connection = postgres_schema()
    with connection.cursor() as cur:
        cur.execute("SELECT count(*) FROM accident_image_evidence")
        assert cur.fetchone()[0] == 0  # Viewing SAFE/legacy images must not extend retention.
        cur.execute("SELECT DISTINCT image_delete_after, image_retention_reason FROM sensor_logs")
        assert cur.fetchall() == [(detected_at+120_000, "AI_NO_DETECTION")]
    connection.close()


def test_accident_preserves_previous_ninety_seconds_and_empty_images_expire(
    postgres_schema,
):
    session_id = str(uuid.uuid4())
    accident_event_id = str(uuid.uuid4())
    accident_at = 2_000_000_000_000

    with TestClient(main.app) as client:
        headers = _signup_and_headers(client)
        image_paths = {}
        for label, captured_at in (
            ("too_old", accident_at - 90_001),
            ("boundary", accident_at - 90_000),
            ("recent", accident_at - 1_000),
        ):
            event_id = str(uuid.uuid4())
            relative_path = f"events/{event_id[:2]}/{event_id}.jpg"
            image_file = main.IMAGE_UPLOAD_DIR / relative_path
            image_file.parent.mkdir(parents=True, exist_ok=True)
            image_file.write_bytes(b"image")
            image_paths[label] = relative_path
            payload = _sensor_payload(session_id, event_id)
            payload["captured_at"] = captured_at
            payload["image_path"] = relative_path
            assert client.post(
                "/sensor-log", json=payload, headers=headers
            ).status_code == 200

        accident = client.post(
            "/accident-event",
            headers=headers,
            json={
                "event_uuid": accident_event_id,
                "ride_session_id": session_id,
                "detected_at": accident_at,
                "latitude": 35.18,
                "longitude": 128.10,
                "accuracy": 5.0,
                "speed": 0.0,
                "accel_x": 0.0,
                "accel_y": 0.0,
                "accel_z": 9.8,
                "gyro_x": 0.0,
                "gyro_y": 0.0,
                "gyro_z": 0.0,
                "acceleration_magnitude": 9.8,
                "gyro_magnitude": 0.0,
                "confirmation_status": "ACCIDENT",
                "is_false_positive": False,
            },
        )
        assert accident.status_code == 200, accident.text
        assert accident.json()["blackbox_image_count"] == 2

        images = client.get(
            f"/accident-events/{accident_event_id}/images", headers=headers
        )
        assert images.status_code == 200, images.text
        assert images.json()["lookback_seconds"] == 90
        assert [item["captured_at"] for item in images.json()["items"]] == [
            accident_at - 90_000,
            accident_at - 1_000,
        ]

        connection = postgres_schema()
        with connection.cursor() as cur:
            cur.execute(
                """
                UPDATE sensor_logs SET
                    image_delete_after=%s,
                    image_retention_reason='AI_NO_DETECTION'
                WHERE captured_at IN (%s,%s,%s)
                """,
                (
                    accident_at,
                    accident_at - 90_001,
                    accident_at - 90_000,
                    accident_at - 1_000,
                ),
            )
        connection.commit()
        connection.close()

        assert main.cleanup_expired_undetected_images(accident_at) == 1
        assert not (main.IMAGE_UPLOAD_DIR / image_paths["too_old"]).exists()
        assert (main.IMAGE_UPLOAD_DIR / image_paths["boundary"]).exists()
        assert (main.IMAGE_UPLOAD_DIR / image_paths["recent"]).exists()
