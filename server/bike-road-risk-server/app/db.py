import os
from pathlib import Path

import psycopg2

MIGRATIONS_DIR = Path(__file__).resolve().parent.parent / "migrations"


def apply_migrations(cur):
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS schema_migrations (
            version VARCHAR(100) PRIMARY KEY,
            applied_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
        )
        """
    )
    if not MIGRATIONS_DIR.exists():
        return
    for migration in sorted(MIGRATIONS_DIR.glob("*.sql")):
        cur.execute("SELECT 1 FROM schema_migrations WHERE version=%s", (migration.name,))
        if cur.fetchone() is not None:
            continue
        cur.execute(migration.read_text(encoding="utf-8"))
        cur.execute(
            "INSERT INTO schema_migrations (version) VALUES (%s)",
            (migration.name,)
        )


def get_connection():
    return psycopg2.connect(
        host=os.getenv("BIKE_DB_HOST", "localhost"),
        port=int(os.getenv("BIKE_DB_PORT", "5432")),
        dbname=os.getenv("BIKE_DB_NAME", "bike_db"),
        user=os.getenv("BIKE_DB_USER", "postgres"),
        password=os.getenv("BIKE_DB_PASSWORD", "aibike0000")
    )


def ensure_schema():
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS users (
                    user_id BIGSERIAL PRIMARY KEY,
                    email VARCHAR(320) NOT NULL UNIQUE,
                    name VARCHAR(100) NOT NULL,
                    nickname VARCHAR(100),
                    phone VARCHAR(30),
                    password_hash TEXT NOT NULL,
                    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
                    role VARCHAR(20) NOT NULL DEFAULT 'USER',
                    is_active BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            cur.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS phone VARCHAR(30)")
            cur.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS nickname VARCHAR(100)")
            cur.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'USER'")
            cur.execute("UPDATE users SET nickname=name WHERE nickname IS NULL")
            cur.execute("ALTER TABLE users ALTER COLUMN nickname SET NOT NULL")
            admin_emails = [
                value.strip().lower()
                for value in os.getenv("BIKE_ADMIN_EMAILS", "").split(",")
                if value.strip()
            ]
            if admin_emails:
                cur.execute(
                    "UPDATE users SET role='ADMIN' WHERE LOWER(email)=ANY(%s)",
                    (admin_emails,)
                )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS devices (
                    device_pk BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                    device_id VARCHAR(100) NOT NULL,
                    device_name VARCHAR(100),
                    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_seen_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (user_id, device_id)
                )
                """
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS refresh_tokens (
                    refresh_token_id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                    device_id VARCHAR(100) NOT NULL,
                    token_hash CHAR(64) NOT NULL UNIQUE,
                    expires_at BIGINT NOT NULL,
                    revoked_at TIMESTAMP WITHOUT TIME ZONE,
                    replaced_by_hash CHAR(64),
                    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            cur.execute(
                """
                CREATE INDEX IF NOT EXISTS index_refresh_tokens_user_device
                ON refresh_tokens (user_id, device_id)
                """
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS user_consents (
                    user_id BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
                    terms_required BOOLEAN NOT NULL,
                    privacy_required BOOLEAN NOT NULL,
                    collection_required BOOLEAN NOT NULL,
                    ai_research_optional BOOLEAN NOT NULL DEFAULT FALSE,
                    long_term_optional BOOLEAN NOT NULL DEFAULT FALSE,
                    consented_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            cur.execute(
                "ALTER TABLE user_consents ADD COLUMN IF NOT EXISTS policy_version VARCHAR(30) NOT NULL DEFAULT '2026-07-23'"
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS user_consent_history (
                    consent_history_id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
                    terms_required BOOLEAN NOT NULL,
                    privacy_required BOOLEAN NOT NULL,
                    collection_required BOOLEAN NOT NULL,
                    ai_research_optional BOOLEAN NOT NULL,
                    long_term_optional BOOLEAN NOT NULL,
                    policy_version VARCHAR(30) NOT NULL,
                    changed_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS system_logs (
                    system_log_id BIGSERIAL PRIMARY KEY,
                    log_type VARCHAR(50) NOT NULL,
                    message TEXT NOT NULL,
                    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            cur.execute(
                "CREATE INDEX IF NOT EXISTS index_system_logs_created_at ON system_logs(created_at)"
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS sensor_logs (
                    sensor_log_id BIGSERIAL PRIMARY KEY,
                    event_uuid VARCHAR(36) UNIQUE,
                    ride_session_id VARCHAR(36),
                    captured_at BIGINT NOT NULL,
                    latitude DOUBLE PRECISION,
                    longitude DOUBLE PRECISION,
                    accuracy DOUBLE PRECISION,
                    speed DOUBLE PRECISION,
                    direction DOUBLE PRECISION,
                    accel_x DOUBLE PRECISION NOT NULL,
                    accel_y DOUBLE PRECISION NOT NULL,
                    accel_z DOUBLE PRECISION NOT NULL,
                    gyro_x DOUBLE PRECISION NOT NULL,
                    gyro_y DOUBLE PRECISION NOT NULL,
                    gyro_z DOUBLE PRECISION NOT NULL,
                    geomagnetic_x DOUBLE PRECISION NOT NULL,
                    geomagnetic_y DOUBLE PRECISION NOT NULL,
                    geomagnetic_z DOUBLE PRECISION NOT NULL,
                    image_path TEXT,
                    sensor_samples JSONB NOT NULL DEFAULT '[]'::jsonb,
                    peak_sample_at BIGINT,
                    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            cur.execute(
                "ALTER TABLE sensor_logs ADD COLUMN IF NOT EXISTS peak_sample_at BIGINT"
            )
            cur.execute("ALTER TABLE sensor_logs ADD COLUMN IF NOT EXISTS event_uuid VARCHAR(36)")
            cur.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_sensor_logs_event_uuid "
                "ON sensor_logs(event_uuid) WHERE event_uuid IS NOT NULL"
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS accident_events (
                    accident_event_id BIGSERIAL PRIMARY KEY,
                    event_uuid VARCHAR(36) NOT NULL UNIQUE,
                    ride_session_id VARCHAR(36),
                    detected_at BIGINT NOT NULL,
                    latitude DOUBLE PRECISION,
                    longitude DOUBLE PRECISION,
                    accuracy DOUBLE PRECISION,
                    speed DOUBLE PRECISION,
                    accel_x DOUBLE PRECISION NOT NULL,
                    accel_y DOUBLE PRECISION NOT NULL,
                    accel_z DOUBLE PRECISION NOT NULL,
                    gyro_x DOUBLE PRECISION NOT NULL,
                    gyro_y DOUBLE PRECISION NOT NULL,
                    gyro_z DOUBLE PRECISION NOT NULL,
                    acceleration_magnitude DOUBLE PRECISION NOT NULL,
                    gyro_magnitude DOUBLE PRECISION NOT NULL,
                    confirmation_status VARCHAR(32) NOT NULL,
                    is_false_positive BOOLEAN NOT NULL,
                    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            cur.execute(
                """
                CREATE INDEX IF NOT EXISTS index_accident_events_ride_session_id
                ON accident_events (ride_session_id)
                """
            )
            cur.execute(
                """
                CREATE INDEX IF NOT EXISTS index_accident_events_detected_at
                ON accident_events (detected_at)
                """
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS rides (
                    ride_session_id VARCHAR(36) PRIMARY KEY,
                    started_at BIGINT NOT NULL,
                    ended_at BIGINT NOT NULL,
                    elapsed_seconds BIGINT NOT NULL,
                    total_distance_meters DOUBLE PRECISION NOT NULL,
                    average_speed_kmh DOUBLE PRECISION NOT NULL,
                    max_speed_kmh DOUBLE PRECISION NOT NULL,
                    collected_frame_count INTEGER NOT NULL,
                    accident_count INTEGER NOT NULL,
                    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            cur.execute(
                "CREATE INDEX IF NOT EXISTS index_rides_ended_at ON rides (ended_at)"
            )
            cur.execute("ALTER TABLE rides ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITHOUT TIME ZONE")
            cur.execute("ALTER TABLE rides ADD COLUMN IF NOT EXISTS deletion_due_at TIMESTAMP WITHOUT TIME ZONE")
            for table_name in ("rides", "sensor_logs", "accident_events"):
                cur.execute(
                    f"ALTER TABLE {table_name} ADD COLUMN IF NOT EXISTS user_id BIGINT "
                    "REFERENCES users(user_id) ON DELETE SET NULL"
                )
                cur.execute(
                    f"ALTER TABLE {table_name} ADD COLUMN IF NOT EXISTS device_id VARCHAR(100)"
                )
                cur.execute(
                    f"ALTER TABLE {table_name} ADD COLUMN IF NOT EXISTS "
                    "is_test_data BOOLEAN NOT NULL DEFAULT TRUE"
                )
                cur.execute(
                    f"ALTER TABLE {table_name} ADD COLUMN IF NOT EXISTS "
                    "is_anonymized BOOLEAN NOT NULL DEFAULT FALSE"
                )
            cur.execute(
                "CREATE INDEX IF NOT EXISTS index_rides_user_ended "
                "ON rides (user_id, ended_at DESC)"
            )
            cur.execute(
                "CREATE INDEX IF NOT EXISTS index_sensor_logs_user_ride "
                "ON sensor_logs (user_id, ride_session_id)"
            )
            cur.execute(
                "CREATE INDEX IF NOT EXISTS index_sensor_logs_user_ride_captured "
                "ON sensor_logs (user_id, ride_session_id, captured_at)"
            )
            cur.execute(
                "CREATE INDEX IF NOT EXISTS index_accident_events_user_ride "
                "ON accident_events (user_id, ride_session_id)"
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS hazard_zones (
                    zone_id UUID PRIMARY KEY,
                    title VARCHAR(200) NOT NULL,
                    road_damage_type VARCHAR(40) NOT NULL,
                    severity VARCHAR(20) NOT NULL,
                    latitude DOUBLE PRECISION NOT NULL,
                    longitude DOUBLE PRECISION NOT NULL,
                    radius_meters DOUBLE PRECISION NOT NULL,
                    detection_count INTEGER NOT NULL DEFAULT 1,
                    description TEXT NOT NULL DEFAULT '',
                    first_detected_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    last_detected_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    source_model VARCHAR(100),
                    confidence DOUBLE PRECISION,
                    status VARCHAR(20) NOT NULL DEFAULT 'CANDIDATE',
                    is_test_data BOOLEAN NOT NULL DEFAULT FALSE,
                    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT hazard_zones_severity_check
                        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
                    CONSTRAINT hazard_zones_status_check
                        CHECK (status IN (
                            'CANDIDATE', 'REVIEW_REQUIRED', 'CONFIRMED',
                            'STALE', 'ARCHIVED', 'REJECTED'
                        )),
                    CONSTRAINT hazard_zones_radius_check CHECK (radius_meters > 0),
                    CONSTRAINT hazard_zones_detection_count_check CHECK (detection_count > 0)
                )
                """
            )
            cur.execute(
                """
                CREATE INDEX IF NOT EXISTS index_hazard_zones_status_location
                ON hazard_zones (status, latitude, longitude)
                """
            )
            cur.execute(
                """
                CREATE INDEX IF NOT EXISTS index_hazard_zones_updated_at
                ON hazard_zones (updated_at DESC)
                """
            )
            cur.execute(
                """
                CREATE TABLE IF NOT EXISTS audit_logs (
                    audit_log_id BIGSERIAL PRIMARY KEY,
                    actor_user_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
                    action VARCHAR(80) NOT NULL,
                    target_type VARCHAR(50) NOT NULL,
                    target_id VARCHAR(100),
                    details JSONB NOT NULL DEFAULT '{}'::jsonb,
                    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """
            )
            cur.execute(
                "CREATE INDEX IF NOT EXISTS index_audit_logs_created_at ON audit_logs(created_at DESC)"
            )
            apply_migrations(cur)
        conn.commit()
    finally:
        conn.close()
