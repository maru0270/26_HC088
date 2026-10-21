from fastapi import FastAPI, HTTPException, Request, Query, Form, BackgroundTasks
from fastapi.responses import FileResponse, JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from app.db import ensure_schema, get_connection
from app.operations import (
    backup_status,
    configure_operational_logging,
    create_backup,
    operational_health,
)
from app.auth import (
    REFRESH_TOKEN_SECONDS,
    hash_password,
    issue_token_pair,
    optional_principal,
    password_email_configured,
    require_principal,
    send_temporary_password,
    token_hash,
    validate_password,
    verify_password,
)
from app.ai_service import (
    AI_CONFIG,
    ai_queue_status,
    ai_status,
    enqueue_ai_analysis,
    list_analysis_jobs,
    process_ai_job,
    process_pending_ai_jobs,
    recover_interrupted_ai_jobs,
)
from app.ai_annotation import render_detection_image
from app.hazard_rules import (
    AI_SENSOR_SUPPORT_DELTA,
    CLASS_RULES,
    SENSOR_EVIDENCE_MIN_SPEED_MPS,
    _refresh_zone,
    image_matched_impact,
    median_speed_around_impact,
    merge_eligible_detections,
    refresh_hazard_zone_lifecycle,
)
from app.user_safety import (
    VALID_FEEDBACK_TYPES,
    badge_progress,
    contribution_type_for_feedback,
)
from app.demo_monitor import (
    STATIC_DIR as DEMO_MONITOR_STATIC_DIR,
    monitor_events,
    monitor_status,
    record_demo_event,
    record_demo_event_safely,
    require_demo_access,
)
from fastapi import UploadFile, File
import os
import secrets
import uuid
from pathlib import Path
from psycopg2.extras import Json
import asyncio
import contextlib
import time
import logging
import json
import httpx2
import math
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo
from contextlib import contextmanager

app = FastAPI(title="BikeEyes Server")
logger = configure_operational_logging()
app.mount(
    "/demo-monitor-static",
    StaticFiles(directory=str(DEMO_MONITOR_STATIC_DIR)),
    name="demo-monitor-static",
)

IMAGE_UPLOAD_DIR = Path(os.getenv("BIKE_IMAGE_UPLOAD_DIR", r"D:\Hanium\uploads\images")).resolve()
MAX_IMAGE_BYTES = 10 * 1024 * 1024
ALLOW_ANONYMOUS_INGEST = os.getenv(
    "BIKE_ALLOW_ANONYMOUS_INGEST",
    "true" if os.getenv("BIKE_ENV", "development").lower() == "development" else "false"
).lower() == "true"
PUBLIC_DEMO_DATA_ENABLED = os.getenv(
    "BIKE_PUBLIC_DEMO_DATA_ENABLED", "false"
).lower() == "true"
PUBLIC_DEMO_BATCH_ID = os.getenv(
    "BIKE_PUBLIC_DEMO_BATCH_ID", ""
).strip()

IMAGE_UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
RIDE_RECOVERY_DAYS = 7
RAW_RETENTION_DAYS = 365
SYSTEM_LOG_RETENTION_DAYS = 90
RETENTION_INTERVAL_SECONDS = 24 * 60 * 60
ORPHAN_GRACE_SECONDS = 24 * 60 * 60
ORPHAN_SENSOR_GRACE_SECONDS = 24 * 60 * 60
RETENTION_LOCK_KEY = 0x42494B45
RETENTION_ENABLED = os.getenv(
    "BIKE_RETENTION_ENABLED",
    "true",
).lower() == "true"
BACKUP_INTERVAL_SECONDS = int(os.getenv("BIKE_BACKUP_INTERVAL_SECONDS", str(24 * 60 * 60)))
BACKUP_ENABLED = os.getenv("BIKE_BACKUP_ENABLED", "false").lower() == "true"
AI_QUEUE_INTERVAL_SECONDS = int(os.getenv("BIKE_AI_QUEUE_INTERVAL_SECONDS", "10"))
ACCIDENT_BLACKBOX_LOOKBACK_MS = 90 * 1000
KAKAO_REST_API_KEY = os.getenv("KAKAO_REST_API_KEY", "").strip()
KAKAO_PLACE_SEARCH_URL = "https://dapi.kakao.com/v2/local/search/keyword.json"
KAKAO_ADDRESS_SEARCH_URL = "https://dapi.kakao.com/v2/local/search/address.json"
KAKAO_COORD_TO_ADDRESS_URL = "https://dapi.kakao.com/v2/local/geo/coord2address.json"
KAKAO_BICYCLE_ROUTE_URL = "https://dapi.kakao.com/v2/routing/bicycle"
KAKAO_WALK_ROUTE_URL = "https://dapi.kakao.com/v2/routing/walk"
WALK_ROUTE_BICYCLE_SPEED_KMH = 15.0


@contextmanager
def db_transaction():
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            yield cur
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def retention_cutoff_ms(now_ms: int, retention_days: int) -> int:
    return now_ms - retention_days * 24 * 60 * 60 * 1000


def is_retention_expired(
    captured_at_ms: int,
    now_ms: int,
    retention_days: int,
) -> bool:
    return captured_at_ms < retention_cutoff_ms(now_ms, retention_days)


def is_due_at(due_at_ms: int, now_ms: int) -> bool:
    return due_at_ms <= now_ms


def track_orphan_sensor_session(cur, ride_session_id: str | None, now_ms: int | None = None):
    if not ride_session_id:
        return
    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)
    grace_ms = ORPHAN_SENSOR_GRACE_SECONDS * 1000
    cur.execute(
        """
        INSERT INTO orphan_sensor_sessions (
            ride_session_id, user_id, device_id,
            first_captured_at, last_captured_at, sensor_count,
            status, grace_due_at
        )
        SELECT
            s.ride_session_id, max(s.user_id), max(s.device_id),
            min(s.captured_at), max(s.captured_at), count(*)::integer,
            CASE WHEN max(s.captured_at) + %s <= %s
                 THEN 'ORPHAN' ELSE 'WAITING' END,
            max(s.captured_at) + %s
        FROM sensor_logs s
        WHERE s.ride_session_id=%s
          AND NOT EXISTS (
              SELECT 1 FROM rides r
              WHERE r.ride_session_id=s.ride_session_id
          )
        GROUP BY s.ride_session_id
        ON CONFLICT (ride_session_id) DO UPDATE SET
            user_id=EXCLUDED.user_id,
            device_id=EXCLUDED.device_id,
            first_captured_at=EXCLUDED.first_captured_at,
            last_captured_at=EXCLUDED.last_captured_at,
            sensor_count=EXCLUDED.sensor_count,
            status=EXCLUDED.status,
            grace_due_at=EXCLUDED.grace_due_at,
            updated_at=CURRENT_TIMESTAMP,
            resolved_at=NULL
        """,
        (grace_ms, now_ms, grace_ms, ride_session_id),
    )


def resolve_orphan_sensor_session(cur, ride_session_id: str):
    cur.execute(
        """
        UPDATE orphan_sensor_sessions
        SET status='RESOLVED', resolved_at=CURRENT_TIMESTAMP,
            updated_at=CURRENT_TIMESTAMP
        WHERE ride_session_id=%s AND status<>'RESOLVED'
        """,
        (ride_session_id,),
    )


def reconcile_orphan_sensor_sessions(cur, now_ms: int | None = None) -> dict:
    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)
    cur.execute(
        """
        UPDATE orphan_sensor_sessions o
        SET status='RESOLVED', resolved_at=CURRENT_TIMESTAMP,
            updated_at=CURRENT_TIMESTAMP
        WHERE status<>'RESOLVED'
          AND EXISTS (
              SELECT 1 FROM rides r
              WHERE r.ride_session_id=o.ride_session_id
          )
        """
    )
    resolved = cur.rowcount
    cur.execute(
        """
        UPDATE orphan_sensor_sessions
        SET status='ORPHAN', updated_at=CURRENT_TIMESTAMP
        WHERE status='WAITING' AND grace_due_at <= %s
        """,
        (now_ms,),
    )
    classified = cur.rowcount
    cur.execute(
        """
        SELECT
            count(*) FILTER (WHERE status='WAITING'),
            count(*) FILTER (WHERE status='ORPHAN'),
            count(*) FILTER (WHERE status='RESOLVED')
        FROM orphan_sensor_sessions
        """
    )
    waiting, orphan, resolved_total = cur.fetchone()
    return {
        "newly_resolved": resolved,
        "newly_classified": classified,
        "waiting": waiting,
        "orphan": orphan,
        "resolved": resolved_total,
    }


def write_system_log(
    request_id: str,
    level: str,
    endpoint: str,
    status_code: int,
    message: str,
    details: dict | None = None,
):
    try:
        with db_transaction() as cur:
            cur.execute(
                """
                INSERT INTO system_logs
                    (log_type, message, request_id, level, endpoint, status_code, details)
                VALUES (%s,%s,%s,%s,%s,%s,%s)
                """,
                (
                    "HTTP_REQUEST",
                    message[:2000],
                    request_id[:100],
                    level,
                    endpoint[:255],
                    status_code,
                    Json(details or {}),
                )
            )
    except Exception:
        logger.exception("failed to persist system log")


def http_error_level(status_code: int) -> str:
    return "ERROR" if status_code == 404 or status_code >= 500 else "WARN"


class KakaoPlaceItem(BaseModel):
    id: str
    name: str
    category_name: str = ""
    phone: str = ""
    address: str = ""
    road_address: str = ""
    longitude: float
    latitude: float
    distance_meters: int | None = None


class KakaoPlaceSearchResponse(BaseModel):
    query: str
    places: list[KakaoPlaceItem]
    has_more: bool = False


class KakaoCurrentLocationResponse(BaseModel):
    name: str
    address: str = ""
    road_address: str = ""
    longitude: float
    latitude: float


class BicycleRoutePoint(BaseModel):
    longitude: float
    latitude: float


class BicycleRouteStep(BaseModel):
    guidance: str
    distance_meters: int
    duration_seconds: int
    longitude: float
    latitude: float


class BicycleRouteResponse(BaseModel):
    route_type: str
    route_mode: str
    total_distance_meters: int
    total_duration_seconds: int
    landing_url: str = ""
    points: list[BicycleRoutePoint]
    steps: list[BicycleRouteStep]


class HazardZoneItem(BaseModel):
    zone_id: str
    title: str
    road_damage_type: str
    severity: str
    latitude: float
    longitude: float
    radius_meters: float
    detection_count: int
    description: str = ""
    last_detected_at: int
    status: str
    is_test_data: bool
    updated_at: int
    zone_category: str
    is_user_visible: bool
    expires_at: int | None = None


class HazardZoneListResponse(BaseModel):
    zones: list[HazardZoneItem]
    data_available: bool


class HazardZoneFeedbackRequest(BaseModel):
    feedback_type: str = Field(min_length=1, max_length=20)

    @field_validator("feedback_type")
    @classmethod
    def validate_feedback_type(cls, value):
        normalized = value.strip().upper()
        if normalized not in VALID_FEEDBACK_TYPES:
            raise ValueError("feedback_type must be DANGER, CAUTION, or RESOLVED")
        return normalized


class HazardZoneStatusUpdate(BaseModel):
    status: str = Field(min_length=1, max_length=20)


class HazardEvidenceLinkRequest(BaseModel):
    ai_detection_id: int | None = Field(default=None, ge=1)
    sensor_log_id: int | None = Field(default=None, ge=1)
    event_uuid: str | None = Field(default=None, min_length=36, max_length=36)
    source_model: str = Field(default="MANUAL", min_length=1, max_length=100)
    model_version: str | None = Field(default=None, max_length=100)
    confidence: float | None = Field(default=None, ge=0, le=1)
    distance_meters: float | None = Field(default=None, ge=0)
    details: dict = Field(default_factory=dict)

    @model_validator(mode="after")
    def validate_source(self):
        if self.ai_detection_id is None and self.sensor_log_id is None and self.event_uuid is None:
            raise ValueError("ai_detection_id, sensor_log_id, or event_uuid is required")
        if self.event_uuid is not None:
            uuid.UUID(self.event_uuid)
        return self


class HazardEvidenceAutoLinkRequest(BaseModel):
    radius_meters: float | None = Field(default=None, gt=0, le=500)
    include_test_data: bool = False
    max_items: int = Field(default=100, ge=1, le=1000)


class ReviewItemCandidateRequest(BaseModel):
    classes: list[str] = Field(min_length=1, max_length=10)

    @field_validator("classes")
    @classmethod
    def validate_classes(cls, values):
        normalized = []
        for value in values:
            name = str(value).strip().lower()
            if name not in CLASS_RULES:
                raise ValueError(f"unsupported review class: {name}")
            if name not in normalized:
                normalized.append(name)
        if not normalized:
            raise ValueError("at least one class is required")
        return normalized


@app.get("/map/places", response_model=KakaoPlaceSearchResponse)
async def search_kakao_places(
    q: str = Query(min_length=2, max_length=100),
    longitude: float | None = Query(default=None, ge=-180, le=180),
    latitude: float | None = Query(default=None, ge=-90, le=90),
    size: int = Query(default=10, ge=1, le=15),
):
    if not KAKAO_REST_API_KEY:
        raise HTTPException(
            status_code=503,
            detail="Kakao REST API key is not configured on the server",
        )

    params: dict[str, str | int | float] = {
        "query": q.strip(),
        "size": size,
        "page": 1,
    }
    if longitude is not None and latitude is not None:
        params.update({"x": longitude, "y": latitude, "sort": "distance"})

    headers = {"Authorization": f"KakaoAK {KAKAO_REST_API_KEY}"}
    try:
        async with httpx2.AsyncClient(timeout=5.0) as client:
            response = await client.get(
                KAKAO_PLACE_SEARCH_URL,
                params=params,
                headers=headers,
            )
            address_response = None
            if response.status_code == 200 and not response.json().get("documents"):
                address_response = await client.get(
                    KAKAO_ADDRESS_SEARCH_URL,
                    params={"query": q.strip(), "size": min(size, 30), "page": 1},
                    headers=headers,
                )
    except httpx2.RequestError as exc:
        logger.warning("Kakao place search network failure: %s", exc)
        raise HTTPException(
            status_code=502,
            detail="Kakao place search is temporarily unavailable",
        ) from exc

    if response.status_code != 200:
        logger.warning(
            "Kakao place search failed status=%s body=%s",
            response.status_code,
            response.text[:300],
        )
        if response.status_code in (401, 403):
            raise HTTPException(
                status_code=502,
                detail="Kakao place search authentication failed",
            )
        raise HTTPException(
            status_code=502,
            detail="Kakao place search request failed",
        )

    payload = response.json()
    if not payload.get("documents") and address_response is not None:
        if address_response.status_code != 200:
            logger.warning(
                "Kakao address fallback failed status=%s body=%s",
                address_response.status_code,
                address_response.text[:300],
            )
        else:
            address_payload = address_response.json()
            address_places = []
            for item in address_payload.get("documents", []):
                road_address = item.get("road_address") or {}
                address = item.get("address") or {}
                display_name = (
                    road_address.get("building_name")
                    or road_address.get("address_name")
                    or address.get("address_name")
                    or item.get("address_name")
                    or q.strip()
                )
                longitude = item.get("x")
                latitude = item.get("y")
                if longitude and latitude:
                    address_places.append(
                        KakaoPlaceItem(
                            id=f"address:{longitude}:{latitude}",
                            name=display_name,
                            category_name="주소",
                            address=address.get("address_name", item.get("address_name", "")),
                            road_address=road_address.get("address_name", ""),
                            longitude=float(longitude),
                            latitude=float(latitude),
                        )
                    )
            return KakaoPlaceSearchResponse(
                query=q.strip(),
                places=address_places,
                has_more=not address_payload.get("meta", {}).get("is_end", True),
            )
    places = [
        KakaoPlaceItem(
            id=str(item.get("id", "")),
            name=item.get("place_name", ""),
            category_name=item.get("category_name", ""),
            phone=item.get("phone", ""),
            address=item.get("address_name", ""),
            road_address=item.get("road_address_name", ""),
            longitude=float(item["x"]),
            latitude=float(item["y"]),
            distance_meters=int(item["distance"]) if item.get("distance") else None,
        )
        for item in payload.get("documents", [])
        if item.get("x") and item.get("y")
    ]
    return KakaoPlaceSearchResponse(
        query=q.strip(),
        places=places,
        has_more=not payload.get("meta", {}).get("is_end", True),
    )


@app.get("/map/current-location", response_model=KakaoCurrentLocationResponse)
async def get_kakao_current_location(
    longitude: float = Query(ge=-180, le=180),
    latitude: float = Query(ge=-90, le=90),
):
    if not KAKAO_REST_API_KEY:
        raise HTTPException(
            status_code=503,
            detail="Kakao REST API key is not configured on the server",
        )

    headers = {"Authorization": f"KakaoAK {KAKAO_REST_API_KEY}"}
    try:
        async with httpx2.AsyncClient(timeout=5.0) as client:
            response = await client.get(
                KAKAO_COORD_TO_ADDRESS_URL,
                params={"x": longitude, "y": latitude, "input_coord": "WGS84"},
                headers=headers,
            )
    except httpx2.RequestError as exc:
        logger.warning("Kakao reverse geocoding network failure: %s", exc)
        raise HTTPException(
            status_code=502,
            detail="Kakao reverse geocoding is temporarily unavailable",
        ) from exc

    if response.status_code != 200:
        logger.warning(
            "Kakao reverse geocoding failed status=%s body=%s",
            response.status_code,
            response.text[:300],
        )
        raise HTTPException(status_code=502, detail="Kakao reverse geocoding request failed")

    documents = response.json().get("documents", [])
    if not documents:
        return KakaoCurrentLocationResponse(
            name="현재 위치",
            longitude=longitude,
            latitude=latitude,
        )

    document = documents[0]
    road_address = document.get("road_address") or {}
    address = document.get("address") or {}
    road_address_name = road_address.get("address_name", "")
    address_name = address.get("address_name", "")
    display_name = (
        road_address.get("building_name")
        or road_address_name
        or address_name
        or "현재 위치"
    )
    return KakaoCurrentLocationResponse(
        name=display_name,
        address=address_name,
        road_address=road_address_name,
        longitude=longitude,
        latitude=latitude,
    )


@app.get("/map/bicycle-route", response_model=BicycleRouteResponse)
async def get_kakao_bicycle_route(
    start_longitude: float = Query(ge=-180, le=180),
    start_latitude: float = Query(ge=-90, le=90),
    end_longitude: float = Query(ge=-180, le=180),
    end_latitude: float = Query(ge=-90, le=90),
    destination_name: str = Query(default="도착", max_length=100),
    route_mode: str = Query(default="BIKE_ONLY"),
):
    if not KAKAO_REST_API_KEY:
        raise HTTPException(
            status_code=503,
            detail="Kakao REST API key is not configured on the server",
        )
    normalized_mode = route_mode.upper()
    if normalized_mode not in {"BIKE_ONLY", "SHORTEST", "ACCESSIBLE"}:
        raise HTTPException(status_code=422, detail="unsupported bicycle route mode")

    params = {
        "start_x": start_longitude,
        "start_y": start_latitude,
        "end_x": end_longitude,
        "end_y": end_latitude,
        "s_name": "현재 위치",
        "e_name": destination_name.strip() or "도착",
        "input_coord": "WGS84",
        "output_coord": "WGS84",
        "route_mode": normalized_mode,
    }
    try:
        async with httpx2.AsyncClient(timeout=10.0) as client:
            response = await client.get(
                KAKAO_BICYCLE_ROUTE_URL,
                params=params,
                headers={"Authorization": f"KakaoAK {KAKAO_REST_API_KEY}"},
            )
    except httpx2.RequestError as exc:
        logger.warning("Kakao bicycle route network failure: %s", exc)
        raise HTTPException(
            status_code=502,
            detail="Kakao bicycle route is temporarily unavailable",
        ) from exc

    if response.status_code != 200:
        logger.warning(
            "Kakao bicycle route failed status=%s body=%s",
            response.status_code,
            response.text[:500],
        )
        if response.status_code in (401, 403):
            raise HTTPException(status_code=502, detail="Kakao route authentication failed")
        raise HTTPException(status_code=502, detail="Kakao bicycle route request failed")

    payload = response.json()
    status = payload.get("status")
    if status != "OK" or not payload.get("route"):
        status_messages = {
            "SAME_POINT": "출발지와 목적지가 같습니다",
            "START_LINK_NOT_FOUND": "출발지 주변의 자전거 경로를 찾을 수 없습니다",
            "END_LINK_NOT_FOUND": "목적지 주변의 자전거 경로를 찾을 수 없습니다",
            "TOO_FAR_AWAY": "출발지와 목적지가 너무 멉니다",
            "ROUTE_RESULT_NOT_FOUND": "자전거 경로를 찾을 수 없습니다",
        }
        raise HTTPException(
            status_code=404,
            detail=status_messages.get(status, "자전거 경로를 찾을 수 없습니다"),
        )

    route = payload["route"]
    properties = route.get("properties", {})
    points: list[BicycleRoutePoint] = []
    steps: list[BicycleRouteStep] = []
    for leg in route.get("legs", []):
        for step in leg.get("steps", []):
            step_properties = step.get("properties", {})
            path_points = step.get("path", {}).get("points", [])
            for coordinate in path_points:
                if isinstance(coordinate, list) and len(coordinate) >= 2:
                    point = BicycleRoutePoint(
                        longitude=float(coordinate[0]),
                        latitude=float(coordinate[1]),
                    )
                    if not points or (
                        points[-1].longitude != point.longitude
                        or points[-1].latitude != point.latitude
                    ):
                        points.append(point)
            if step_properties.get("x") is not None and step_properties.get("y") is not None:
                steps.append(
                    BicycleRouteStep(
                        guidance=str(step_properties.get("guidance", "경로를 따라 이동")),
                        distance_meters=int(step_properties.get("distance", 0)),
                        duration_seconds=int(step_properties.get("time", 0)),
                        longitude=float(step_properties["x"]),
                        latitude=float(step_properties["y"]),
                    )
                )
    if len(points) < 2:
        raise HTTPException(status_code=502, detail="Kakao bicycle route has no geometry")

    return BicycleRouteResponse(
        route_type="BICYCLE",
        route_mode=normalized_mode,
        total_distance_meters=int(properties.get("totalDistance", 0)),
        total_duration_seconds=int(properties.get("totalTime", 0)),
        landing_url=str(properties.get("landingUrl", "")),
        points=points,
        steps=steps,
    )


@app.get("/hazard-zones", response_model=HazardZoneListResponse)
def list_hazard_zones(
    include_candidates: bool = Query(default=False),
    include_test_data: bool = Query(default=False),
):
    # General riders only receive administrator-confirmed zones. The legacy
    # query flag remains accepted for backward compatibility.
    statuses = ["CONFIRMED"]
    with db_transaction() as cur:
        cur.execute(
            """
            SELECT zone_id::text, title, road_damage_type, severity,
                   latitude, longitude, radius_meters, detection_count,
                   description,
                   (EXTRACT(EPOCH FROM last_detected_at) * 1000)::BIGINT,
                   status, is_test_data,
                   (EXTRACT(EPOCH FROM updated_at) * 1000)::BIGINT,
                   zone_category,is_user_visible,
                   (EXTRACT(EPOCH FROM expires_at) * 1000)::BIGINT
            FROM hazard_zones
            WHERE status = ANY(%s)
              AND zone_category='USER_HAZARD'
              AND is_user_visible=TRUE
              AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)
              AND (
                    %s
                    OR is_test_data = FALSE
                    OR (
                        %s
                        AND NULLIF(%s, '') IS NOT NULL
                        AND demo_batch_id=%s
                    )
                  )
            ORDER BY severity DESC, detection_count DESC, updated_at DESC
            """,
            (
                statuses,
                include_test_data,
                PUBLIC_DEMO_DATA_ENABLED,
                PUBLIC_DEMO_BATCH_ID,
                PUBLIC_DEMO_BATCH_ID,
            ),
        )
        rows = cur.fetchall()
    zones = [
        HazardZoneItem(
            zone_id=row[0],
            title=row[1],
            road_damage_type=row[2],
            severity=row[3],
            latitude=row[4],
            longitude=row[5],
            radius_meters=row[6],
            detection_count=row[7],
            description=row[8],
            last_detected_at=row[9],
            status=row[10],
            is_test_data=row[11],
            updated_at=row[12],
            zone_category=row[13],
            is_user_visible=row[14],
            expires_at=row[15],
        )
        for row in rows
    ]
    return HazardZoneListResponse(zones=zones, data_available=bool(zones))


def _parse_zone_id(zone_id: str) -> str:
    try:
        return str(uuid.UUID(zone_id))
    except ValueError:
        raise HTTPException(status_code=422, detail="invalid zone_id")


@app.get("/hazard-zones/{zone_id}/representative-image")
def get_hazard_zone_representative_image(zone_id: str, request: Request):
    """Return the highest-confidence valid evidence image for a public zone.

    The confidence score is used only to choose the representative evidence.
    The returned rider-facing image deliberately omits numeric confidence labels.
    """
    require_principal(request)
    normalized_zone_id = _parse_zone_id(zone_id)
    candidates: list[tuple[str, str, int, list[dict], float]] = []
    with db_transaction() as cur:
        _require_feedback_eligible_zone(cur, normalized_zone_id)
        cur.execute(
            """
            SELECT COALESCE(e.event_uuid, s.event_uuid), s.image_path,
                   j.ai_analysis_job_id
            FROM hazard_zone_evidence e
            JOIN sensor_logs s ON s.sensor_log_id=e.sensor_log_id
            JOIN ai_analysis_jobs j ON j.sensor_log_id=s.sensor_log_id
            WHERE e.zone_id=%s AND s.image_path IS NOT NULL
            ORDER BY COALESCE(e.confidence, 0) DESC,
                     e.linked_at DESC,
                     e.hazard_zone_evidence_id DESC
            LIMIT 50
            """,
            (normalized_zone_id,),
        )
        evidence_rows = cur.fetchall()
        for event_uuid, image_path, job_id in evidence_rows:
            cur.execute(
                """
                SELECT ai_detection_id,stage,model_version,class_name,confidence,
                       bbox_xyxy,polygon
                FROM ai_detections
                WHERE ai_analysis_job_id=%s
                ORDER BY confidence DESC,ai_detection_id
                """,
                (job_id,),
            )
            eligible = merge_eligible_detections(cur.fetchall())
            if not eligible:
                continue
            detections = [
                {
                    "class_name": item[3],
                    "confidence": item[4],
                    "bbox_xyxy": item[5],
                    "polygon": item[6],
                    "stage": item[1],
                }
                for item in eligible
            ]
            candidates.append(
                (
                    str(event_uuid),
                    image_path,
                    int(job_id),
                    detections,
                    max(float(item[4]) for item in eligible),
                )
            )

    if not candidates:
        raise HTTPException(status_code=404, detail="representative evidence image not found")
    event_uuid, image_path, _job_id, detections, _confidence = max(
        candidates,
        key=lambda item: item[4],
    )
    image_file = _first_existing_stored_image_path(image_path)
    if image_file is None:
        raise HTTPException(status_code=404, detail="representative image file has expired")
    content = render_detection_image(image_file, detections, show_confidence=False)
    return Response(
        content=content,
        media_type="image/jpeg",
        headers={
            "Cache-Control": "private, max-age=3600",
            "Content-Disposition": f'inline; filename="hazard-{normalized_zone_id}-{event_uuid}.jpg"',
        },
    )


def _require_feedback_eligible_zone(cur, zone_id: str, *, lock: bool = False):
    cur.execute(
        f"""
        SELECT zone_id::text,title
        FROM hazard_zones
        WHERE zone_id=%s
          AND status='CONFIRMED'
          AND zone_category='USER_HAZARD'
          AND is_user_visible=TRUE
          AND (
                is_test_data=FALSE
                OR (
                    %s
                    AND NULLIF(%s, '') IS NOT NULL
                    AND demo_batch_id=%s
                )
              )
          AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)
        {'FOR UPDATE' if lock else ''}
        """,
        (
            zone_id,
            PUBLIC_DEMO_DATA_ENABLED,
            PUBLIC_DEMO_BATCH_ID,
            PUBLIC_DEMO_BATCH_ID,
        ),
    )
    zone = cur.fetchone()
    if zone is None:
        raise HTTPException(
            status_code=404,
            detail="feedback is available only for visible confirmed hazard zones",
        )
    return zone


def _hazard_feedback_payload(cur, zone_id: str, user_id: int) -> dict:
    cur.execute(
        """
        SELECT feedback_type,created_at,updated_at
        FROM hazard_zone_feedback
        WHERE zone_id=%s AND user_id=%s
        """,
        (zone_id, user_id),
    )
    mine = cur.fetchone()
    cur.execute(
        """
        SELECT
            COUNT(*) FILTER (WHERE feedback_type='DANGER'),
            COUNT(*) FILTER (WHERE feedback_type='CAUTION'),
            COUNT(*) FILTER (WHERE feedback_type='RESOLVED'),
            COUNT(*)
        FROM hazard_zone_feedback
        WHERE zone_id=%s
        """,
        (zone_id,),
    )
    counts = cur.fetchone()
    return {
        "zone_id": zone_id,
        "can_submit": True,
        "my_feedback": None if mine is None else {
            "feedback_type": mine[0],
            "created_at": mine[1].isoformat(),
            "updated_at": mine[2].isoformat(),
        },
        "aggregate": {
            "danger": counts[0],
            "caution": counts[1],
            "resolved": counts[2],
            "total": counts[3],
        },
    }


@app.get("/hazard-zones/{zone_id}/feedback")
def get_hazard_zone_feedback(zone_id: str, request: Request):
    principal = require_principal(request)
    normalized_zone_id = _parse_zone_id(zone_id)
    with db_transaction() as cur:
        _require_feedback_eligible_zone(cur, normalized_zone_id)
        return _hazard_feedback_payload(cur, normalized_zone_id, principal.user_id)


@app.put("/hazard-zones/{zone_id}/feedback")
def put_hazard_zone_feedback(
    zone_id: str,
    body: HazardZoneFeedbackRequest,
    request: Request,
):
    principal = require_principal(request)
    normalized_zone_id = _parse_zone_id(zone_id)
    with db_transaction() as cur:
        _require_feedback_eligible_zone(cur, normalized_zone_id, lock=True)
        cur.execute(
            """
            SELECT feedback_id,feedback_type
            FROM hazard_zone_feedback
            WHERE zone_id=%s AND user_id=%s
            FOR UPDATE
            """,
            (normalized_zone_id, principal.user_id),
        )
        existing = cur.fetchone()
        changed = existing is None or existing[1] != body.feedback_type
        if existing is None:
            cur.execute(
                """
                INSERT INTO hazard_zone_feedback(zone_id,user_id,feedback_type)
                VALUES (%s,%s,%s)
                RETURNING feedback_id
                """,
                (normalized_zone_id, principal.user_id, body.feedback_type),
            )
            feedback_id = cur.fetchone()[0]
        else:
            feedback_id = existing[0]
            if changed:
                cur.execute(
                    """
                    UPDATE hazard_zone_feedback
                    SET feedback_type=%s,
                        updated_at=timezone('Asia/Seoul', CURRENT_TIMESTAMP)
                    WHERE feedback_id=%s
                    """,
                    (body.feedback_type, feedback_id),
                )

        contribution_created = False
        if changed:
            contribution_type = contribution_type_for_feedback(body.feedback_type)
            cur.execute(
                """
                INSERT INTO user_safety_contributions(
                    user_id,zone_id,contribution_type,source_feedback_id
                ) VALUES (%s,%s,%s,%s)
                ON CONFLICT (user_id,zone_id,contribution_type) DO NOTHING
                RETURNING contribution_id
                """,
                (
                    principal.user_id,
                    normalized_zone_id,
                    contribution_type,
                    feedback_id,
                ),
            )
            contribution_created = cur.fetchone() is not None
            audit(
                cur,
                principal.user_id,
                "HAZARD_ZONE_FEEDBACK_UPDATED",
                "HAZARD_ZONE",
                normalized_zone_id,
                {
                    "feedback_type": body.feedback_type,
                    "contribution_type": contribution_type,
                    "contribution_created": contribution_created,
                },
            )

        payload = _hazard_feedback_payload(
            cur, normalized_zone_id, principal.user_id,
        )
        payload.update({
            "changed": changed,
            "contribution_created": contribution_created,
        })
        return payload


@app.get("/auth/me/safety-contributions")
def my_safety_contributions(request: Request):
    principal = require_principal(request)
    with db_transaction() as cur:
        cur.execute(
            """
            SELECT
                COUNT(*) FILTER (WHERE contribution_type='CONFIRMATION'),
                COUNT(*) FILTER (WHERE contribution_type='ESTABLISHMENT'),
                COUNT(*) FILTER (WHERE contribution_type='RESOLUTION'),
                COUNT(*)
            FROM user_safety_contributions
            WHERE user_id=%s
            """,
            (principal.user_id,),
        )
        counts = cur.fetchone()
        cur.execute(
            """
            SELECT c.contribution_type,c.zone_id::text,z.title,c.created_at
            FROM user_safety_contributions c
            JOIN hazard_zones z ON z.zone_id=c.zone_id
            WHERE c.user_id=%s
            ORDER BY c.created_at DESC,c.contribution_id DESC
            LIMIT 20
            """,
            (principal.user_id,),
        )
        recent = cur.fetchall()
    total = counts[3]
    return {
        "confirmation_count": counts[0],
        "establishment_count": counts[1],
        "resolution_count": counts[2],
        "total_count": total,
        "badge": badge_progress(total),
        "recent": [
            {
                "contribution_type": row[0],
                "zone_id": row[1],
                "zone_title": row[2],
                "created_at": row[3].isoformat(),
            }
            for row in recent
        ],
    }


@app.get("/safety-contributions/weekly-ranking")
def weekly_safety_contribution_ranking(request: Request):
    require_principal(request)
    with db_transaction() as cur:
        cur.execute(
            """
            WITH period AS (
                SELECT
                    date_trunc('week', timezone('Asia/Seoul', CURRENT_TIMESTAMP)) AS starts_at,
                    date_trunc('week', timezone('Asia/Seoul', CURRENT_TIMESTAMP))
                        + INTERVAL '7 days' AS ends_at
            ), ranked AS (
                SELECT c.user_id,u.nickname,COUNT(*)::INTEGER AS contribution_count,
                       MAX(c.created_at) AS latest_contribution_at
                FROM user_safety_contributions c
                JOIN users u ON u.user_id=c.user_id AND u.is_active=TRUE
                CROSS JOIN period p
                WHERE c.created_at>=p.starts_at AND c.created_at<p.ends_at
                GROUP BY c.user_id,u.nickname
            )
            SELECT r.user_id,r.nickname,r.contribution_count,
                   r.latest_contribution_at,p.starts_at,p.ends_at
            FROM ranked r CROSS JOIN period p
            ORDER BY r.contribution_count DESC,r.latest_contribution_at ASC,r.user_id ASC
            LIMIT 5
            """
        )
        rows = cur.fetchall()
        if rows:
            starts_at, ends_at = rows[0][4], rows[0][5]
        else:
            cur.execute(
                """
                SELECT date_trunc('week', timezone('Asia/Seoul', CURRENT_TIMESTAMP)),
                       date_trunc('week', timezone('Asia/Seoul', CURRENT_TIMESTAMP))
                           + INTERVAL '7 days'
                """
            )
            starts_at, ends_at = cur.fetchone()
    return {
        "timezone": "Asia/Seoul",
        "starts_at": starts_at.isoformat(),
        "ends_at": ends_at.isoformat(),
        "items": [
            {
                "rank": index + 1,
                "nickname": row[1],
                "contribution_count": row[2],
            }
            for index, row in enumerate(rows)
        ],
    }


@app.get("/map/walking-bike-route", response_model=BicycleRouteResponse)
async def get_kakao_walking_bike_route(
    start_longitude: float = Query(ge=-180, le=180),
    start_latitude: float = Query(ge=-90, le=90),
    end_longitude: float = Query(ge=-180, le=180),
    end_latitude: float = Query(ge=-90, le=90),
    destination_name: str = Query(default="목적지", max_length=100),
    route_mode: str = Query(default="BROAD_FIRST"),
):
    """Return a walking network route with ETA recalculated for bicycle speed.

    This route can contain sidewalks, stairs, or bicycle-restricted segments.
    The client must instruct the rider to dismount where riding is not allowed.
    """
    if not KAKAO_REST_API_KEY:
        raise HTTPException(
            status_code=503,
            detail="Kakao REST API key is not configured on the server",
        )
    normalized_mode = route_mode.upper()
    if normalized_mode not in {"BROAD_FIRST", "SHORTEST", "ACCESSIBLE"}:
        raise HTTPException(status_code=422, detail="unsupported walking route mode")

    params = {
        "start_x": start_longitude,
        "start_y": start_latitude,
        "end_x": end_longitude,
        "end_y": end_latitude,
        "s_name": "현재 위치",
        "e_name": destination_name.strip() or "목적지",
        "input_coord": "WGS84",
        "output_coord": "WGS84",
        "route_mode": normalized_mode,
    }
    try:
        async with httpx2.AsyncClient(timeout=10.0) as client:
            response = await client.get(
                KAKAO_WALK_ROUTE_URL,
                params=params,
                headers={"Authorization": f"KakaoAK {KAKAO_REST_API_KEY}"},
            )
    except httpx2.RequestError as exc:
        logger.warning("Kakao walking route network failure: %s", exc)
        raise HTTPException(
            status_code=502,
            detail="Kakao walking route is temporarily unavailable",
        ) from exc

    if response.status_code != 200:
        logger.warning(
            "Kakao walking route failed status=%s body=%s",
            response.status_code,
            response.text[:500],
        )
        if response.status_code in (401, 403):
            raise HTTPException(status_code=502, detail="Kakao route authentication failed")
        raise HTTPException(status_code=502, detail="Kakao walking route request failed")

    payload = response.json()
    status = payload.get("status")
    if status != "OK" or not payload.get("route"):
        raise HTTPException(status_code=404, detail="보행 기반 경로를 찾을 수 없습니다")

    route = payload["route"]
    properties = route.get("properties", {})
    points: list[BicycleRoutePoint] = []
    steps: list[BicycleRouteStep] = []
    meters_per_second = WALK_ROUTE_BICYCLE_SPEED_KMH * 1000.0 / 3600.0
    for leg in route.get("legs", []):
        for step in leg.get("steps", []):
            step_properties = step.get("properties", {})
            for coordinate in step.get("path", {}).get("points", []):
                if isinstance(coordinate, list) and len(coordinate) >= 2:
                    point = BicycleRoutePoint(
                        longitude=float(coordinate[0]),
                        latitude=float(coordinate[1]),
                    )
                    if not points or (
                        points[-1].longitude != point.longitude
                        or points[-1].latitude != point.latitude
                    ):
                        points.append(point)
            if step_properties.get("x") is not None and step_properties.get("y") is not None:
                distance = int(step_properties.get("distance", 0))
                steps.append(
                    BicycleRouteStep(
                        guidance=str(step_properties.get("guidance", "경로를 따라 이동")),
                        distance_meters=distance,
                        duration_seconds=(
                            max(1, math.ceil(distance / meters_per_second))
                            if distance > 0 else 0
                        ),
                        longitude=float(step_properties["x"]),
                        latitude=float(step_properties["y"]),
                    )
                )
    if len(points) < 2:
        raise HTTPException(status_code=502, detail="Kakao walking route has no geometry")

    total_distance = int(properties.get("totalDistance", 0))
    return BicycleRouteResponse(
        route_type="WALKING_BIKE",
        route_mode=normalized_mode,
        total_distance_meters=total_distance,
        total_duration_seconds=max(1, math.ceil(total_distance / meters_per_second)),
        landing_url=str(properties.get("landingUrl", "")),
        points=points,
        steps=steps,
    )


@app.middleware("http")
async def request_trace_middleware(request: Request, call_next):
    supplied_id = request.headers.get("X-Request-ID", "").strip()
    request_id = supplied_id[:100] if supplied_id else str(uuid.uuid4())
    request.state.request_id = request_id
    started = time.monotonic()
    response = await call_next(request)
    elapsed_ms = round((time.monotonic() - started) * 1000, 2)
    response.headers["X-Request-ID"] = request_id
    log_record = {
        "request_id": request_id,
        "method": request.method,
        "endpoint": request.url.path,
        "status_code": response.status_code,
        "elapsed_ms": elapsed_ms,
    }
    logger.info(json.dumps(log_record, ensure_ascii=False))
    if response.status_code >= 400:
        await asyncio.to_thread(
            write_system_log,
            request_id,
            http_error_level(response.status_code),
            request.url.path,
            response.status_code,
            f"{request.method} {request.url.path} returned {response.status_code}",
            {"elapsed_ms": elapsed_ms},
        )
    return response


@app.exception_handler(Exception)
async def unhandled_exception_handler(request: Request, exception: Exception):
    request_id = getattr(request.state, "request_id", str(uuid.uuid4()))
    logger.exception("unhandled request error request_id=%s", request_id)
    await asyncio.to_thread(
        write_system_log,
        request_id,
        "ERROR",
        request.url.path,
        500,
        "Unhandled server error",
        {"exception_type": exception.__class__.__name__},
    )
    return JSONResponse(
        status_code=500,
        content={"detail": "internal server error", "request_id": request_id},
        headers={"X-Request-ID": request_id},
    )


def require_admin(request: Request):
    principal = require_principal(request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT role FROM users WHERE user_id=%s AND is_active=TRUE", (principal.user_id,))
            row = cur.fetchone()
    finally:
        conn.close()
    if row is None or row[0] != "ADMIN":
        raise HTTPException(status_code=403, detail="administrator access required")
    return principal


def audit(cur, actor_user_id: int | None, action: str, target_type: str,
          target_id: str | None, details: dict | None = None):
    cur.execute(
        """
        INSERT INTO audit_logs (actor_user_id, action, target_type, target_id, details)
        VALUES (%s,%s,%s,%s,%s)
        """,
        (actor_user_id, action, target_type, target_id, Json(details or {}))
    )


def finalize_ride_deletion(cur, ride_session_id: str, user_id: int):
    cur.execute(
        """
        UPDATE sensor_logs SET
            user_id=NULL, device_id=NULL, ride_session_id=NULL,
            latitude=NULL, longitude=NULL, accuracy=NULL,
            speed=NULL, direction=NULL, is_anonymized=TRUE,
            sensor_samples=COALESCE((
                SELECT jsonb_agg(
                    sample - 'latitude' - 'longitude' - 'accuracy' - 'speed' - 'direction'
                )
                FROM jsonb_array_elements(sensor_samples) AS sample
            ), '[]'::jsonb)
        WHERE ride_session_id=%s AND user_id=%s
        """,
        (ride_session_id, user_id)
    )
    retained_sensor_count = cur.rowcount
    cur.execute(
        """
        UPDATE accident_events SET
            user_id=NULL, device_id=NULL, ride_session_id=NULL,
            latitude=NULL, longitude=NULL, accuracy=NULL, speed=NULL,
            is_anonymized=TRUE
        WHERE ride_session_id=%s AND user_id=%s
        """,
        (ride_session_id, user_id)
    )
    retained_accident_count = cur.rowcount
    cur.execute(
        "DELETE FROM rides WHERE ride_session_id=%s AND user_id=%s",
        (ride_session_id, user_id)
    )
    return retained_sensor_count, retained_accident_count, cur.rowcount


def _stored_image_path(relative_path: str | None) -> Path | None:
    if not relative_path:
        return None
    supplied = Path(relative_path)
    candidate = supplied.resolve() if supplied.is_absolute() else (IMAGE_UPLOAD_DIR / supplied).resolve()
    try:
        candidate.relative_to(IMAGE_UPLOAD_DIR)
    except ValueError:
        return None
    return candidate


def _first_existing_stored_image_path(*paths: str | None) -> Path | None:
    """Return the first safe, existing image path in priority order."""
    for path in paths:
        candidate = _stored_image_path(path)
        if candidate is not None and candidate.is_file():
            return candidate
    return None


def _image_storage_key(image_path: str | None) -> str | None:
    candidate = _stored_image_path(image_path)
    if candidate is None:
        return None
    return candidate.relative_to(IMAGE_UPLOAD_DIR).as_posix()


def _enqueue_file_deletion(cur, relative_path: str, reason: str):
    storage_key = _image_storage_key(relative_path)
    if storage_key is None:
        return
    cur.execute(
        """
        INSERT INTO file_deletion_queue (relative_path, reason)
        VALUES (%s,%s)
        ON CONFLICT (relative_path) DO UPDATE SET
            reason=EXCLUDED.reason, updated_at=CURRENT_TIMESTAMP
        """,
        (storage_key, reason)
    )


def _preserve_accident_blackbox_images(
    cur,
    accident_event_id: int,
    ride_session_id: str | None,
    detected_at: int,
    user_id: int | None,
) -> int:
    """Links and protects every captured image in the 90 seconds before an accident."""
    if not ride_session_id:
        return 0
    cur.execute(
        """
        INSERT INTO accident_image_evidence (
            accident_event_id, sensor_log_id, captured_at
        )
        SELECT %s, s.sensor_log_id, s.captured_at
        FROM sensor_logs s
        WHERE s.ride_session_id=%s
          AND s.captured_at BETWEEN %s AND %s
          AND s.image_path IS NOT NULL
          AND (%s IS NULL OR s.user_id=%s)
        ON CONFLICT (accident_event_id, sensor_log_id) DO NOTHING
        """,
        (
            accident_event_id,
            ride_session_id,
            detected_at - ACCIDENT_BLACKBOX_LOOKBACK_MS,
            detected_at,
            user_id,
            user_id,
        ),
    )
    linked = cur.rowcount
    cur.execute(
        """
        UPDATE sensor_logs s SET
            image_delete_after=NULL,
            image_retention_reason='ACCIDENT_BLACKBOX'
        WHERE EXISTS (
            SELECT 1 FROM accident_image_evidence e
            WHERE e.accident_event_id=%s
              AND e.sensor_log_id=s.sensor_log_id
        )
        """,
        (accident_event_id,),
    )
    return linked


def _preserve_sensor_for_existing_accident(
    cur,
    sensor_log_id: int,
    ride_session_id: str | None,
    captured_at: int,
    user_id: int | None,
) -> int:
    """Handles offline/retry ordering when the accident reaches the server first."""
    if not ride_session_id:
        return 0
    cur.execute(
        """
        INSERT INTO accident_image_evidence (
            accident_event_id, sensor_log_id, captured_at
        )
        SELECT a.accident_event_id, %s, %s
        FROM accident_events a
        WHERE a.ride_session_id=%s
          AND a.confirmation_status IN ('ACCIDENT', 'NO_RESPONSE')
          AND a.is_false_positive=FALSE
          AND %s BETWEEN a.detected_at-%s AND a.detected_at
          AND (%s IS NULL OR a.user_id=%s)
        ON CONFLICT (accident_event_id, sensor_log_id) DO NOTHING
        """,
        (
            sensor_log_id,
            captured_at,
            ride_session_id,
            captured_at,
            ACCIDENT_BLACKBOX_LOOKBACK_MS,
            user_id,
            user_id,
        ),
    )
    linked = cur.rowcount
    if linked:
        cur.execute(
            """
            UPDATE sensor_logs SET
                image_delete_after=NULL,
                image_retention_reason='ACCIDENT_BLACKBOX'
            WHERE sensor_log_id=%s
            """,
            (sensor_log_id,),
        )
    return linked


def cleanup_expired_undetected_images(
    now_ms: int | None = None,
    limit: int = 500,
) -> int:
    """Deletes AI-negative images after their two-minute accident look-back grace."""
    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)
    result = {"image_files_deleted": 0, "image_delete_failures": 0}
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT s.sensor_log_id, s.image_path,
                       j.ai_analysis_job_id, j.annotated_image_path
                FROM sensor_logs s
                LEFT JOIN ai_analysis_jobs j ON j.sensor_log_id=s.sensor_log_id
                WHERE s.image_delete_after IS NOT NULL
                  AND s.image_delete_after <= %s
                  AND s.image_path IS NOT NULL
                  AND s.image_retention_reason='AI_NO_DETECTION'
                  AND NOT EXISTS (
                      SELECT 1 FROM accident_image_evidence e
                      WHERE e.sensor_log_id=s.sensor_log_id
                  )
                  AND NOT EXISTS (
                      SELECT 1 FROM ai_review_items r
                      WHERE r.sensor_log_id=s.sensor_log_id
                        AND r.status='PENDING'
                  )
                ORDER BY s.image_delete_after, s.sensor_log_id
                LIMIT %s
                FOR UPDATE OF s SKIP LOCKED
                """,
                (now_ms, limit),
            )
            rows = cur.fetchall()
            for sensor_log_id, image_path, job_id, annotated_path in rows:
                _enqueue_file_deletion(cur, image_path, "AI_NO_DETECTION_EXPIRED")
                if annotated_path:
                    _enqueue_file_deletion(cur, annotated_path, "AI_NO_DETECTION_EXPIRED")
                if job_id is not None:
                    cur.execute(
                        """
                        SELECT classified_image_path FROM ai_classified_images
                        WHERE ai_analysis_job_id=%s
                        """,
                        (job_id,),
                    )
                    for (classified_path,) in cur.fetchall():
                        _enqueue_file_deletion(
                            cur, classified_path, "AI_NO_DETECTION_EXPIRED"
                        )
                    cur.execute(
                        "DELETE FROM ai_classified_images WHERE ai_analysis_job_id=%s",
                        (job_id,),
                    )
                    cur.execute(
                        """
                        UPDATE ai_analysis_jobs SET annotated_image_path=NULL
                        WHERE ai_analysis_job_id=%s
                        """,
                        (job_id,),
                    )
                cur.execute(
                    """
                    UPDATE sensor_logs SET image_path=NULL, image_delete_after=NULL
                    WHERE sensor_log_id=%s
                    """,
                    (sensor_log_id,),
                )
        conn.commit()
        _process_file_deletion_queue(conn, result)
        return len(rows)
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def _process_file_deletion_queue(conn, result: dict):
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT relative_path FROM file_deletion_queue
            WHERE next_retry_at <= CURRENT_TIMESTAMP
            ORDER BY created_at LIMIT 1000
            """
        )
        paths = [row[0] for row in cur.fetchall()]
    for relative_path in paths:
        candidate = _stored_image_path(relative_path)
        try:
            if candidate and candidate.is_file():
                candidate.unlink()
            with conn.cursor() as cur:
                cur.execute(
                    "DELETE FROM file_deletion_queue WHERE relative_path=%s",
                    (relative_path,)
                )
            conn.commit()
            result["image_files_deleted"] += 1
        except OSError as exception:
            conn.rollback()
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE file_deletion_queue SET
                        attempt_count=attempt_count+1,
                        next_retry_at=CURRENT_TIMESTAMP + INTERVAL '1 hour',
                        last_error=%s, updated_at=CURRENT_TIMESTAMP
                    WHERE relative_path=%s
                    """,
                    (str(exception)[:1000], relative_path)
                )
            conn.commit()
            result["image_delete_failures"] += 1


def _reconcile_image_storage(conn, result: dict, now_ms: int):
    with conn.cursor() as cur:
        cur.execute(
            "SELECT sensor_log_id, image_path FROM sensor_logs WHERE image_path IS NOT NULL"
        )
        rows = cur.fetchall()
    referenced = set()
    for sensor_log_id, relative_path in rows:
        candidate = _stored_image_path(relative_path)
        storage_key = _image_storage_key(relative_path)
        if candidate is None or not candidate.is_file():
            with conn.cursor() as cur:
                cur.execute(
                    "UPDATE sensor_logs SET image_path=NULL WHERE sensor_log_id=%s",
                    (sensor_log_id,)
                )
            conn.commit()
            result["missing_image_references_cleared"] += 1
            continue
        referenced.add(storage_key)
        invalid = candidate.stat().st_size > MAX_IMAGE_BYTES
        if not invalid:
            with candidate.open("rb") as image_file:
                invalid = not image_file.read(3).startswith(b"\xff\xd8\xff")
        if invalid:
            with conn.cursor() as cur:
                cur.execute(
                    "UPDATE sensor_logs SET image_path=NULL WHERE sensor_log_id=%s",
                    (sensor_log_id,)
                )
                _enqueue_file_deletion(cur, relative_path, "INVALID_IMAGE")
            conn.commit()
            result["invalid_images_queued"] += 1

    orphan_before_ms = now_ms - ORPHAN_GRACE_SECONDS * 1000
    for candidate in IMAGE_UPLOAD_DIR.rglob("*.jpg"):
        with contextlib.suppress(OSError):
            relative_path = candidate.relative_to(IMAGE_UPLOAD_DIR).as_posix()
            if relative_path not in referenced and candidate.stat().st_mtime * 1000 < orphan_before_ms:
                with conn.cursor() as cur:
                    _enqueue_file_deletion(cur, relative_path, "ORPHAN_IMAGE")
                conn.commit()
                result["orphan_images_queued"] += 1


def apply_retention_policy(
    now_ms: int | None = None,
    trigger_type: str = "SCHEDULED",
    requested_by: int | None = None,
):
    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)
    cutoff_ms = retention_cutoff_ms(now_ms, RAW_RETENTION_DAYS)
    # Database lifecycle columns are TIMESTAMP WITHOUT TIME ZONE and are
    # written with PostgreSQL's local CURRENT_TIMESTAMP. Compare them with the
    # same local-wall-clock convention; using naive UTC delays due work by the
    # local UTC offset (nine hours in Korea).
    now_datetime = datetime.fromtimestamp(now_ms / 1000)
    system_log_cutoff = now_datetime - timedelta(days=SYSTEM_LOG_RETENTION_DAYS)
    result = {
        "sensor_rows": 0,
        "accident_locations": 0,
        "system_logs": 0,
        "refresh_tokens": 0,
        "rides_finalized": 0,
        "anonymized_ride_sensors": 0,
        "anonymized_ride_accidents": 0,
        "missing_image_references_cleared": 0,
        "invalid_images_queued": 0,
        "orphan_images_queued": 0,
        "image_files_deleted": 0,
        "image_delete_failures": 0,
        "orphan_sensor_sessions": {},
        "hazard_zone_lifecycle": {},
        "errors": [],
    }
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT pg_try_advisory_lock(%s)", (RETENTION_LOCK_KEY,))
            locked = cur.fetchone()[0]
        if not locked:
            return {"status": "SKIPPED", **result}
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO retention_runs (trigger_type, requested_by, status)
                VALUES (%s,%s,'RUNNING') RETURNING retention_run_id
                """,
                (trigger_type, requested_by)
            )
            run_id = cur.fetchone()[0]
        conn.commit()

        try:
            with conn.cursor() as cur:
                result["orphan_sensor_sessions"] = reconcile_orphan_sensor_sessions(
                    cur, now_ms
                )
            conn.commit()
        except Exception as exception:
            conn.rollback()
            result["errors"].append(f"orphan sensor reconciliation: {exception}")

        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                SELECT image_path FROM sensor_logs
                WHERE captured_at < %s AND image_path IS NOT NULL
                    """,
                    (cutoff_ms,)
                )
                for row in cur.fetchall():
                    _enqueue_file_deletion(cur, row[0], "RAW_RETENTION_EXPIRED")
                cur.execute(
                    """
                    UPDATE ai_review_items r SET image_path=NULL, updated_at=CURRENT_TIMESTAMP
                    WHERE r.sensor_log_id IN (
                        SELECT sensor_log_id FROM sensor_logs WHERE captured_at < %s
                    )
                    """,
                    (cutoff_ms,),
                )
                cur.execute("DELETE FROM sensor_logs WHERE captured_at < %s", (cutoff_ms,))
                result["sensor_rows"] = cur.rowcount
            conn.commit()
        except Exception as exception:
            conn.rollback()
            result["errors"].append(f"sensor retention: {exception}")

        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                UPDATE accident_events SET
                    latitude=NULL, longitude=NULL, accuracy=NULL, speed=NULL,
                    is_anonymized=TRUE
                WHERE detected_at < %s
                  AND (latitude IS NOT NULL OR longitude IS NOT NULL)
                    """,
                    (cutoff_ms,)
                )
                result["accident_locations"] = cur.rowcount
            conn.commit()
        except Exception as exception:
            conn.rollback()
            result["errors"].append(f"accident retention: {exception}")

        try:
            with conn.cursor() as cur:
                cur.execute(
                    "DELETE FROM system_logs WHERE created_at < %s",
                    (system_log_cutoff,),
                )
                result["system_logs"] = cur.rowcount
                cur.execute(
                    "DELETE FROM refresh_tokens WHERE expires_at < %s OR revoked_at IS NOT NULL",
                    (now_ms,)
                )
                result["refresh_tokens"] = cur.rowcount
            conn.commit()
        except Exception as exception:
            conn.rollback()
            result["errors"].append(f"log/token retention: {exception}")

        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                SELECT ride_session_id, user_id FROM rides
                WHERE deletion_due_at IS NOT NULL AND deletion_due_at <= %s
                FOR UPDATE
                    """,
                    (now_datetime,),
                )
                due_rides = cur.fetchall()
                for ride_session_id, user_id in due_rides:
                    retained_sensors, retained_accidents, deleted = finalize_ride_deletion(
                        cur, ride_session_id, user_id
                    )
                    result["anonymized_ride_sensors"] += retained_sensors
                    result["anonymized_ride_accidents"] += retained_accidents
                    result["rides_finalized"] += deleted
                    audit(
                        cur, user_id, "RIDE_DELETE_FINALIZED", "RIDE", ride_session_id,
                        {
                            "retained_sensors": retained_sensors,
                            "retained_accidents": retained_accidents,
                        }
                    )
            conn.commit()
        except Exception as exception:
            conn.rollback()
            result["errors"].append(f"ride deletion: {exception}")

        try:
            _reconcile_image_storage(conn, result, now_ms)
        except Exception as exception:
            conn.rollback()
            result["errors"].append(f"image reconciliation: {exception}")
        try:
            _process_file_deletion_queue(conn, result)
        except Exception as exception:
            conn.rollback()
            result["errors"].append(f"file deletion queue: {exception}")

        try:
            with conn.cursor() as cur:
                result["hazard_zone_lifecycle"] = refresh_hazard_zone_lifecycle(cur)
            conn.commit()
        except Exception as exception:
            conn.rollback()
            result["errors"].append(f"hazard zone lifecycle: {exception}")

        status = "COMPLETED" if not result["errors"] else "PARTIAL"
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE retention_runs SET status=%s, completed_at=CURRENT_TIMESTAMP,
                    result=%s, error_message=%s
                WHERE retention_run_id=%s
                """,
                (
                    status,
                    Json(result),
                    "\n".join(result["errors"])[:4000] or None,
                    run_id,
                )
            )
        conn.commit()
        return {"status": status, "run_id": run_id, **result}
    finally:
        with contextlib.suppress(Exception):
            with conn.cursor() as cur:
                cur.execute("SELECT pg_advisory_unlock(%s)", (RETENTION_LOCK_KEY,))
            conn.commit()
        conn.close()


_retention_task = None
_backup_task = None
_ai_task = None


async def retention_scheduler():
    trigger_type = "STARTUP"
    while True:
        await asyncio.to_thread(apply_retention_policy, None, trigger_type, None)
        trigger_type = "SCHEDULED"
        await asyncio.sleep(RETENTION_INTERVAL_SECONDS)


async def backup_scheduler():
    while True:
        await asyncio.to_thread(create_backup, "SCHEDULED")
        await asyncio.sleep(BACKUP_INTERVAL_SECONDS)


async def ai_scheduler():
    await asyncio.to_thread(recover_interrupted_ai_jobs)
    while True:
        await asyncio.to_thread(process_pending_ai_jobs)
        await asyncio.to_thread(cleanup_expired_undetected_images)
        await asyncio.sleep(AI_QUEUE_INTERVAL_SECONDS)


@app.on_event("startup")
async def start_retention_scheduler():
    global _retention_task, _backup_task, _ai_task
    await asyncio.to_thread(ensure_schema)
    if RETENTION_ENABLED:
        _retention_task = asyncio.create_task(retention_scheduler())
    if BACKUP_ENABLED:
        _backup_task = asyncio.create_task(backup_scheduler())
    if AI_CONFIG.enabled:
        _ai_task = asyncio.create_task(ai_scheduler())


@app.on_event("shutdown")
async def stop_retention_scheduler():
    if _retention_task is not None:
        _retention_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await _retention_task
    if _backup_task is not None:
        _backup_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await _backup_task
    if _ai_task is not None:
        _ai_task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await _ai_task


class ContractModel(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


class SensorSampleRequest(ContractModel):
    sampled_at: int = Field(ge=0)
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)
    accuracy: float | None = Field(default=None, ge=0)
    speed: float | None = Field(default=None, ge=0)
    direction: float | None = None
    accel_x: float
    accel_y: float
    accel_z: float
    gyro_x: float
    gyro_y: float
    gyro_z: float
    geomagnetic_x: float
    geomagnetic_y: float
    geomagnetic_z: float


class SensorLogRequest(ContractModel):
    event_uuid: str | None = Field(default=None, min_length=36, max_length=36)
    ride_session_id: str | None = None
    sensor_samples: list[SensorSampleRequest] = Field(default_factory=list)
    captured_at: int = Field(ge=0)
    peak_sample_at: int | None = Field(default=None, ge=0)

    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    accuracy: float = Field(ge=0)
    speed: float = Field(ge=0)
    direction: float

    gyro_x: float
    gyro_y: float
    gyro_z: float

    accel_x: float
    accel_y: float
    accel_z: float

    geomagnetic_x: float
    geomagnetic_y: float
    geomagnetic_z: float

    image_path: str | None = None

    @field_validator("event_uuid", "ride_session_id")
    @classmethod
    def validate_optional_uuid(cls, value):
        if value is not None:
            uuid.UUID(value)
        return value

    @model_validator(mode="after")
    def validate_image_ownership_key(self):
        if self.image_path is None:
            return self
        if self.event_uuid is None:
            raise ValueError("event_uuid is required when image_path is provided")
        expected = f"events/{self.event_uuid[:2]}/{self.event_uuid}.jpg"
        if self.image_path.replace("\\", "/") != expected:
            raise ValueError("image_path does not match event_uuid")
        return self


class ConsentUpdateRequest(ContractModel):
    ai_research_optional: bool
    long_term_optional: bool
    policy_version: str = "2026-07-23"

class AccidentEventRequest(ContractModel):
    event_uuid: str = Field(min_length=36, max_length=36)
    ride_session_id: str | None = None
    detected_at: int = Field(ge=0)
    latitude: float | None = None
    longitude: float | None = None
    accuracy: float | None = None
    speed: float | None = None
    accel_x: float
    accel_y: float
    accel_z: float
    gyro_x: float
    gyro_y: float
    gyro_z: float
    acceleration_magnitude: float
    gyro_magnitude: float
    confirmation_status: str
    is_false_positive: bool

    @field_validator("event_uuid")
    @classmethod
    def validate_event_uuid(cls, value):
        uuid.UUID(value)
        return value

    @field_validator("confirmation_status")
    @classmethod
    def validate_confirmation_status(cls, value):
        if value not in {"ACCIDENT", "NO_RESPONSE", "SAFE"}:
            raise ValueError("invalid confirmation_status")
        return value


class RideResultRequest(ContractModel):
    ride_session_id: str = Field(min_length=36, max_length=36)
    started_at: int = Field(ge=0)
    ended_at: int = Field(ge=0)
    elapsed_seconds: int = Field(ge=0)
    total_distance_meters: float = Field(ge=0)
    average_speed_kmh: float = Field(ge=0)
    max_speed_kmh: float = Field(ge=0)
    collected_frame_count: int = Field(ge=0)
    accident_count: int = Field(ge=0)

    @field_validator("ride_session_id")
    @classmethod
    def validate_ride_uuid(cls, value):
        uuid.UUID(value)
        return value

    @model_validator(mode="after")
    def validate_time_range(self):
        if self.ended_at < self.started_at:
            raise ValueError("ended_at must not precede started_at")
        return self


class SignupRequest(ContractModel):
    email: str
    nickname: str
    phone: str | None = None
    password: str
    device_id: str
    device_name: str | None = None
    terms_required: bool
    privacy_required: bool
    collection_required: bool
    ai_research_optional: bool = False
    long_term_optional: bool = False


class LoginRequest(ContractModel):
    email: str
    password: str
    device_id: str
    device_name: str | None = None


class RefreshRequest(ContractModel):
    refresh_token: str
    device_id: str


class LogoutRequest(ContractModel):
    refresh_token: str


class PasswordChangeRequest(ContractModel):
    current_password: str
    new_password: str


class PasswordResetRequest(ContractModel):
    email: str


class ProfileUpdateRequest(ContractModel):
    nickname: str
    phone: str | None = None


class AccountDeleteRequest(ContractModel):
    password: str


@app.post("/auth/signup")
def signup(request: SignupRequest):
    email = request.email.strip().lower()
    if "@" not in email:
        raise HTTPException(status_code=422, detail="invalid email")
    if not request.nickname.strip():
        raise HTTPException(status_code=422, detail="nickname is required")
    if not all((
        request.terms_required,
        request.privacy_required,
        request.collection_required
    )):
        raise HTTPException(status_code=422, detail="required consents are missing")
    validate_password(request.password)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            try:
                cur.execute(
                    """
                    INSERT INTO users (email, name, nickname, phone, password_hash)
                    VALUES (%s,%s,%s,%s,%s)
                    RETURNING user_id
                    """,
                    (
                        email, request.nickname.strip(), request.nickname.strip(),
                        request.phone.strip() if request.phone else None,
                        hash_password(request.password)
                    )
                )
                user_id = cur.fetchone()[0]
                cur.execute(
                    """
                    INSERT INTO user_consents (
                        user_id, terms_required, privacy_required, collection_required,
                        ai_research_optional, long_term_optional
                    ) VALUES (%s,%s,%s,%s,%s,%s)
                    """,
                    (
                        user_id, request.terms_required, request.privacy_required,
                        request.collection_required, request.ai_research_optional,
                        request.long_term_optional
                    )
                )
                cur.execute(
                    """
                    INSERT INTO user_consent_history (
                        user_id, terms_required, privacy_required, collection_required,
                        ai_research_optional, long_term_optional, policy_version
                    ) VALUES (%s,%s,%s,%s,%s,%s,%s)
                    """,
                    (
                        user_id, request.terms_required, request.privacy_required,
                        request.collection_required, request.ai_research_optional,
                        request.long_term_optional, "2026-07-23"
                    )
                )
                cur.execute(
                    """
                    INSERT INTO devices (user_id, device_id, device_name)
                    VALUES (%s,%s,%s)
                    """,
                    (user_id, request.device_id, request.device_name)
                )
                conn.commit()
            except Exception as exc:
                conn.rollback()
                if getattr(exc, "pgcode", None) == "23505":
                    raise HTTPException(status_code=409, detail="email already registered")
                raise
    finally:
        conn.close()
    return {"user_id": user_id, **issue_token_pair(user_id, request.device_id)}


@app.post("/auth/login")
def login(request: LoginRequest):
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT user_id, password_hash, is_active, must_change_password, nickname, role
                FROM users WHERE email=%s
                """,
                (request.email.strip().lower(),)
            )
            row = cur.fetchone()
            if row is None or not row[2] or not verify_password(row[1], request.password):
                raise HTTPException(status_code=401, detail="invalid email or password")
            record_demo_event(
                cur,
                category="AUTH",
                event_type="USER_LOGIN",
                message=f"{row[4]} 계정이 앱에 로그인했습니다.",
                status="로그인 완료",
                user_id=row[0],
                device_id=request.device_id,
                details={"role": row[5], "device_name": request.device_name},
            )
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
    return {
        "user_id": row[0],
        "name": row[4],
        "must_change_password": row[3],
        **issue_token_pair(row[0], request.device_id)
    }


@app.post("/auth/refresh")
def refresh(request: RefreshRequest):
    old_hash = token_hash(request.refresh_token)
    now_ms = int(__import__("time").time() * 1000)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT refresh_token_id, user_id FROM refresh_tokens
                WHERE token_hash=%s AND device_id=%s AND revoked_at IS NULL
                  AND expires_at>%s
                FOR UPDATE
                """,
                (old_hash, request.device_id, now_ms)
            )
            row = cur.fetchone()
            if row is None:
                raise HTTPException(status_code=401, detail="invalid refresh token")
            cur.execute(
                "UPDATE refresh_tokens SET revoked_at=CURRENT_TIMESTAMP WHERE refresh_token_id=%s",
                (row[0],)
            )
        conn.commit()
    finally:
        conn.close()
    tokens = issue_token_pair(row[1], request.device_id)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "UPDATE refresh_tokens SET replaced_by_hash=%s WHERE token_hash=%s",
                (token_hash(tokens["refresh_token"]), old_hash)
            )
        conn.commit()
    finally:
        conn.close()
    return {"user_id": row[1], **tokens}


@app.post("/auth/logout")
def logout(request: LogoutRequest):
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT r.user_id,r.device_id,u.nickname,u.role
                FROM refresh_tokens r
                JOIN users u ON u.user_id=r.user_id
                WHERE r.token_hash=%s AND r.revoked_at IS NULL
                ORDER BY r.refresh_token_id DESC LIMIT 1
                """,
                (token_hash(request.refresh_token),)
            )
            account = cur.fetchone()
            cur.execute(
                """
                UPDATE refresh_tokens SET revoked_at=CURRENT_TIMESTAMP
                WHERE token_hash=%s AND revoked_at IS NULL
                """,
                (token_hash(request.refresh_token),)
            )
            if account is not None:
                record_demo_event(
                    cur,
                    category="AUTH",
                    event_type="USER_LOGOUT",
                    message=f"{account[2]} 계정이 앱에서 로그아웃했습니다.",
                    status="로그아웃 완료",
                    user_id=account[0],
                    device_id=account[1],
                    details={"role": account[3]},
                )
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
    return {"message": "logged out"}


@app.get("/auth/me")
def me(request: Request):
    principal = require_principal(request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT user_id,email,nickname,phone,must_change_password,role FROM users WHERE user_id=%s",
                (principal.user_id,)
            )
            row = cur.fetchone()
    finally:
        conn.close()
    if row is None:
        raise HTTPException(status_code=404, detail="user not found")
    return {
        "user_id": row[0], "email": row[1], "nickname": row[2], "phone": row[3],
        "must_change_password": row[4], "role": row[5], "device_id": principal.device_id
    }


@app.patch("/auth/me")
def update_profile(body: ProfileUpdateRequest, request: Request):
    principal = require_principal(request)
    nickname = body.nickname.strip()
    if not nickname:
        raise HTTPException(status_code=422, detail="nickname is required")
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE users SET name=%s, nickname=%s, phone=%s, updated_at=CURRENT_TIMESTAMP
                WHERE user_id=%s
                """,
                (
                    nickname, nickname, body.phone.strip() if body.phone else None,
                    principal.user_id
                )
            )
        conn.commit()
    finally:
        conn.close()
    return {"message": "profile updated"}


@app.get("/auth/consents")
def get_consents(request: Request):
    principal = require_principal(request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT terms_required, privacy_required, collection_required,
                       ai_research_optional, long_term_optional, policy_version,
                       consented_at, updated_at
                FROM user_consents WHERE user_id=%s
                """,
                (principal.user_id,)
            )
            row = cur.fetchone()
    finally:
        conn.close()
    if row is None:
        raise HTTPException(status_code=404, detail="consents not found")
    return {
        "terms_required": row[0], "privacy_required": row[1],
        "collection_required": row[2], "ai_research_optional": row[3],
        "long_term_optional": row[4], "policy_version": row[5],
        "consented_at": row[6].isoformat(), "updated_at": row[7].isoformat()
    }


@app.patch("/auth/consents")
def update_consents(body: ConsentUpdateRequest, request: Request):
    principal = require_principal(request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE user_consents
                SET ai_research_optional=%s, long_term_optional=%s,
                    policy_version=%s, updated_at=CURRENT_TIMESTAMP
                WHERE user_id=%s
                RETURNING terms_required, privacy_required, collection_required
                """,
                (
                    body.ai_research_optional, body.long_term_optional,
                    body.policy_version, principal.user_id
                )
            )
            required = cur.fetchone()
            if required is None:
                raise HTTPException(status_code=404, detail="consents not found")
            cur.execute(
                """
                INSERT INTO user_consent_history (
                    user_id, terms_required, privacy_required, collection_required,
                    ai_research_optional, long_term_optional, policy_version
                ) VALUES (%s,%s,%s,%s,%s,%s,%s)
                """,
                (
                    principal.user_id, required[0], required[1], required[2],
                    body.ai_research_optional, body.long_term_optional,
                    body.policy_version
                )
            )
        conn.commit()
    finally:
        conn.close()
    return {"message": "consents updated"}


@app.get("/auth/consents/history")
def consent_history(request: Request):
    principal = require_principal(request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT ai_research_optional, long_term_optional, policy_version, changed_at
                FROM user_consent_history WHERE user_id=%s
                ORDER BY changed_at DESC LIMIT 50
                """,
                (principal.user_id,)
            )
            rows = cur.fetchall()
    finally:
        conn.close()
    return [
        {
            "ai_research_optional": row[0], "long_term_optional": row[1],
            "policy_version": row[2], "changed_at": row[3].isoformat()
        }
        for row in rows
    ]


@app.post("/auth/password/change")
def change_password(body: PasswordChangeRequest, request: Request):
    principal = require_principal(request)
    validate_password(body.new_password)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT password_hash FROM users WHERE user_id=%s", (principal.user_id,))
            row = cur.fetchone()
            if row is None or not verify_password(row[0], body.current_password):
                raise HTTPException(status_code=401, detail="current password is incorrect")
            cur.execute(
                """
                UPDATE users SET password_hash=%s, must_change_password=FALSE,
                    updated_at=CURRENT_TIMESTAMP WHERE user_id=%s
                """,
                (hash_password(body.new_password), principal.user_id)
            )
            cur.execute(
                """
                UPDATE refresh_tokens SET revoked_at=CURRENT_TIMESTAMP
                WHERE user_id=%s AND revoked_at IS NULL
                """,
                (principal.user_id,)
            )
        conn.commit()
    finally:
        conn.close()
    return {"message": "password changed; sign in again"}


@app.post("/auth/password/reset")
def reset_password(body: PasswordResetRequest):
    if not password_email_configured():
        raise HTTPException(
            status_code=503,
            detail="password reset email is not configured"
        )
    temporary = secrets.token_urlsafe(9) + "!"
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE users SET password_hash=%s, must_change_password=TRUE,
                    updated_at=CURRENT_TIMESTAMP
                WHERE email=%s RETURNING user_id
                """,
                (hash_password(temporary), body.email.strip().lower())
            )
            row = cur.fetchone()
            if row:
                cur.execute(
                    """
                    UPDATE refresh_tokens SET revoked_at=CURRENT_TIMESTAMP
                    WHERE user_id=%s AND revoked_at IS NULL
                    """,
                    (row[0],)
                )
                try:
                    send_temporary_password(body.email.strip().lower(), temporary)
                except Exception:
                    conn.rollback()
                    raise HTTPException(
                        status_code=503,
                        detail="temporary password email could not be sent"
                    )
        conn.commit()
    finally:
        conn.close()
    response = {"message": "if the account exists, a temporary password was issued"}
    if row and os.getenv("BIKE_AUTH_DEV_EXPOSE_TEMP_PASSWORD", "").lower() == "true":
        response["temporary_password"] = temporary
    return response


def ingest_principal(request: Request):
    principal = optional_principal(request)
    if principal is None and not ALLOW_ANONYMOUS_INGEST:
        raise HTTPException(status_code=401, detail="authentication required")
    return principal


def delete_stored_image(relative_path: str | None):
    if not relative_path:
        return
    candidate = (IMAGE_UPLOAD_DIR / relative_path).resolve()
    if IMAGE_UPLOAD_DIR not in candidate.parents:
        return
    if candidate.is_file():
        candidate.unlink()


@app.delete("/auth/me")
def delete_account(body: AccountDeleteRequest, request: Request):
    principal = require_principal(request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT password_hash FROM users WHERE user_id=%s",
                (principal.user_id,)
            )
            user_row = cur.fetchone()
            if user_row is None or not verify_password(user_row[0], body.password):
                raise HTTPException(status_code=401, detail="password is incorrect")
            cur.execute(
                """
                SELECT ai_research_optional, long_term_optional
                FROM user_consents WHERE user_id=%s
                """,
                (principal.user_id,)
            )
            consent_row = cur.fetchone()
            preserve_image = bool(consent_row and consent_row[0])
            preserve_collected_data = bool(
                consent_row and (consent_row[0] or consent_row[1])
            )
            cur.execute(
                "SELECT image_path FROM sensor_logs WHERE user_id=%s AND image_path IS NOT NULL",
                (principal.user_id,)
            )
            image_paths = [row[0] for row in cur.fetchall()]
            cur.execute("DELETE FROM rides WHERE user_id=%s", (principal.user_id,))
            if preserve_collected_data:
                cur.execute(
                    """
                    UPDATE accident_events SET
                        user_id=NULL, device_id=NULL, ride_session_id=NULL,
                        latitude=NULL, longitude=NULL, accuracy=NULL, speed=NULL,
                        is_anonymized=TRUE
                    WHERE user_id=%s
                    """,
                    (principal.user_id,)
                )
                cur.execute(
                    """
                    UPDATE sensor_logs SET
                        user_id=NULL, device_id=NULL, ride_session_id=NULL,
                        latitude=NULL, longitude=NULL, accuracy=NULL,
                        speed=NULL, direction=NULL,
                        image_path=CASE WHEN %s THEN image_path ELSE NULL END,
                        sensor_samples=COALESCE((
                            SELECT jsonb_agg(
                                sample - 'latitude' - 'longitude' - 'accuracy'
                                       - 'speed' - 'direction'
                            )
                            FROM jsonb_array_elements(sensor_samples) AS sample
                        ), '[]'::jsonb),
                        is_anonymized=TRUE
                    WHERE user_id=%s
                    """,
                    (preserve_image, principal.user_id)
                )
            else:
                cur.execute(
                    "DELETE FROM accident_events WHERE user_id=%s",
                    (principal.user_id,)
                )
                cur.execute("DELETE FROM sensor_logs WHERE user_id=%s", (principal.user_id,))
            if not preserve_image:
                for image_path in image_paths:
                    _enqueue_file_deletion(cur, image_path, "ACCOUNT_DELETED")
            audit(
                cur,
                principal.user_id,
                "ACCOUNT_DELETED",
                "USER",
                str(principal.user_id),
                {
                    "preserved_collected_data": preserve_collected_data,
                    "preserved_images": preserve_image,
                }
            )
            cur.execute("DELETE FROM users WHERE user_id=%s", (principal.user_id,))
        conn.commit()
    finally:
        conn.close()
    return {"message": "account deleted"}


@app.get("/")
def health_check():
    return {
        "status": "ok",
        "message": "BikeEyes Server is running"
    }


@app.get("/demo-monitor")
def demo_monitor_page(request: Request):
    require_demo_access(request)
    return FileResponse(DEMO_MONITOR_STATIC_DIR / "index.html", media_type="text/html")


@app.get("/api/demo-monitor/status")
def demo_monitor_status(request: Request):
    require_demo_access(request)
    return monitor_status()


@app.get("/api/demo-monitor/events")
def demo_monitor_event_list(
    request: Request,
    limit: int = Query(default=200, ge=1, le=500),
    after_id: int = Query(default=0, ge=0),
):
    require_demo_access(request)
    return {"items": monitor_events(limit, after_id), "limit": limit, "after_id": after_id}


@app.post("/sensor-log")
def receive_sensor_log(
    request: SensorLogRequest,
    http_request: Request,
    background_tasks: BackgroundTasks,
):
    principal = ingest_principal(http_request)
    normalized_event_uuid = request.event_uuid or str(uuid.uuid4())
    sample_payloads = [sample.model_dump() for sample in request.sensor_samples]
    impact_delta, impact_at = image_matched_impact(
        sample_payloads,
        request.captured_at,
        request.accel_x,
        request.accel_y,
        request.accel_z,
        request.peak_sample_at,
    )
    speed_median = median_speed_around_impact(
        sample_payloads,
        impact_at,
        request.captured_at,
        request.speed,
    )
    peak_sample = next(
        (
            sample
            for sample in sample_payloads
            if int(sample.get("sampled_at") or -1) == int(impact_at)
        ),
        None,
    )
    peak_values = peak_sample or {
        "accel_x": request.accel_x,
        "accel_y": request.accel_y,
        "accel_z": request.accel_z,
        "gyro_x": request.gyro_x,
        "gyro_y": request.gyro_y,
        "gyro_z": request.gyro_z,
        "geomagnetic_x": request.geomagnetic_x,
        "geomagnetic_y": request.geomagnetic_y,
        "geomagnetic_z": request.geomagnetic_z,
    }
    acceleration_magnitude = math.sqrt(
        float(peak_values["accel_x"]) ** 2
        + float(peak_values["accel_y"]) ** 2
        + float(peak_values["accel_z"]) ** 2
    )
    peak_available = bool(sample_payloads) or request.peak_sample_at is not None
    meets_sensor_threshold = (
        peak_available
        and impact_delta >= AI_SENSOR_SUPPORT_DELTA
        and speed_median >= SENSOR_EVIDENCE_MIN_SPEED_MPS
    )
    ai_job_id = None
    with db_transaction() as cur:
        cur.execute(
            """
        INSERT INTO sensor_logs (
            event_uuid,
            ride_session_id,
            captured_at,
            latitude, longitude, accuracy, speed, direction,
            accel_x, accel_y, accel_z,
            gyro_x, gyro_y, gyro_z,
            geomagnetic_x, geomagnetic_y, geomagnetic_z,
            image_path,
            sensor_samples,
            peak_sample_at,
            user_id, device_id, is_test_data
        )
        VALUES (
            %s,
            %s,
            %s, %s, %s, %s, %s, %s,
            %s, %s, %s,
            %s, %s, %s,
            %s, %s, %s,
            %s,
            %s,
            %s,
            %s, %s, %s
        )
        ON CONFLICT (event_uuid) WHERE event_uuid IS NOT NULL
        DO UPDATE SET event_uuid=EXCLUDED.event_uuid
        RETURNING sensor_log_id;
            """,
            (
            normalized_event_uuid,
            request.ride_session_id,
            request.captured_at,
            request.latitude,
            request.longitude,
            request.accuracy,
            request.speed,
            request.direction,
            request.accel_x,
            request.accel_y,
            request.accel_z,
            request.gyro_x,
            request.gyro_y,
            request.gyro_z,
            request.geomagnetic_x,
            request.geomagnetic_y,
            request.geomagnetic_z,
            request.image_path,
            Json(sample_payloads),
            request.peak_sample_at,
            principal.user_id if principal else None,
            principal.device_id if principal else None,
            principal is None,
            )
        )
        sensor_log_id = cur.fetchone()[0]
        if request.image_path:
            _preserve_sensor_for_existing_accident(
                cur,
                sensor_log_id,
                request.ride_session_id,
                request.captured_at,
                principal.user_id if principal else None,
            )
            ai_job_id = enqueue_ai_analysis(
                cur,
                sensor_log_id,
                normalized_event_uuid,
                request.image_path,
            )
        track_orphan_sensor_session(cur, request.ride_session_id)
        if request.ride_session_id:
            cur.execute(
                """
                UPDATE rides
                SET collected_frame_count = GREATEST(
                    collected_frame_count,
                    (
                        SELECT COUNT(*)
                        FROM sensor_logs
                        WHERE ride_session_id = %s
                    )
                ),
                updated_at = CURRENT_TIMESTAMP
                WHERE ride_session_id = %s
                """,
                (request.ride_session_id, request.ride_session_id),
            )
            cur.execute(
                "SELECT COUNT(*) FROM sensor_logs WHERE ride_session_id=%s",
                (request.ride_session_id,),
            )
            current_count = cur.fetchone()[0]
            if current_count == 1:
                record_demo_event(
                    cur,
                    category="RIDE",
                    event_type="RIDE_DATA_RECEIVE_STARTED",
                    message="휴대폰에서 새 주행 데이터 수신을 시작했습니다.",
                    status="수신 중",
                    ride_session_id=request.ride_session_id,
                    event_uuid=normalized_event_uuid,
                    user_id=principal.user_id if principal else None,
                    device_id=principal.device_id if principal else None,
                    details={"sensor_count": current_count},
                )
            if current_count == 1 or current_count % 10 == 0:
                record_demo_event(
                    cur,
                    category="SENSOR",
                    event_type="SENSOR_BATCH_STORED",
                    message=f"센서 데이터 {current_count}건을 저장했습니다.",
                    status="저장 완료",
                    ride_session_id=request.ride_session_id,
                    event_uuid=normalized_event_uuid,
                    user_id=principal.user_id if principal else None,
                    device_id=principal.device_id if principal else None,
                    details={"sensor_count": current_count},
                )
                if request.latitude is not None and request.longitude is not None:
                    record_demo_event(
                        cur,
                        category="GPS",
                        event_type="GPS_BATCH_STORED",
                        message=f"주행 위치 데이터가 누적 {current_count}건 저장되었습니다.",
                        status="저장 완료",
                        ride_session_id=request.ride_session_id,
                        event_uuid=normalized_event_uuid,
                        user_id=principal.user_id if principal else None,
                        device_id=principal.device_id if principal else None,
                        details={"location_count": current_count},
                    )

        cur.execute(
            """
            SELECT 1
            FROM demo_monitor_events
            WHERE event_uuid=%s AND event_type='SENSOR_PEAK_RECORDED'
            LIMIT 1
            """,
            (normalized_event_uuid,),
        )
        if cur.fetchone() is None:
            frame_label = (
                f"이미지 #{current_count}"
                if request.image_path and request.ride_session_id
                else "수집 이미지"
            )
            if peak_available:
                peak_message = (
                    f"{frame_label}에 배정된 센서 4개 중 Peak를 "
                    f"선정했습니다. 충격 변화량 {impact_delta:.2f}m/s², "
                    f"속도 중앙값 {speed_median:.2f}m/s입니다."
                )
                peak_status = "기준 통과" if meets_sensor_threshold else "선정 완료"
            else:
                peak_message = (
                    f"{frame_label}에 배정된 센서 샘플이 없어 Peak를 선정하지 "
                    "못했습니다."
                )
                peak_status = "샘플 없음"
            record_demo_event(
                cur,
                category="SENSOR_PEAK",
                event_type="SENSOR_PEAK_RECORDED",
                message=peak_message,
                status=peak_status,
                ride_session_id=request.ride_session_id,
                event_uuid=normalized_event_uuid,
                user_id=principal.user_id if principal else None,
                device_id=principal.device_id if principal else None,
                details={
                    "captured_at": request.captured_at,
                    "peak_sample_at": impact_at if peak_available else None,
                    "sample_count": len(sample_payloads),
                    "accel_x": round(float(peak_values["accel_x"]), 3),
                    "accel_y": round(float(peak_values["accel_y"]), 3),
                    "accel_z": round(float(peak_values["accel_z"]), 3),
                    "gyro_x": round(float(peak_values["gyro_x"]), 3),
                    "gyro_y": round(float(peak_values["gyro_y"]), 3),
                    "gyro_z": round(float(peak_values["gyro_z"]), 3),
                    "geomagnetic_x": round(float(peak_values["geomagnetic_x"]), 3),
                    "geomagnetic_y": round(float(peak_values["geomagnetic_y"]), 3),
                    "geomagnetic_z": round(float(peak_values["geomagnetic_z"]), 3),
                    "acceleration_magnitude_mps2": round(acceleration_magnitude, 3),
                    "impact_delta_mps2": round(float(impact_delta), 3),
                    "speed_median_mps": round(float(speed_median), 3),
                    "impact_threshold_mps2": AI_SENSOR_SUPPORT_DELTA,
                    "speed_threshold_mps": SENSOR_EVIDENCE_MIN_SPEED_MPS,
                    "meets_user_hazard_sensor_threshold": meets_sensor_threshold,
                },
            )
            logger.debug(
                "Peak selected event_uuid=%s captured_at=%s peak_at=%s samples=%s "
                "accel=(%.3f,%.3f,%.3f) magnitude=%.3f impact_delta=%.3f "
                "speed_median=%.3f threshold_pass=%s",
                normalized_event_uuid,
                request.captured_at,
                impact_at if peak_available else None,
                len(sample_payloads),
                float(peak_values["accel_x"]),
                float(peak_values["accel_y"]),
                float(peak_values["accel_z"]),
                acceleration_magnitude,
                impact_delta,
                speed_median,
                meets_sensor_threshold,
            )

    if ai_job_id is not None:
        background_tasks.add_task(process_ai_job, ai_job_id)

    return {
        "id": sensor_log_id,
        "message": "sensor log saved"
    }


@app.get("/health/live")
def health_live():
    return {"status": "UP"}


@app.get("/health/ready")
def health_ready():
    health = operational_health()
    ai_health = ai_status()
    infrastructure_ready = health["database"] == "UP" and health["storage"] == "UP"
    ai_ready = not ai_health["enabled"] or ai_health["ready"]
    status = "UP" if infrastructure_ready and ai_ready else "DEGRADED"
    return JSONResponse(
        status_code=200 if status == "UP" else 503,
        content={"status": status, **health, "ai": ai_health},
    )


@app.get("/admin/ai/status")
def admin_ai_status(request: Request):
    require_admin(request)
    return {**ai_status(), "queue": ai_queue_status()}


@app.get("/admin/ai/jobs")
def admin_ai_jobs(
    request: Request,
    limit: int = Query(default=100, ge=1, le=500),
):
    require_admin(request)
    return {"items": list_analysis_jobs(limit), "limit": limit}

@app.post("/accident-event")
def receive_accident_event(request: AccidentEventRequest, http_request: Request):
    principal = ingest_principal(http_request)
    with db_transaction() as cur:
        cur.execute("""
        INSERT INTO accident_events (
            event_uuid, ride_session_id, detected_at, latitude, longitude, accuracy, speed,
            accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z,
            acceleration_magnitude, gyro_magnitude, confirmation_status, is_false_positive,
            user_id, device_id, is_test_data
        ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
        ON CONFLICT (event_uuid)
        DO UPDATE SET event_uuid=EXCLUDED.event_uuid
        RETURNING accident_event_id
    """, (request.event_uuid, request.ride_session_id, request.detected_at,
          request.latitude, request.longitude, request.accuracy, request.speed,
          request.accel_x, request.accel_y, request.accel_z,
          request.gyro_x, request.gyro_y, request.gyro_z,
          request.acceleration_magnitude, request.gyro_magnitude,
          request.confirmation_status, request.is_false_positive,
          principal.user_id if principal else None,
          principal.device_id if principal else None,
          principal is None))
        row = cur.fetchone()
        blackbox_image_count = 0
        if (
            request.confirmation_status in {"ACCIDENT", "NO_RESPONSE"}
            and not request.is_false_positive
        ):
            blackbox_image_count = _preserve_accident_blackbox_images(
                cur,
                row[0],
                request.ride_session_id,
                request.detected_at,
                principal.user_id if principal else None,
            )
        record_demo_event(
            cur,
            category="ACCIDENT",
            event_type="ACCIDENT_EVENT_STORED",
            message="휴대폰에서 수신한 사고 의심 확인 결과를 저장했습니다.",
            status="저장 완료",
            ride_session_id=request.ride_session_id,
            event_uuid=request.event_uuid,
            user_id=principal.user_id if principal else None,
            device_id=principal.device_id if principal else None,
            details={
                "confirmation_status": request.confirmation_status,
                "is_false_positive": request.is_false_positive,
                "blackbox_image_count": blackbox_image_count,
            },
        )
    return {
        "id": row[0],
        "message": "accident event saved",
        "blackbox_image_count": blackbox_image_count,
    }


@app.get("/accident-events/{event_uuid}/images")
def get_accident_event_images(event_uuid: str, request: Request):
    principal = require_principal(request)
    try:
        normalized_event_uuid = str(uuid.UUID(event_uuid))
    except ValueError:
        raise HTTPException(status_code=422, detail="invalid event_uuid")

    with db_transaction() as cur:
        cur.execute(
            "SELECT accident_event_id,user_id FROM accident_events WHERE event_uuid=%s",
            (normalized_event_uuid,),
        )
        accident = cur.fetchone()
        if accident is None:
            raise HTTPException(status_code=404, detail="accident event not found")
        cur.execute("SELECT role FROM users WHERE user_id=%s", (principal.user_id,))
        role_row = cur.fetchone()
        is_admin = role_row is not None and role_row[0] == "ADMIN"
        if accident[1] != principal.user_id and not is_admin:
            raise HTTPException(status_code=403, detail="accident image access denied")
        cur.execute(
            """
            SELECT s.event_uuid, s.captured_at, s.image_path, j.annotated_image_path,
                   s.peak_sample_at, s.latitude, s.longitude, s.accuracy,
                   s.speed, s.direction,
                   s.accel_x, s.accel_y, s.accel_z,
                   s.gyro_x, s.gyro_y, s.gyro_z,
                   s.geomagnetic_x, s.geomagnetic_y, s.geomagnetic_z,
                   s.sensor_samples
            FROM accident_events a
            JOIN sensor_logs s ON (
                EXISTS (
                    SELECT 1 FROM accident_image_evidence e
                    WHERE e.accident_event_id=a.accident_event_id
                      AND e.sensor_log_id=s.sensor_log_id
                )
                OR (
                    s.ride_session_id=a.ride_session_id
                    AND s.captured_at BETWEEN a.detected_at-%s AND a.detected_at
                )
            )
            LEFT JOIN ai_analysis_jobs j ON j.sensor_log_id=s.sensor_log_id
            WHERE a.accident_event_id=%s
              AND (a.user_id IS NULL OR s.user_id=a.user_id)
              AND s.event_uuid IS NOT NULL
              AND s.image_path IS NOT NULL
            ORDER BY s.captured_at, s.sensor_log_id
            """,
            (ACCIDENT_BLACKBOX_LOOKBACK_MS, accident[0]),
        )
        # 조회와 보존은 별개다. SAFE/과거 미연결 기록도 같은 주행의 당시 이미지가
        # 남아 있으면 보여주되, evidence를 추가하거나 삭제 예정 시각을 연장하지 않는다.
        items = [
            {
                "event_uuid": item[0],
                "captured_at": item[1],
                "peak_sample_at": item[4],
                "latitude": item[5],
                "longitude": item[6],
                "accuracy": item[7],
                "speed": item[8],
                "direction": item[9],
                "accel_x": item[10],
                "accel_y": item[11],
                "accel_z": item[12],
                "gyro_x": item[13],
                "gyro_y": item[14],
                "gyro_z": item[15],
                "geomagnetic_x": item[16],
                "geomagnetic_y": item[17],
                "geomagnetic_z": item[18],
                "sensor_samples": item[19] or [],
            }
            for item in cur.fetchall()
            if _first_existing_stored_image_path(item[3], item[2]) is not None
        ]
    return {
        "accident_event_uuid": normalized_event_uuid,
        "lookback_seconds": ACCIDENT_BLACKBOX_LOOKBACK_MS // 1000,
        "items": items,
    }


@app.post("/ride-result")
def receive_ride_result(request: RideResultRequest, http_request: Request):
    principal = ingest_principal(http_request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO rides (
                    ride_session_id, started_at, ended_at, elapsed_seconds,
                    total_distance_meters, average_speed_kmh, max_speed_kmh,
                    collected_frame_count, accident_count,
                    user_id, device_id, is_test_data
                ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                ON CONFLICT (ride_session_id) DO UPDATE SET
                    started_at = EXCLUDED.started_at,
                    ended_at = EXCLUDED.ended_at,
                    elapsed_seconds = EXCLUDED.elapsed_seconds,
                    total_distance_meters = EXCLUDED.total_distance_meters,
                    average_speed_kmh = EXCLUDED.average_speed_kmh,
                    max_speed_kmh = EXCLUDED.max_speed_kmh,
                    collected_frame_count = EXCLUDED.collected_frame_count,
                    accident_count = EXCLUDED.accident_count,
                    user_id = COALESCE(EXCLUDED.user_id, rides.user_id),
                    device_id = COALESCE(EXCLUDED.device_id, rides.device_id),
                    is_test_data = CASE
                        WHEN EXCLUDED.user_id IS NOT NULL THEN FALSE
                        ELSE rides.is_test_data
                    END,
                    updated_at = CURRENT_TIMESTAMP
                RETURNING ride_session_id
                """,
                (
                    request.ride_session_id,
                    request.started_at,
                    request.ended_at,
                    request.elapsed_seconds,
                    request.total_distance_meters,
                    request.average_speed_kmh,
                    request.max_speed_kmh,
                    request.collected_frame_count,
                    request.accident_count,
                    principal.user_id if principal else None,
                    principal.device_id if principal else None,
                    principal is None
                )
            )
            ride_session_id = cur.fetchone()[0]
            resolve_orphan_sensor_session(cur, ride_session_id)
            cur.execute(
                """
                UPDATE rides
                SET collected_frame_count = GREATEST(
                    collected_frame_count,
                    (
                        SELECT COUNT(*)
                        FROM sensor_logs
                        WHERE ride_session_id = %s
                    )
                ),
                updated_at = CURRENT_TIMESTAMP
                WHERE ride_session_id = %s
                """,
                (ride_session_id, ride_session_id),
            )
            cur.execute(
                "SELECT COUNT(*) FROM sensor_logs WHERE ride_session_id=%s",
                (ride_session_id,),
            )
            actual_frame_count = cur.fetchone()[0]
            record_demo_event(
                cur,
                category="RIDE",
                event_type="RIDE_RESULT_STORED",
                message="주행 종료 정보와 주행 요약을 저장했습니다.",
                status="완료",
                ride_session_id=ride_session_id,
                user_id=principal.user_id if principal else None,
                device_id=principal.device_id if principal else None,
                details={
                    "expected_frame_count": request.collected_frame_count,
                    "stored_frame_count": actual_frame_count,
                },
            )
            sync_complete = actual_frame_count >= request.collected_frame_count
            record_demo_event(
                cur,
                category="SYNC",
                event_type="RIDE_SYNC_STATUS",
                message=(
                    "모든 주행 데이터의 동기화를 완료했습니다."
                    if sync_complete
                    else "일부 주행 데이터를 계속 전송하고 있습니다."
                ),
                status="동기화 완료" if sync_complete else "재전송 중",
                ride_session_id=ride_session_id,
                user_id=principal.user_id if principal else None,
                device_id=principal.device_id if principal else None,
                details={
                    "expected_frame_count": request.collected_frame_count,
                    "stored_frame_count": actual_frame_count,
                },
            )
        conn.commit()
        return {"ride_session_id": ride_session_id, "message": "ride result saved"}
    finally:
        conn.close()


@app.get("/rides/{ride_session_id}")
def get_ride(ride_session_id: str, request: Request):
    principal = require_principal(request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT ride_session_id, started_at, ended_at, elapsed_seconds,
                       total_distance_meters, average_speed_kmh, max_speed_kmh,
                       collected_frame_count, accident_count
                FROM rides
                WHERE ride_session_id = %s AND user_id = %s AND deleted_at IS NULL
                """,
                (ride_session_id, principal.user_id)
            )
            ride = cur.fetchone()
            if ride is None:
                raise HTTPException(status_code=404, detail="ride not found")
            cur.execute(
                """
                WITH ordered AS (
                    SELECT captured_at, latitude, longitude, accuracy, speed, direction,
                           ROW_NUMBER() OVER (ORDER BY captured_at) AS row_number,
                           COUNT(*) OVER () AS total_count
                    FROM sensor_logs
                    WHERE ride_session_id = %s AND user_id = %s
                      AND latitude IS NOT NULL AND longitude IS NOT NULL
                      AND accuracy <= 50
                )
                SELECT captured_at, latitude, longitude, accuracy, speed, direction
                FROM ordered
                WHERE total_count <= 2000
                   OR row_number = 1
                   OR row_number = total_count
                   OR MOD(row_number - 1, CEIL(total_count / 2000.0)::INTEGER) = 0
                ORDER BY captured_at
                """,
                (ride_session_id, principal.user_id)
            )
            route = [
                {
                    "captured_at": row[0],
                    "latitude": row[1],
                    "longitude": row[2],
                    "accuracy": row[3],
                    "speed": row[4],
                    "direction": row[5]
                }
                for row in cur.fetchall()
            ]
        return {
            "ride_session_id": ride[0],
            "started_at": ride[1],
            "ended_at": ride[2],
            "elapsed_seconds": ride[3],
            "total_distance_meters": ride[4],
            "average_speed_kmh": ride[5],
            "max_speed_kmh": ride[6],
            "collected_frame_count": ride[7],
            "accident_count": ride[8],
            "route": route
        }
    finally:
        conn.close()


@app.get("/rides")
def list_rides(
    request: Request,
    limit: int = Query(50, ge=1, le=100),
    offset: int = Query(0, ge=0)
):
    principal = require_principal(request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT COUNT(*) FROM rides WHERE user_id=%s AND deleted_at IS NULL",
                (principal.user_id,)
            )
            total = cur.fetchone()[0]
            cur.execute(
                """
                SELECT ride_session_id, started_at, ended_at, elapsed_seconds,
                       total_distance_meters, average_speed_kmh, max_speed_kmh,
                       collected_frame_count, accident_count, updated_at
                FROM rides
                WHERE user_id=%s AND deleted_at IS NULL
                ORDER BY ended_at DESC
                LIMIT %s OFFSET %s
                """,
                (principal.user_id, limit, offset)
            )
            rows = cur.fetchall()
    finally:
        conn.close()
    return {
        "items": [
            {
                "ride_session_id": row[0], "started_at": row[1], "ended_at": row[2],
                "elapsed_seconds": row[3], "total_distance_meters": row[4],
                "average_speed_kmh": row[5], "max_speed_kmh": row[6],
                "collected_frame_count": row[7], "accident_count": row[8],
                "updated_at": row[9].isoformat()
            }
            for row in rows
        ],
        "total": total,
        "limit": limit,
        "offset": offset
    }


@app.get("/rides/{ride_session_id}/sensor-logs")
def get_ride_sensor_logs(
    ride_session_id: str,
    request: Request,
    limit: int = Query(100, ge=1, le=500),
    offset: int = Query(0, ge=0),
    images_only: bool = Query(False)
):
    principal = require_principal(request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT 1 FROM rides WHERE ride_session_id=%s AND user_id=%s AND deleted_at IS NULL",
                (ride_session_id, principal.user_id)
            )
            if cur.fetchone() is None:
                raise HTTPException(status_code=404, detail="ride not found")
            image_filter = " AND image_path IS NOT NULL AND image_path <> ''" if images_only else ""
            cur.execute(
                f"SELECT COUNT(*) FROM sensor_logs "
                f"WHERE ride_session_id=%s AND user_id=%s{image_filter}",
                (ride_session_id, principal.user_id)
            )
            total = cur.fetchone()[0]
            cur.execute(
                """
                SELECT s.captured_at, s.latitude, s.longitude, s.accuracy, s.speed, s.direction,
                       gyro_x, gyro_y, gyro_z, accel_x, accel_y, accel_z,
                       geomagnetic_x, geomagnetic_y, geomagnetic_z, image_path,
                       s.event_uuid,
                       COALESCE((
                           SELECT json_agg(json_build_object(
                               'class_name', d.class_name,
                               'confidence', d.confidence,
                               'stage', d.stage,
                               'model_version', d.model_version
                           ) ORDER BY d.confidence DESC)
                           FROM ai_analysis_jobs j
                           JOIN ai_detections d
                             ON d.ai_analysis_job_id=j.ai_analysis_job_id
                           WHERE j.sensor_log_id=s.sensor_log_id
                             AND d.confidence >= CASE lower(d.class_name)
                                 WHEN 'linear' THEN 0.40
                                 WHEN 'alligator' THEN 0.40
                                 WHEN 'pothole' THEN 0.50
                                 WHEN 'flood_area' THEN 0.50
                                 WHEN 'manhole' THEN 0.55
                                 WHEN 'bollard' THEN 0.55
                                 ELSE 1.01
                             END
                       ), '[]'::json) AS road_anomaly_detections
                FROM sensor_logs s
                WHERE s.ride_session_id=%s AND s.user_id=%s
                  AND (%s = FALSE OR (s.image_path IS NOT NULL AND s.image_path <> ''))
                ORDER BY s.captured_at
                LIMIT %s OFFSET %s
                """,
                (ride_session_id, principal.user_id, images_only, limit, offset)
            )
            rows = cur.fetchall()
    finally:
        conn.close()
    return {
        "items": [
            {
                "captured_at": row[0], "latitude": row[1], "longitude": row[2],
                "accuracy": row[3], "speed": row[4], "direction": row[5],
                "gyro_x": row[6], "gyro_y": row[7], "gyro_z": row[8],
                "accel_x": row[9], "accel_y": row[10], "accel_z": row[11],
                "geomagnetic_x": row[12], "geomagnetic_y": row[13],
                "geomagnetic_z": row[14], "image_path": row[15],
                "event_uuid": row[16],
                "road_anomaly_detections": row[17]
            }
            for row in rows
        ],
        "total": total,
        "limit": limit,
        "offset": offset
    }


@app.delete("/rides/{ride_session_id}")
def delete_ride(ride_session_id: str, request: Request):
    principal = require_principal(request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE rides SET
                    deleted_at=COALESCE(deleted_at, CURRENT_TIMESTAMP),
                    deletion_due_at=COALESCE(
                        deletion_due_at,
                        CURRENT_TIMESTAMP + INTERVAL '7 days'
                    ),
                    updated_at=CURRENT_TIMESTAMP
                WHERE ride_session_id=%s AND user_id=%s
                RETURNING deletion_due_at
                """,
                (ride_session_id, principal.user_id)
            )
            row = cur.fetchone()
            if row is not None:
                audit(
                    cur, principal.user_id, "RIDE_DELETE_REQUESTED", "RIDE",
                    ride_session_id, {"recovery_days": RIDE_RECOVERY_DAYS}
                )
        conn.commit()
    finally:
        conn.close()
    return {
        "message": "ride hidden and scheduled for deletion",
        "recoverable": row is not None,
        "deletion_due_at": row[0].isoformat() if row else None,
    }


@app.get("/deleted-rides")
def list_deleted_rides(request: Request):
    principal = require_principal(request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT ride_session_id, started_at, ended_at, elapsed_seconds,
                       total_distance_meters, deleted_at, deletion_due_at
                FROM rides
                WHERE user_id=%s AND deleted_at IS NOT NULL
                  AND deletion_due_at > CURRENT_TIMESTAMP
                ORDER BY deleted_at DESC
                """,
                (principal.user_id,)
            )
            rows = cur.fetchall()
    finally:
        conn.close()
    return [
        {
            "ride_session_id": row[0], "started_at": row[1], "ended_at": row[2],
            "elapsed_seconds": row[3], "total_distance_meters": row[4],
            "deleted_at": row[5].isoformat(), "deletion_due_at": row[6].isoformat()
        }
        for row in rows
    ]


@app.post("/rides/{ride_session_id}/restore")
def restore_ride(ride_session_id: str, request: Request):
    principal = require_principal(request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE rides SET deleted_at=NULL, deletion_due_at=NULL,
                    updated_at=CURRENT_TIMESTAMP
                WHERE ride_session_id=%s AND user_id=%s
                  AND deletion_due_at > CURRENT_TIMESTAMP
                RETURNING ride_session_id
                """,
                (ride_session_id, principal.user_id)
            )
            restored = cur.fetchone()
            if restored is None:
                raise HTTPException(status_code=404, detail="recoverable ride not found")
            audit(cur, principal.user_id, "RIDE_RESTORED", "RIDE", ride_session_id)
        conn.commit()
    finally:
        conn.close()
    return {"message": "ride restored", "ride_session_id": ride_session_id}


@app.delete("/rides/{ride_session_id}/permanent")
def permanently_delete_ride(ride_session_id: str, request: Request):
    """Finalize a user's recoverable ride deletion immediately."""
    principal = require_principal(request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT 1 FROM rides
                WHERE ride_session_id=%s AND user_id=%s
                  AND deleted_at IS NOT NULL
                FOR UPDATE
                """,
                (ride_session_id, principal.user_id)
            )
            if cur.fetchone() is None:
                raise HTTPException(status_code=404, detail="recoverable ride not found")

            retained_sensors, retained_accidents, deleted = finalize_ride_deletion(
                cur, ride_session_id, principal.user_id
            )
            if deleted != 1:
                raise HTTPException(status_code=409, detail="ride deletion could not be finalized")
            audit(
                cur,
                principal.user_id,
                "RIDE_DELETION_FINALIZED",
                "RIDE",
                ride_session_id,
                {
                    "trigger": "USER_IMMEDIATE",
                    "retained_anonymized_sensors": retained_sensors,
                    "retained_anonymized_accidents": retained_accidents,
                }
            )
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
    return {
        "message": "ride permanently deleted",
        "ride_session_id": ride_session_id,
        "retained_anonymized_sensors": retained_sensors,
        "retained_anonymized_accidents": retained_accidents,
    }


@app.post("/upload-image")
def upload_image(
    http_request: Request,
    file: UploadFile = File(...),
    event_uuid: str | None = Form(default=None)
):
    principal = ingest_principal(http_request)
    if file.content_type not in ("image/jpeg", "image/jpg"):
        raise HTTPException(status_code=415, detail="only JPEG images are allowed")
    first_chunk = file.file.read(1024 * 1024)
    if not first_chunk.startswith(b"\xff\xd8\xff"):
        raise HTTPException(status_code=415, detail="invalid JPEG data")
    try:
        image_id = str(uuid.UUID(event_uuid)) if event_uuid else str(uuid.uuid4())
    except ValueError:
        raise HTTPException(status_code=422, detail="invalid event_uuid")
    relative_path = f"events/{image_id[:2]}/{image_id}.jpg"
    save_path = (IMAGE_UPLOAD_DIR / relative_path).resolve()
    if save_path.is_file():
        return {"message": "image already uploaded", "image_path": relative_path}
    save_path.parent.mkdir(parents=True, exist_ok=True)
    written = 0
    try:
        with save_path.open("wb") as buffer:
            chunk = first_chunk
            while chunk:
                written += len(chunk)
                if written > MAX_IMAGE_BYTES:
                    raise HTTPException(status_code=413, detail="image exceeds 10 MB")
                buffer.write(chunk)
                chunk = file.file.read(1024 * 1024)
    except Exception:
        if save_path.exists():
            save_path.unlink()
        raise

    record_demo_event_safely(
        category="IMAGE",
        event_type="IMAGE_STORED",
        message="주행 원본 이미지 1장을 저장했습니다.",
        status="저장 완료",
        event_uuid=image_id,
        user_id=principal.user_id if principal else None,
        device_id=principal.device_id if principal else None,
        details={"image_count": 1},
    )

    return {
        "message": "image uploaded",
        "image_path": relative_path
    }


@app.get("/sensor-logs/{event_uuid}/image")
def get_sensor_log_image(event_uuid: str, request: Request):
    principal = require_principal(request)
    try:
        normalized_event_uuid = str(uuid.UUID(event_uuid))
    except ValueError:
        raise HTTPException(status_code=422, detail="invalid event_uuid")

    with db_transaction() as cur:
        cur.execute(
            """
            SELECT s.image_path,j.annotated_image_path,j.ai_analysis_job_id
            FROM sensor_logs s
            LEFT JOIN ai_analysis_jobs j ON j.sensor_log_id=s.sensor_log_id
            WHERE s.event_uuid=%s AND s.user_id=%s
            LIMIT 1
            """,
            (normalized_event_uuid, principal.user_id),
        )
        row = cur.fetchone()
        detections = []
        if row and row[2] is not None:
            cur.execute(
                """
                SELECT ai_detection_id,stage,model_version,class_name,confidence,
                       bbox_xyxy,polygon
                FROM ai_detections WHERE ai_analysis_job_id=%s
                ORDER BY stage,confidence DESC,ai_detection_id
                """,
                (row[2],),
            )
            detections = [
                {"class_name": item[3], "confidence": item[4],
                 "bbox_xyxy": item[5], "polygon": item[6], "stage": item[1]}
                for item in merge_eligible_detections(cur.fetchall())
            ]
    if row is None:
        raise HTTPException(status_code=404, detail="sensor log not found")
    if not row[0]:
        raise HTTPException(status_code=404, detail="image was not captured or has expired")

    image_file = _first_existing_stored_image_path(row[0])
    if image_file is None:
        raise HTTPException(status_code=404, detail="image file not found or has expired")
    content = render_detection_image(image_file, detections, show_confidence=False)
    return Response(
        content=content,
        media_type="image/jpeg",
        headers={
            "Cache-Control": "private, max-age=3600",
            "Content-Disposition": f'inline; filename="{normalized_event_uuid}.jpg"',
        },
    )


@app.get("/auth/data-summary")
def user_data_summary(request: Request):
    principal = require_principal(request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            counts = {}
            for key, table_name in (
                ("rides", "rides"),
                ("sensor_logs", "sensor_logs"),
                ("accident_events", "accident_events"),
            ):
                if table_name == "rides":
                    cur.execute(
                        "SELECT COUNT(*) FROM rides WHERE user_id=%s AND deleted_at IS NULL",
                        (principal.user_id,)
                    )
                else:
                    cur.execute(
                        f"SELECT COUNT(*) FROM {table_name} WHERE user_id=%s",
                        (principal.user_id,)
                    )
                counts[key] = cur.fetchone()[0]
            cur.execute(
                "SELECT COUNT(*) FROM rides WHERE user_id=%s AND deleted_at IS NOT NULL",
                (principal.user_id,)
            )
            counts["recoverable_rides"] = cur.fetchone()[0]
    finally:
        conn.close()
    return {
        **counts,
        "retention": {
            "raw_gps_sensor_image_days": 365,
            "system_log_days": 90,
            "ride_recovery_days": RIDE_RECOVERY_DAYS,
        }
    }


@app.get("/admin/diagnostics")
def admin_diagnostics(request: Request):
    principal = require_admin(request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT COUNT(*) FROM users WHERE is_active=TRUE")
            users = cur.fetchone()[0]
            cur.execute("SELECT COUNT(*) FROM rides WHERE deleted_at IS NULL")
            rides = cur.fetchone()[0]
            cur.execute("SELECT COUNT(*) FROM rides WHERE deleted_at IS NOT NULL")
            pending_deletions = cur.fetchone()[0]
            cur.execute("SELECT COUNT(*) FROM sensor_logs")
            sensor_logs = cur.fetchone()[0]
            cur.execute("SELECT COUNT(*) FROM accident_events")
            accidents = cur.fetchone()[0]
            cur.execute("SELECT MAX(created_at) FROM system_logs")
            last_system_log_at = cur.fetchone()[0]
            cur.execute(
                """
                SELECT
                    count(*) FILTER (WHERE status='WAITING'),
                    count(*) FILTER (WHERE status='ORPHAN'),
                    count(*) FILTER (WHERE status='RESOLVED')
                FROM orphan_sensor_sessions
                """
            )
            orphan_waiting, orphan_confirmed, orphan_resolved = cur.fetchone()
            cur.execute("SELECT COUNT(*) FROM sensor_logs WHERE event_uuid IS NULL")
            legacy_sensor_without_uuid = cur.fetchone()[0]
            cur.execute(
                "SELECT image_path FROM sensor_logs WHERE image_path IS NOT NULL"
            )
            image_paths = [row[0] for row in cur.fetchall()]
            cur.execute(
                "SELECT version FROM schema_migrations ORDER BY version DESC LIMIT 1"
            )
            latest_migration = cur.fetchone()
            audit(
                cur, principal.user_id, "ADMIN_DIAGNOSTICS_VIEWED", "SYSTEM", None
            )
        conn.commit()
    finally:
        conn.close()
    missing_image_files = sum(
        1
        for image_path in image_paths
        if (candidate := _stored_image_path(image_path)) is None
        or not candidate.is_file()
    )
    return {
        "users": users,
        "rides": rides,
        "pending_deletions": pending_deletions,
        "sensor_logs": sensor_logs,
        "accident_events": accidents,
        "last_system_log_at": last_system_log_at.isoformat() if last_system_log_at else None,
        "image_storage_directory": str(IMAGE_UPLOAD_DIR),
        "image_max_bytes": MAX_IMAGE_BYTES,
        "orphan_sensor_waiting": orphan_waiting,
        "orphan_sensor_confirmed": orphan_confirmed,
        "orphan_sensor_resolved": orphan_resolved,
        "legacy_sensor_without_uuid": legacy_sensor_without_uuid,
        "referenced_images": len(image_paths),
        "missing_image_files": missing_image_files,
        "latest_migration": latest_migration[0] if latest_migration else None,
    }


@app.get("/admin/dashboard")
def admin_dashboard(request: Request):
    principal = require_admin(request)
    with db_transaction() as cur:
        cur.execute(
            """
            SELECT
                (SELECT COUNT(*) FROM users WHERE is_active=TRUE),
                (SELECT COUNT(*) FROM users
                 WHERE created_at >= CURRENT_DATE),
                (SELECT COUNT(*) FROM devices),
                (SELECT COUNT(*) FROM rides WHERE deleted_at IS NULL),
                (SELECT COUNT(*) FROM rides
                 WHERE deleted_at IS NULL
                   AND created_at >= CURRENT_DATE),
                (SELECT COUNT(*) FROM sensor_logs),
                (SELECT COUNT(*) FROM sensor_logs
                 WHERE image_path IS NOT NULL AND image_path <> ''),
                (SELECT COUNT(*) FROM accident_events),
                (SELECT COUNT(*) FROM hazard_zones WHERE status='CANDIDATE'),
                (SELECT COUNT(*) FROM hazard_zones WHERE status='CONFIRMED'),
                (SELECT COUNT(*) FROM rides WHERE deleted_at IS NOT NULL),
                (SELECT COUNT(*) FROM system_logs
                 WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
                   AND level IN ('WARN', 'ERROR'))
            """
        )
        row = cur.fetchone()
        audit(cur, principal.user_id, "ADMIN_DASHBOARD_VIEWED", "SYSTEM", None)
    return {
        "active_users": row[0],
        "new_users_today": row[1],
        "registered_devices": row[2],
        "rides": row[3],
        "rides_today": row[4],
        "sensor_logs": row[5],
        "images": row[6],
        "accident_events": row[7],
        "candidate_hazard_zones": row[8],
        "confirmed_hazard_zones": row[9],
        "pending_ride_deletions": row[10],
        "warnings_last_24_hours": row[11],
    }


@app.get("/admin/users")
def admin_users(
    request: Request,
    q: str = Query(default="", max_length=100),
    status: str = Query(default="ALL"),
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
):
    principal = require_admin(request)
    normalized_status = status.upper()
    if normalized_status not in {"ALL", "ACTIVE", "INACTIVE"}:
        raise HTTPException(status_code=422, detail="unsupported user status")
    search = f"%{q.strip()}%"
    active_filter = {
        "ALL": "",
        "ACTIVE": "AND u.is_active=TRUE",
        "INACTIVE": "AND u.is_active=FALSE",
    }[normalized_status]
    with db_transaction() as cur:
        cur.execute(
            f"""
            SELECT u.user_id, u.email, u.nickname, u.phone, u.role, u.is_active,
                   u.created_at, u.updated_at,
                   (SELECT COUNT(*) FROM devices d WHERE d.user_id=u.user_id),
                   (SELECT MAX(d.last_seen_at) FROM devices d WHERE d.user_id=u.user_id),
                   (SELECT COUNT(*) FROM rides r
                    WHERE r.user_id=u.user_id AND r.deleted_at IS NULL),
                   (SELECT COUNT(*) FROM sensor_logs s WHERE s.user_id=u.user_id),
                   (SELECT COUNT(*) FROM accident_events a WHERE a.user_id=u.user_id)
            FROM users u
            WHERE (%s='' OR u.email ILIKE %s OR u.nickname ILIKE %s
                   OR COALESCE(u.phone, '') ILIKE %s)
              {active_filter}
            ORDER BY u.created_at DESC, u.user_id DESC
            LIMIT %s OFFSET %s
            """,
            (q.strip(), search, search, search, limit, offset),
        )
        rows = cur.fetchall()
        cur.execute(
            f"""
            SELECT COUNT(*) FROM users u
            WHERE (%s='' OR u.email ILIKE %s OR u.nickname ILIKE %s
                   OR COALESCE(u.phone, '') ILIKE %s)
              {active_filter}
            """,
            (q.strip(), search, search, search),
        )
        total = cur.fetchone()[0]
        audit(
            cur,
            principal.user_id,
            "ADMIN_USERS_VIEWED",
            "USER",
            None,
            {"query": q.strip(), "status": normalized_status},
        )
    return {
        "total": total,
        "items": [
            {
                "user_id": row[0],
                "email": row[1],
                "nickname": row[2],
                "phone": row[3],
                "role": row[4],
                "is_active": row[5],
                "created_at": row[6].isoformat(),
                "updated_at": row[7].isoformat(),
                "device_count": row[8],
                "last_seen_at": row[9].isoformat() if row[9] else None,
                "ride_count": row[10],
                "sensor_log_count": row[11],
                "accident_count": row[12],
            }
            for row in rows
        ],
    }


@app.get("/admin/users/{user_id}")
def admin_user_detail(request: Request, user_id: int):
    principal = require_admin(request)
    with db_transaction() as cur:
        cur.execute(
            """
            SELECT u.user_id, u.email, u.nickname, u.phone, u.role, u.is_active,
                   u.created_at, u.updated_at,
                   (SELECT COUNT(*) FROM devices d WHERE d.user_id=u.user_id),
                   (SELECT MAX(d.last_seen_at) FROM devices d WHERE d.user_id=u.user_id),
                   (SELECT COUNT(*) FROM rides r
                    WHERE r.user_id=u.user_id AND r.deleted_at IS NULL),
                   (SELECT COUNT(*) FROM rides r
                    WHERE r.user_id=u.user_id AND r.deleted_at IS NOT NULL),
                   (SELECT COUNT(*) FROM sensor_logs s WHERE s.user_id=u.user_id),
                   (SELECT COUNT(*) FROM sensor_logs s
                    WHERE s.user_id=u.user_id
                      AND s.image_path IS NOT NULL AND s.image_path <> ''),
                   (SELECT COUNT(*) FROM accident_events a WHERE a.user_id=u.user_id)
            FROM users u WHERE u.user_id=%s
            """,
            (user_id,),
        )
        row = cur.fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="user not found")
        cur.execute(
            """
            SELECT ride_session_id, started_at, ended_at, elapsed_seconds,
                   total_distance_meters, collected_frame_count, accident_count
            FROM rides
            WHERE user_id=%s AND deleted_at IS NULL
            ORDER BY ended_at DESC LIMIT 10
            """,
            (user_id,),
        )
        rides = cur.fetchall()
        audit(
            cur,
            principal.user_id,
            "ADMIN_USER_DETAIL_VIEWED",
            "USER",
            str(user_id),
        )
    return {
        "user_id": row[0],
        "email": row[1],
        "nickname": row[2],
        "phone": row[3],
        "role": row[4],
        "is_active": row[5],
        "created_at": row[6].isoformat(),
        "updated_at": row[7].isoformat(),
        "device_count": row[8],
        "last_seen_at": row[9].isoformat() if row[9] else None,
        "ride_count": row[10],
        "recoverable_ride_count": row[11],
        "sensor_log_count": row[12],
        "image_count": row[13],
        "accident_count": row[14],
        "recent_rides": [
            {
                "ride_session_id": ride[0],
                "started_at": ride[1],
                "ended_at": ride[2],
                "elapsed_seconds": ride[3],
                "total_distance_meters": ride[4],
                "collected_frame_count": ride[5],
                "accident_count": ride[6],
            }
            for ride in rides
        ],
    }


@app.get("/admin/rides")
def admin_rides(
    request: Request,
    q: str = Query(default="", max_length=100),
    status: str = Query(default="ALL"),
    year: int | None = Query(default=None, ge=2000, le=2100),
    month: int | None = Query(default=None, ge=1, le=12),
    limit: int = Query(default=50, ge=1, le=200),
    offset: int = Query(default=0, ge=0),
):
    principal = require_admin(request)
    normalized_status = status.upper()
    if normalized_status not in {"ALL", "COMPLETE", "INCOMPLETE", "DELETED"}:
        raise HTTPException(status_code=422, detail="unsupported ride status")
    if (year is None) != (month is None):
        raise HTTPException(status_code=422, detail="year and month must be provided together")

    period_filter = ""
    period_params: tuple[int, int] = ()
    if year is not None and month is not None:
        kst = ZoneInfo("Asia/Seoul")
        period_start = datetime(year, month, 1, tzinfo=kst)
        next_year, next_month = (year + 1, 1) if month == 12 else (year, month + 1)
        period_end = datetime(next_year, next_month, 1, tzinfo=kst)
        period_filter = "AND r.ended_at >= %s AND r.ended_at < %s"
        period_params = (
            int(period_start.timestamp() * 1000),
            int(period_end.timestamp() * 1000),
        )
    search = f"%{q.strip()}%"
    status_filter = {
        "ALL": "",
        "COMPLETE": (
            "AND r.deleted_at IS NULL "
            "AND (SELECT COUNT(*) FROM sensor_logs s "
            "     WHERE s.ride_session_id=r.ride_session_id) >= r.collected_frame_count "
            "AND (SELECT COUNT(*) FROM accident_events a "
            "     WHERE a.ride_session_id=r.ride_session_id) >= r.accident_count"
        ),
        "INCOMPLETE": (
            "AND r.deleted_at IS NULL AND ("
            "(SELECT COUNT(*) FROM sensor_logs s "
            " WHERE s.ride_session_id=r.ride_session_id) < r.collected_frame_count OR "
            "(SELECT COUNT(*) FROM accident_events a "
            " WHERE a.ride_session_id=r.ride_session_id) < r.accident_count)"
        ),
        "DELETED": "AND r.deleted_at IS NOT NULL",
    }[normalized_status]
    base_where = f"""
        WHERE (%s='' OR r.ride_session_id ILIKE %s OR u.email ILIKE %s
               OR u.nickname ILIKE %s)
          {status_filter}
          {period_filter}
    """
    with db_transaction() as cur:
        cur.execute(
            f"""
            SELECT r.ride_session_id, r.user_id, u.email, u.nickname, r.device_id,
                   r.started_at, r.ended_at, r.elapsed_seconds,
                   r.total_distance_meters, r.average_speed_kmh, r.max_speed_kmh,
                   r.collected_frame_count, r.accident_count, r.deleted_at,
                   (SELECT COUNT(*) FROM sensor_logs s
                    WHERE s.ride_session_id=r.ride_session_id),
                   (SELECT COUNT(*) FROM sensor_logs s
                    WHERE s.ride_session_id=r.ride_session_id
                      AND s.image_path IS NOT NULL AND s.image_path <> ''),
                   (SELECT COUNT(*) FROM accident_events a
                    WHERE a.ride_session_id=r.ride_session_id)
            FROM rides r
            LEFT JOIN users u ON u.user_id=r.user_id
            {base_where}
            ORDER BY r.ended_at DESC
            LIMIT %s OFFSET %s
            """,
            (q.strip(), search, search, search, *period_params, limit, offset),
        )
        rows = cur.fetchall()
        cur.execute(
            f"""
            SELECT COUNT(*)
            FROM rides r
            LEFT JOIN users u ON u.user_id=r.user_id
            {base_where}
            """,
            (q.strip(), search, search, search, *period_params),
        )
        total = cur.fetchone()[0]
        audit(
            cur,
            principal.user_id,
            "ADMIN_RIDES_VIEWED",
            "RIDE",
            None,
            {"query": q.strip(), "status": normalized_status, "year": year, "month": month},
        )
    return {
        "total": total,
        "items": [
            {
                "ride_session_id": row[0],
                "user_id": row[1],
                "email": row[2],
                "nickname": row[3],
                "device_id": row[4],
                "started_at": row[5],
                "ended_at": row[6],
                "elapsed_seconds": row[7],
                "total_distance_meters": row[8],
                "average_speed_kmh": row[9],
                "max_speed_kmh": row[10],
                "expected_sensor_logs": row[11],
                "expected_accidents": row[12],
                "deleted_at": row[13].isoformat() if row[13] else None,
                "received_sensor_logs": row[14],
                "image_count": row[15],
                "received_accidents": row[16],
                "sync_status": (
                    "DELETED" if row[13]
                    else "COMPLETE"
                    if row[14] >= row[11] and row[16] >= row[12]
                    else "INCOMPLETE"
                ),
            }
            for row in rows
        ],
    }


@app.get("/admin/rides/{ride_session_id}")
def admin_ride_detail(request: Request, ride_session_id: str):
    principal = require_admin(request)
    with db_transaction() as cur:
        cur.execute(
            """
            SELECT r.ride_session_id, r.user_id, u.email, u.nickname, r.device_id,
                   r.started_at, r.ended_at, r.elapsed_seconds,
                   r.total_distance_meters, r.average_speed_kmh, r.max_speed_kmh,
                   r.collected_frame_count, r.accident_count, r.deleted_at
            FROM rides r
            LEFT JOIN users u ON u.user_id=r.user_id
            WHERE r.ride_session_id=%s
            """,
            (ride_session_id,),
        )
        ride = cur.fetchone()
        if ride is None:
            raise HTTPException(status_code=404, detail="ride not found")
        cur.execute(
            "SELECT COUNT(*) FROM sensor_logs WHERE ride_session_id=%s",
            (ride_session_id,),
        )
        sensor_total = cur.fetchone()[0]
        cur.execute(
            """
            WITH recent AS (
                SELECT sensor_log_id, event_uuid, captured_at, latitude, longitude,
                       accuracy, speed, accel_x, accel_y, accel_z,
                       gyro_x, gyro_y, gyro_z,
                       geomagnetic_x, geomagnetic_y, geomagnetic_z, image_path
                FROM sensor_logs
                WHERE ride_session_id=%s
                ORDER BY captured_at DESC, sensor_log_id DESC
                LIMIT 100
            ), image_records AS (
                SELECT sensor_log_id, event_uuid, captured_at, latitude, longitude,
                       accuracy, speed, accel_x, accel_y, accel_z,
                       gyro_x, gyro_y, gyro_z,
                       geomagnetic_x, geomagnetic_y, geomagnetic_z, image_path
                FROM sensor_logs
                WHERE ride_session_id=%s
                  AND image_path IS NOT NULL AND image_path <> ''
            )
            SELECT event_uuid, captured_at, latitude, longitude, accuracy, speed,
                   accel_x, accel_y, accel_z, gyro_x, gyro_y, gyro_z,
                   geomagnetic_x, geomagnetic_y, geomagnetic_z, image_path
            FROM (
                SELECT * FROM recent
                UNION
                SELECT * FROM image_records
            ) AS combined
            ORDER BY captured_at DESC, sensor_log_id DESC
            """,
            (ride_session_id, ride_session_id),
        )
        sensors = cur.fetchall()
        cur.execute(
            """
            SELECT event_uuid, detected_at, latitude, longitude, accuracy, speed,
                   acceleration_magnitude, gyro_magnitude,
                   confirmation_status, is_false_positive
            FROM accident_events
            WHERE ride_session_id=%s
            ORDER BY detected_at DESC
            """,
            (ride_session_id,),
        )
        accidents = cur.fetchall()
        audit(
            cur,
            principal.user_id,
            "ADMIN_RIDE_DETAIL_VIEWED",
            "RIDE",
            ride_session_id,
        )
    return {
        "ride": {
            "ride_session_id": ride[0],
            "user_id": ride[1],
            "email": ride[2],
            "nickname": ride[3],
            "device_id": ride[4],
            "started_at": ride[5],
            "ended_at": ride[6],
            "elapsed_seconds": ride[7],
            "total_distance_meters": ride[8],
            "average_speed_kmh": ride[9],
            "max_speed_kmh": ride[10],
            "expected_sensor_logs": ride[11],
            "expected_accidents": ride[12],
            "deleted_at": ride[13].isoformat() if ride[13] else None,
        },
        "sensor_total": sensor_total,
        "sensors_limited": sensor_total > len(sensors),
        "sensors": [
            {
                "event_uuid": row[0],
                "captured_at": row[1],
                "latitude": row[2],
                "longitude": row[3],
                "accuracy": row[4],
                "speed": row[5],
                "accel_x": row[6],
                "accel_y": row[7],
                "accel_z": row[8],
                "gyro_x": row[9],
                "gyro_y": row[10],
                "gyro_z": row[11],
                "geomagnetic_x": row[12],
                "geomagnetic_y": row[13],
                "geomagnetic_z": row[14],
                "has_image": bool(row[15]),
            }
            for row in sensors
        ],
        "accidents": [
            {
                "event_uuid": row[0],
                "detected_at": row[1],
                "latitude": row[2],
                "longitude": row[3],
                "accuracy": row[4],
                "speed": row[5],
                "acceleration_magnitude": row[6],
                "gyro_magnitude": row[7],
                "confirmation_status": row[8],
                "is_false_positive": row[9],
            }
            for row in accidents
        ],
    }


@app.get("/admin/sensor-logs/{event_uuid}/image")
def admin_sensor_log_image(request: Request, event_uuid: str):
    principal = require_admin(request)
    try:
        normalized_event_uuid = str(uuid.UUID(event_uuid))
    except ValueError:
        raise HTTPException(status_code=422, detail="invalid event_uuid")
    with db_transaction() as cur:
        cur.execute(
            """
            SELECT s.image_path,j.annotated_image_path,j.ai_analysis_job_id
            FROM sensor_logs s
            LEFT JOIN ai_analysis_jobs j ON j.sensor_log_id=s.sensor_log_id
            WHERE s.event_uuid=%s LIMIT 1
            """,
            (normalized_event_uuid,),
        )
        row = cur.fetchone()
        detections = []
        if row and row[2] is not None:
            cur.execute(
                """
                SELECT ai_detection_id,stage,model_version,class_name,confidence,
                       bbox_xyxy,polygon
                FROM ai_detections WHERE ai_analysis_job_id=%s
                ORDER BY stage,confidence DESC,ai_detection_id
                """,
                (row[2],),
            )
            detections = [
                {"class_name": item[3], "confidence": item[4],
                 "bbox_xyxy": item[5], "polygon": item[6], "stage": item[1]}
                for item in merge_eligible_detections(cur.fetchall())
            ]
        audit(
            cur,
            principal.user_id,
            "ADMIN_SENSOR_IMAGE_VIEWED",
            "SENSOR_LOG",
            normalized_event_uuid,
        )
    if row is None:
        raise HTTPException(status_code=404, detail="sensor log not found")
    if not row[0]:
        raise HTTPException(status_code=404, detail="image was not captured or has expired")
    image_file = _first_existing_stored_image_path(row[0])
    if image_file is None:
        raise HTTPException(status_code=404, detail="image file not found or has expired")
    content = render_detection_image(image_file, detections, show_confidence=True)
    return Response(
        content=content,
        media_type="image/jpeg",
        headers={
            "Cache-Control": "private, max-age=300",
            "Content-Disposition": f'inline; filename="{normalized_event_uuid}.jpg"',
        },
    )


@app.get("/admin/review-items/{review_item_id}/image")
def admin_review_item_image(request: Request, review_item_id: int):
    """Render only eligible detections for a review item.

    Review items are created when no eligible detection exists. Raw model rows
    are still retained for audit, so they must not be drawn on this screen.
    If a later re-analysis produces an eligible result, draw only those rows;
    otherwise return the untouched source image.
    """
    principal = require_admin(request)
    with db_transaction() as cur:
        cur.execute(
            """
            SELECT r.event_uuid,r.image_path,s.image_path,j.ai_analysis_job_id
            FROM ai_review_items r
            JOIN sensor_logs s ON s.sensor_log_id=r.sensor_log_id
            LEFT JOIN LATERAL (
                SELECT ai_analysis_job_id
                FROM ai_analysis_jobs
                WHERE sensor_log_id=s.sensor_log_id
                ORDER BY ai_analysis_job_id DESC
                LIMIT 1
            ) j ON TRUE
            WHERE r.review_item_id=%s
            """,
            (review_item_id,),
        )
        row = cur.fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="review item not found")
        raw_detections = []
        if row[3] is not None:
            cur.execute(
                """
                SELECT ai_detection_id,stage,model_version,class_name,confidence,
                       bbox_xyxy,polygon
                FROM ai_detections
                WHERE ai_analysis_job_id=%s
                ORDER BY stage,confidence DESC,ai_detection_id
                """,
                (row[3],),
            )
            raw_detections = cur.fetchall()
        eligible = merge_eligible_detections(raw_detections)
        detections = [
            {
                "class_name": item[3], "confidence": item[4],
                "bbox_xyxy": item[5], "polygon": item[6], "stage": item[1],
            }
            for item in eligible
        ]
        audit(cur, principal.user_id, "ADMIN_REVIEW_ITEM_IMAGE_VIEWED", "AI_REVIEW", str(review_item_id))
    if not row[1] and not row[2]:
        raise HTTPException(status_code=404, detail="image was not captured or has expired")
    # Always start from the untouched source. With zero eligible detections this
    # returns the original image; with eligible detections only those are drawn.
    # Review rows keep their own immutable path snapshot. Prefer it, then fall
    # back to the sensor row because historical path-normalization jobs may
    # have updated only one of the two records.
    image_file = _first_existing_stored_image_path(row[1], row[2])
    if image_file is None:
        raise HTTPException(status_code=404, detail="image file not found or has expired")
    if not detections:
        # No eligible result: preserve the exact source bytes without drawing
        # raw low-confidence or duplicate model rows.
        content = image_file.read_bytes()
    else:
        content = render_detection_image(image_file, detections, show_confidence=True)
    return Response(
        content=content,
        media_type="image/jpeg",
        headers={
            "Cache-Control": "private, max-age=300",
            "Content-Disposition": f'inline; filename="review-{review_item_id}.jpg"',
        },
    )


@app.get("/admin/review-items")
def admin_review_items(
    request: Request,
    status: str = Query(default="PENDING"),
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
):
    principal = require_admin(request)
    normalized_status = status.upper()
    if normalized_status not in {"ALL", "PENDING", "CANDIDATE_REGISTERED", "NO_ISSUE"}:
        raise HTTPException(status_code=422, detail="unsupported review item status")
    with db_transaction() as cur:
        cur.execute(
            """
            SELECT review_item_id,sensor_log_id,event_uuid,ride_session_id,image_path,
                   captured_at,peak_sample_at,latitude,longitude,peak_impact_delta,
                   speed_median,status,selected_classes,candidate_zone_ids,
                   reviewed_at,created_at
            FROM ai_review_items
            WHERE (%s='ALL' OR status=%s)
            ORDER BY CASE WHEN status='PENDING' THEN 0 ELSE 1 END,
                     created_at DESC,review_item_id DESC
            LIMIT %s OFFSET %s
            """,
            (normalized_status, normalized_status, limit, offset),
        )
        rows = cur.fetchall()
        cur.execute("SELECT COUNT(*) FROM ai_review_items WHERE status='PENDING'")
        pending_count = cur.fetchone()[0]
        audit(cur, principal.user_id, "ADMIN_REVIEW_ITEMS_VIEWED", "AI_REVIEW", None)
    return {
        "items": [
            {
                "review_item_id": row[0],
                "sensor_log_id": row[1],
                "event_uuid": row[2],
                "ride_session_id": row[3],
                "image_path": row[4],
                "captured_at": row[5],
                "peak_sample_at": row[6],
                "latitude": row[7],
                "longitude": row[8],
                "peak_impact_delta": row[9],
                "speed_median": row[10],
                "status": row[11],
                "selected_classes": row[12] or [],
                "candidate_zone_ids": row[13] or [],
                "reviewed_at": row[14].isoformat() if row[14] else None,
                "created_at": row[15].isoformat(),
            }
            for row in rows
        ],
        "pending_count": pending_count,
        "status": normalized_status,
    }


@app.get("/admin/review-items/{review_item_id}")
def admin_review_item_detail(request: Request, review_item_id: int):
    principal = require_admin(request)
    with db_transaction() as cur:
        cur.execute(
            """
            SELECT review_item_id,sensor_log_id,event_uuid,ride_session_id,image_path,
                   captured_at,peak_sample_at,latitude,longitude,peak_impact_delta,
                   speed_median,sensor_samples,status,selected_classes,candidate_zone_ids,
                   no_issue_delete_after,reviewed_by,reviewed_at,created_at
            FROM ai_review_items WHERE review_item_id=%s
            """,
            (review_item_id,),
        )
        row = cur.fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="review item not found")
        audit(cur, principal.user_id, "ADMIN_REVIEW_ITEM_VIEWED", "AI_REVIEW", str(review_item_id))
    return {
        "review_item_id": row[0], "sensor_log_id": row[1], "event_uuid": row[2],
        "ride_session_id": row[3], "image_path": row[4], "captured_at": row[5],
        "peak_sample_at": row[6], "latitude": row[7], "longitude": row[8],
        "peak_impact_delta": row[9], "speed_median": row[10],
        "sensor_samples": row[11] or [], "status": row[12],
        "selected_classes": row[13] or [], "candidate_zone_ids": row[14] or [],
        "no_issue_delete_after": row[15], "reviewed_by": row[16],
        "reviewed_at": row[17].isoformat() if row[17] else None,
        "created_at": row[18].isoformat(),
    }


@app.post("/admin/review-items/{review_item_id}/register")
def admin_register_review_item(
    request: Request,
    review_item_id: int,
    body: ReviewItemCandidateRequest,
):
    principal = require_admin(request)
    candidate_zone_ids = []
    with db_transaction() as cur:
        cur.execute(
            """
            SELECT review_item_id,sensor_log_id,event_uuid,ride_session_id,latitude,
                   longitude,peak_impact_delta,speed_median,captured_at
            FROM ai_review_items
            WHERE review_item_id=%s AND status='PENDING'
            FOR UPDATE
            """,
            (review_item_id,),
        )
        item = cur.fetchone()
        if item is None:
            raise HTTPException(status_code=404, detail="pending review item not found")
        if item[1] is None or item[4] is None or item[5] is None:
            raise HTTPException(status_code=409, detail="review item has no location evidence")
        for class_name in body.classes:
            damage_type, _confidence_threshold, radius, title = CLASS_RULES[class_name]
            cur.execute(
                """
                SELECT zone_id::text,
                       SQRT(POWER((latitude-%s)*111000.0,2)+
                            POWER((longitude-%s)*91000.0,2))
                FROM hazard_zones
                WHERE zone_category='USER_HAZARD' AND road_damage_type=%s
                  AND status <> 'REJECTED'
                  AND ABS(latitude-%s)<=%s/111000.0
                  AND ABS(longitude-%s)<=%s/91000.0
                ORDER BY 2 LIMIT 1
                """,
                (item[4], item[5], damage_type, item[4], radius, item[5], radius),
            )
            existing = cur.fetchone()
            if existing is None:
                zone_id = str(uuid.uuid4())
                cur.execute(
                    """
                    INSERT INTO hazard_zones(
                        zone_id,title,road_damage_type,severity,latitude,longitude,
                        radius_meters,detection_count,description,source_model,
                        confidence,status,is_test_data,zone_category,is_user_visible,expires_at
                    ) VALUES (%s,%s,%s,'LOW',%s,%s,%s,1,%s,'ADMIN_REVIEW',NULL,'CANDIDATE',FALSE,
                              'USER_HAZARD',FALSE,CASE WHEN %s='flood_area'
                              THEN CURRENT_TIMESTAMP + INTERVAL '24 hours' END)
                    """,
                    (zone_id, title, damage_type, item[4], item[5], radius,
                     "관리자 재검토 등록 후보", damage_type),
                )
            else:
                zone_id = existing[0]
            cur.execute(
                """
                INSERT INTO hazard_zone_evidence(
                    zone_id,sensor_log_id,event_uuid,source_model,model_version,
                    confidence,distance_meters,details
                ) VALUES (%s,%s,%s,'ADMIN_REVIEW','manual-review-v1',NULL,0,%s)
                ON CONFLICT (zone_id,sensor_log_id) WHERE sensor_log_id IS NOT NULL
                DO UPDATE SET source_model='ADMIN_REVIEW',model_version='manual-review-v1',
                              confidence=NULL,details=EXCLUDED.details,
                              linked_at=CURRENT_TIMESTAMP
                """,
                (
                    zone_id, item[1], item[2], Json({
                        "review_item_id": review_item_id,
                        "manual_class": class_name,
                        "impact_delta_mps2": item[6],
                        "impact_speed_mps": item[7],
                        "distinct_ride_rule": "existing_auto_confirmation",
                    }),
                ),
            )
            # Recompute the candidate's evidence count/status/risk fields using
            # the same lifecycle routine as automatic evidence. Manual review
            # confidence is NULL, so this remains a candidate until the normal
            # AI-backed confirmation rule is satisfied.
            _refresh_zone(cur, zone_id, damage_type, "USER_HAZARD")
            candidate_zone_ids.append(zone_id)
        cur.execute(
            """
            UPDATE ai_review_items
            SET status='CANDIDATE_REGISTERED', selected_classes=%s,
                candidate_zone_ids=%s, reviewed_by=%s, reviewed_at=CURRENT_TIMESTAMP,
                updated_at=CURRENT_TIMESTAMP
            WHERE review_item_id=%s
            """,
            (Json(body.classes), Json(candidate_zone_ids), principal.user_id, review_item_id),
        )
        audit(cur, principal.user_id, "ADMIN_REVIEW_ITEM_REGISTERED", "AI_REVIEW",
              str(review_item_id), {"classes": body.classes, "zone_ids": candidate_zone_ids})
    return {"message": "review item registered as hazard candidate", "zone_ids": candidate_zone_ids}


@app.post("/admin/review-items/{review_item_id}/dismiss")
def admin_dismiss_review_item(request: Request, review_item_id: int):
    principal = require_admin(request)
    delete_after = int(time.time() * 1000) + 2 * 60 * 1000
    with db_transaction() as cur:
        cur.execute(
            """
            UPDATE ai_review_items
            SET status='NO_ISSUE', no_issue_delete_after=%s,
                reviewed_by=%s, reviewed_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
            WHERE review_item_id=%s AND status='PENDING'
            RETURNING sensor_log_id
            """,
            (delete_after, principal.user_id, review_item_id),
        )
        row = cur.fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="pending review item not found")
        cur.execute(
            """
            UPDATE sensor_logs
            SET image_delete_after=%s,
                image_retention_reason='AI_NO_DETECTION'
            WHERE sensor_log_id=%s
              AND NOT EXISTS (
                  SELECT 1 FROM accident_image_evidence
                  WHERE sensor_log_id=%s
              )
            """,
            (delete_after, row[0], row[0]),
        )
        audit(cur, principal.user_id, "ADMIN_REVIEW_ITEM_DISMISSED", "AI_REVIEW",
              str(review_item_id), {"image_delete_after": delete_after})
    return {"message": "review item dismissed", "image_delete_after": delete_after}


@app.get("/admin/hazard-zones")
def admin_hazard_zones(
    request: Request,
    q: str = Query(default="", max_length=100),
    status: str = Query(default="ALL"),
    severity: str = Query(default="ALL"),
    zone_category: str = Query(default="ALL"),
    include_test_data: bool = Query(default=True),
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
):
    principal = require_admin(request)
    normalized_status = status.upper()
    normalized_severity = severity.upper()
    normalized_category = zone_category.upper()
    if normalized_status not in {
        "ALL", "CANDIDATE", "CONFIRMED", "STALE", "ARCHIVED",
        "EXPIRED", "REJECTED",
    }:
        raise HTTPException(status_code=422, detail="unsupported hazard zone status")
    if normalized_severity not in {"ALL", "LOW", "MEDIUM", "HIGH"}:
        raise HTTPException(status_code=422, detail="unsupported hazard zone severity")
    if normalized_category not in {"ALL", "ROAD_ANOMALY", "USER_HAZARD"}:
        raise HTTPException(status_code=422, detail="unsupported hazard zone category")
    search = f"%{q.strip()}%"
    with db_transaction() as cur:
        cur.execute(
            """
            SELECT zone_id::text, title, road_damage_type, severity,
                   latitude, longitude, radius_meters, detection_count,
                   description, first_detected_at, last_detected_at,
                   source_model, confidence, status, is_test_data, updated_at,
                   zone_category,is_user_visible,expires_at,confirmation_source,
                   confirmation_cancelled_at
            FROM hazard_zones
            WHERE (%s='' OR title ILIKE %s OR description ILIKE %s
                   OR road_damage_type ILIKE %s)
              AND (%s='ALL' OR status=%s)
              AND (%s='ALL' OR severity=%s)
              AND (%s='ALL' OR zone_category=%s)
              AND (%s OR is_test_data=FALSE)
            ORDER BY
                CASE status
                    WHEN 'REVIEW_REQUIRED' THEN 0
                    WHEN 'CANDIDATE' THEN 1
                    WHEN 'CONFIRMED' THEN 2
                    WHEN 'STALE' THEN 3
                    WHEN 'ARCHIVED' THEN 4
                    ELSE 5
                END,
                updated_at DESC
            LIMIT %s OFFSET %s
            """,
            (
                q.strip(), search, search, search,
                normalized_status, normalized_status,
                normalized_severity, normalized_severity,
                normalized_category, normalized_category,
                include_test_data, limit, offset,
            ),
        )
        rows = cur.fetchall()
        cur.execute(
            """
            SELECT COUNT(*) FROM hazard_zones
            WHERE (%s='' OR title ILIKE %s OR description ILIKE %s
                   OR road_damage_type ILIKE %s)
              AND (%s='ALL' OR status=%s)
              AND (%s='ALL' OR severity=%s)
              AND (%s='ALL' OR zone_category=%s)
              AND (%s OR is_test_data=FALSE)
            """,
            (
                q.strip(), search, search, search,
                normalized_status, normalized_status,
                normalized_severity, normalized_severity,
                normalized_category, normalized_category,
                include_test_data,
            ),
        )
        total = cur.fetchone()[0]
        audit(
            cur,
            principal.user_id,
            "ADMIN_HAZARD_ZONES_VIEWED",
            "HAZARD_ZONE",
            None,
            {
                "query": q.strip(),
                "status": normalized_status,
                "severity": normalized_severity,
                "zone_category": normalized_category,
                "include_test_data": include_test_data,
            },
        )
    return {
        "total": total,
        "items": [
            {
                "zone_id": row[0],
                "title": row[1],
                "road_damage_type": row[2],
                "severity": row[3],
                "latitude": row[4],
                "longitude": row[5],
                "radius_meters": row[6],
                "detection_count": row[7],
                "description": row[8],
                "first_detected_at": row[9].isoformat(),
                "last_detected_at": row[10].isoformat(),
                "source_model": row[11],
                "confidence": row[12],
                "status": row[13],
                "is_test_data": row[14],
                "updated_at": row[15].isoformat(),
                "zone_category": row[16],
                "is_user_visible": row[17],
                "expires_at": row[18].isoformat() if row[18] else None,
                "confirmation_source": row[19],
                "confirmation_cancelled_at": row[20].isoformat() if row[20] else None,
            }
            for row in rows
        ],
    }


@app.get("/admin/hazard-zones/{zone_id}")
def admin_hazard_zone_detail(request: Request, zone_id: str):
    principal = require_admin(request)
    try:
        normalized_zone_id = str(uuid.UUID(zone_id))
    except ValueError:
        raise HTTPException(status_code=422, detail="invalid zone_id")
    with db_transaction() as cur:
        cur.execute(
            """
            SELECT zone_id::text, title, road_damage_type, severity,
                   latitude, longitude, radius_meters, detection_count,
                   description, first_detected_at, last_detected_at,
                   source_model, confidence, status, is_test_data, updated_at,
                   zone_category,is_user_visible,expires_at,confirmation_source,
                   confirmation_cancelled_at
            FROM hazard_zones WHERE zone_id=%s
            """,
            (normalized_zone_id,),
        )
        zone = cur.fetchone()
        if zone is None:
            raise HTTPException(status_code=404, detail="hazard zone not found")
        cur.execute(
            """
            SELECT COALESCE(e.event_uuid, s.event_uuid), s.ride_session_id,
                   s.captured_at, s.peak_sample_at, s.latitude, s.longitude,
                   s.accel_x, s.accel_y, s.accel_z,
                   s.gyro_x, s.gyro_y, s.gyro_z,
                   s.image_path, e.distance_meters
            FROM hazard_zone_evidence e
            JOIN sensor_logs s ON s.sensor_log_id=e.sensor_log_id
            WHERE e.zone_id=%s
            ORDER BY e.linked_at DESC, e.hazard_zone_evidence_id DESC
            LIMIT 20
            """,
            (normalized_zone_id,),
        )
        evidence = cur.fetchall()
        cur.execute(
            """
            SELECT
                COUNT(*) FILTER (WHERE feedback_type='DANGER'),
                COUNT(*) FILTER (WHERE feedback_type='CAUTION'),
                COUNT(*) FILTER (WHERE feedback_type='RESOLVED'),
                COUNT(*)
            FROM hazard_zone_feedback
            WHERE zone_id=%s
            """,
            (normalized_zone_id,),
        )
        feedback_counts = cur.fetchone()
        cur.execute(
            """
            SELECT f.feedback_type,u.nickname,f.updated_at
            FROM hazard_zone_feedback f
            JOIN users u ON u.user_id=f.user_id
            WHERE f.zone_id=%s
            ORDER BY f.updated_at DESC,f.feedback_id DESC
            LIMIT 20
            """,
            (normalized_zone_id,),
        )
        recent_feedback = cur.fetchall()
        audit(
            cur,
            principal.user_id,
            "ADMIN_HAZARD_ZONE_DETAIL_VIEWED",
            "HAZARD_ZONE",
            normalized_zone_id,
        )
    return {
        "zone": {
            "zone_id": zone[0],
            "title": zone[1],
            "road_damage_type": zone[2],
            "severity": zone[3],
            "latitude": zone[4],
            "longitude": zone[5],
            "radius_meters": zone[6],
            "detection_count": zone[7],
            "description": zone[8],
            "first_detected_at": zone[9].isoformat(),
            "last_detected_at": zone[10].isoformat(),
            "source_model": zone[11],
            "confidence": zone[12],
            "status": zone[13],
            "is_test_data": zone[14],
            "updated_at": zone[15].isoformat(),
            "zone_category": zone[16],
            "is_user_visible": zone[17],
            "expires_at": zone[18].isoformat() if zone[18] else None,
            "confirmation_source": zone[19],
            "confirmation_cancelled_at": zone[20].isoformat() if zone[20] else None,
        },
        "evidence": [
            {
                "event_uuid": row[0],
                "ride_session_id": row[1],
                "captured_at": row[2],
                "peak_sample_at": row[3],
                "latitude": row[4],
                "longitude": row[5],
                "accel_x": row[6],
                "accel_y": row[7],
                "accel_z": row[8],
                "gyro_x": row[9],
                "gyro_y": row[10],
                "gyro_z": row[11],
                "has_image": bool(row[12]),
                "distance_meters": row[13],
            }
            for row in evidence
        ],
        "feedback_summary": {
            "danger": feedback_counts[0],
            "caution": feedback_counts[1],
            "resolved": feedback_counts[2],
            "total": feedback_counts[3],
        },
        "recent_feedback": [
            {
                "feedback_type": row[0],
                "nickname": row[1],
                "updated_at": row[2].isoformat(),
            }
            for row in recent_feedback
        ],
    }


@app.get("/admin/hazard-zones/{zone_id}/evidence")
def admin_hazard_evidence(request: Request, zone_id: str):
    principal = require_admin(request)
    try:
        normalized_zone_id = str(uuid.UUID(zone_id))
    except ValueError:
        raise HTTPException(status_code=422, detail="invalid zone_id")
    with db_transaction() as cur:
        cur.execute(
            """
            SELECT e.hazard_zone_evidence_id, e.sensor_log_id, e.event_uuid,
                   e.source_model, e.model_version, e.confidence,
                   e.distance_meters, e.linked_at, e.details,
                   e.ai_detection_id, d.stage, d.class_name,
                   d.bbox_xyxy, d.polygon,
                   s.ride_session_id, s.captured_at, s.image_path IS NOT NULL
            FROM hazard_zone_evidence e
            LEFT JOIN sensor_logs s ON s.sensor_log_id=e.sensor_log_id
            LEFT JOIN ai_detections d ON d.ai_detection_id=e.ai_detection_id
            WHERE e.zone_id=%s
            ORDER BY e.linked_at DESC
            """,
            (normalized_zone_id,),
        )
        rows = cur.fetchall()
        audit(
            cur, principal.user_id, "ADMIN_HAZARD_EVIDENCE_VIEWED",
            "HAZARD_ZONE", normalized_zone_id,
        )
    return {
        "zone_id": normalized_zone_id,
        "total": len(rows),
        "items": [
            {
                "hazard_zone_evidence_id": row[0],
                "sensor_log_id": row[1],
                "event_uuid": row[2],
                "source_model": row[3],
                "model_version": row[4],
                "confidence": row[5],
                "distance_meters": row[6],
                "linked_at": row[7].isoformat(),
                "details": row[8],
                "ai_detection_id": row[9],
                "ai_stage": row[10],
                "ai_class_name": row[11],
                "ai_bbox_xyxy": row[12],
                "ai_polygon": row[13],
                "ride_session_id": row[14],
                "captured_at": row[15],
                "has_image": row[16],
            }
            for row in rows
        ],
    }


@app.post("/admin/hazard-zones/{zone_id}/evidence")
def admin_link_hazard_evidence(
    request: Request,
    zone_id: str,
    body: HazardEvidenceLinkRequest,
):
    principal = require_admin(request)
    try:
        normalized_zone_id = str(uuid.UUID(zone_id))
    except ValueError:
        raise HTTPException(status_code=422, detail="invalid zone_id")
    with db_transaction() as cur:
        cur.execute("SELECT 1 FROM hazard_zones WHERE zone_id=%s", (normalized_zone_id,))
        if cur.fetchone() is None:
            raise HTTPException(status_code=404, detail="hazard zone not found")
        ai_detection_id = body.ai_detection_id
        if ai_detection_id is not None:
            cur.execute(
                """
                SELECT j.sensor_log_id,j.event_uuid,d.stage,d.model_version,d.confidence
                FROM ai_detections d
                JOIN ai_analysis_jobs j
                  ON j.ai_analysis_job_id=d.ai_analysis_job_id
                WHERE d.ai_detection_id=%s
                """,
                (ai_detection_id,),
            )
            ai_detection = cur.fetchone()
            if ai_detection is None:
                raise HTTPException(status_code=404, detail="AI detection not found")
            sensor = (ai_detection[0], ai_detection[1])
            source_model = ai_detection[2]
            model_version = ai_detection[3]
            confidence = ai_detection[4]
        elif body.sensor_log_id is not None:
            cur.execute(
                "SELECT sensor_log_id,event_uuid FROM sensor_logs WHERE sensor_log_id=%s",
                (body.sensor_log_id,),
            )
            sensor = cur.fetchone()
            source_model = body.source_model
            model_version = body.model_version
            confidence = body.confidence
        else:
            cur.execute(
                "SELECT sensor_log_id,event_uuid FROM sensor_logs WHERE event_uuid=%s",
                (body.event_uuid,),
            )
            sensor = cur.fetchone()
            source_model = body.source_model
            model_version = body.model_version
            confidence = body.confidence
        if sensor is None:
            raise HTTPException(status_code=404, detail="sensor log not found")
        cur.execute(
            """
            INSERT INTO hazard_zone_evidence (
                zone_id, sensor_log_id, event_uuid, ai_detection_id,
                source_model, model_version,
                confidence, distance_meters, details
            ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)
            ON CONFLICT (zone_id, sensor_log_id)
                WHERE sensor_log_id IS NOT NULL
            DO UPDATE SET
                event_uuid=EXCLUDED.event_uuid,
                ai_detection_id=EXCLUDED.ai_detection_id,
                source_model=EXCLUDED.source_model,
                model_version=EXCLUDED.model_version,
                confidence=EXCLUDED.confidence,
                distance_meters=EXCLUDED.distance_meters,
                details=EXCLUDED.details,
                linked_at=CURRENT_TIMESTAMP
            RETURNING hazard_zone_evidence_id
            """,
            (
                normalized_zone_id, sensor[0], sensor[1] or body.event_uuid,
                ai_detection_id, source_model, model_version, confidence,
                body.distance_meters, Json(body.details),
            ),
        )
        evidence_id = cur.fetchone()[0]
        audit(
            cur, principal.user_id, "ADMIN_HAZARD_EVIDENCE_LINKED",
            "HAZARD_ZONE", normalized_zone_id,
            {"evidence_id": evidence_id, "sensor_log_id": sensor[0]},
        )
    return {
        "message": "hazard evidence linked",
        "hazard_zone_evidence_id": evidence_id,
        "zone_id": normalized_zone_id,
        "sensor_log_id": sensor[0],
        "event_uuid": sensor[1] or body.event_uuid,
        "ai_detection_id": ai_detection_id,
    }


@app.post("/admin/hazard-zones/{zone_id}/evidence/auto-link")
def admin_auto_link_hazard_evidence(
    request: Request,
    zone_id: str,
    body: HazardEvidenceAutoLinkRequest,
):
    principal = require_admin(request)
    try:
        normalized_zone_id = str(uuid.UUID(zone_id))
    except ValueError:
        raise HTTPException(status_code=422, detail="invalid zone_id")
    with db_transaction() as cur:
        cur.execute(
            "SELECT latitude,longitude,radius_meters FROM hazard_zones WHERE zone_id=%s",
            (normalized_zone_id,),
        )
        zone = cur.fetchone()
        if zone is None:
            raise HTTPException(status_code=404, detail="hazard zone not found")
        radius = body.radius_meters or max(float(zone[2]), 30.0)
        cur.execute(
            """
            WITH candidates AS (
                SELECT sensor_log_id, event_uuid,
                       SQRT(
                           POWER((latitude-%s) * 111000.0, 2) +
                           POWER((longitude-%s) * 91000.0, 2)
                       ) AS distance_meters
                FROM sensor_logs
                WHERE latitude IS NOT NULL AND longitude IS NOT NULL
                  AND (%s OR is_test_data=FALSE)
                  AND ABS(latitude-%s) <= (%s / 111000.0)
                  AND ABS(longitude-%s) <= (%s / 91000.0)
                ORDER BY captured_at DESC
                LIMIT %s
            )
            INSERT INTO hazard_zone_evidence (
                zone_id, sensor_log_id, event_uuid, source_model,
                model_version, distance_meters, details
            )
            SELECT %s, sensor_log_id, event_uuid, 'RULE_DISTANCE',
                   'pre-ai-v1', distance_meters,
                   jsonb_build_object('radius_meters', %s)
            FROM candidates WHERE distance_meters <= %s
            ON CONFLICT (zone_id, sensor_log_id)
                WHERE sensor_log_id IS NOT NULL
            DO NOTHING
            RETURNING hazard_zone_evidence_id
            """,
            (
                zone[0], zone[1], body.include_test_data,
                zone[0], radius, zone[1], radius, body.max_items,
                normalized_zone_id, radius, radius,
            ),
        )
        linked_ids = [row[0] for row in cur.fetchall()]
        audit(
            cur, principal.user_id, "ADMIN_HAZARD_EVIDENCE_AUTO_LINKED",
            "HAZARD_ZONE", normalized_zone_id,
            {
                "linked_count": len(linked_ids),
                "radius_meters": radius,
                "include_test_data": body.include_test_data,
            },
        )
    return {
        "message": "hazard evidence auto-link completed",
        "zone_id": normalized_zone_id,
        "linked_count": len(linked_ids),
        "radius_meters": radius,
    }


@app.delete("/admin/hazard-zones/{zone_id}/evidence/{evidence_id}")
def admin_unlink_hazard_evidence(
    request: Request,
    zone_id: str,
    evidence_id: int,
):
    principal = require_admin(request)
    try:
        normalized_zone_id = str(uuid.UUID(zone_id))
    except ValueError:
        raise HTTPException(status_code=422, detail="invalid zone_id")
    with db_transaction() as cur:
        cur.execute(
            """
            DELETE FROM hazard_zone_evidence
            WHERE hazard_zone_evidence_id=%s AND zone_id=%s
            RETURNING sensor_log_id
            """,
            (evidence_id, normalized_zone_id),
        )
        row = cur.fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="hazard evidence not found")
        audit(
            cur, principal.user_id, "ADMIN_HAZARD_EVIDENCE_UNLINKED",
            "HAZARD_ZONE", normalized_zone_id,
            {"evidence_id": evidence_id, "sensor_log_id": row[0]},
        )
    return {"message": "hazard evidence unlinked"}


@app.patch("/admin/hazard-zones/{zone_id}/status")
def admin_update_hazard_zone_status(
    request: Request,
    zone_id: str,
    body: HazardZoneStatusUpdate,
):
    principal = require_admin(request)
    try:
        normalized_zone_id = str(uuid.UUID(zone_id))
    except ValueError:
        raise HTTPException(status_code=422, detail="invalid zone_id")
    normalized_status = body.status.upper()
    if normalized_status not in {"CANDIDATE", "CONFIRMED", "REJECTED"}:
        raise HTTPException(status_code=422, detail="unsupported hazard zone status")
    with db_transaction() as cur:
        cur.execute(
            """
            UPDATE hazard_zones
            SET status=%s,
                is_user_visible=CASE
                    WHEN %s='CONFIRMED'
                         AND zone_category='USER_HAZARD'
                         AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)
                    THEN TRUE ELSE FALSE END,
                confirmation_source=CASE
                    WHEN %s='CONFIRMED' THEN 'MANUAL'
                    ELSE confirmation_source END,
                confirmation_cancelled_at=CASE
                    WHEN %s='REJECTED' THEN CURRENT_TIMESTAMP
                    WHEN %s='CONFIRMED' THEN NULL
                    ELSE confirmation_cancelled_at END,
                confirmed_at=CASE WHEN %s='CONFIRMED'
                    THEN COALESCE(confirmed_at,CURRENT_TIMESTAMP)
                    ELSE confirmed_at END,
                stale_at=CASE WHEN %s='CONFIRMED' THEN NULL ELSE stale_at END,
                archived_at=CASE WHEN %s='CONFIRMED' THEN NULL ELSE archived_at END,
                updated_at=CURRENT_TIMESTAMP
            WHERE zone_id=%s
            RETURNING status, is_test_data
            """,
            (
                normalized_status, normalized_status, normalized_status,
                normalized_status, normalized_status, normalized_status,
                normalized_status, normalized_status,
                normalized_zone_id,
            ),
        )
        row = cur.fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail="hazard zone not found")
        audit(
            cur,
            principal.user_id,
            "ADMIN_HAZARD_ZONE_STATUS_CHANGED",
            "HAZARD_ZONE",
            normalized_zone_id,
            {"status": normalized_status, "is_test_data": row[1]},
        )
    return {
        "message": "hazard zone status updated",
        "zone_id": normalized_zone_id,
        "status": row[0],
        "is_test_data": row[1],
    }


@app.get("/admin/operations")
def admin_operations(request: Request):
    principal = require_admin(request)
    health = operational_health()
    with db_transaction() as cur:
        cur.execute(
            """
            SELECT
                COUNT(*) FILTER (WHERE status_code >= 500),
                COUNT(*) FILTER (WHERE status_code >= 400),
                COUNT(*)
            FROM system_logs
            WHERE created_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'
              AND log_type='HTTP_REQUEST'
            """
        )
        server_errors, request_errors, logged_failures = cur.fetchone()
        cur.execute(
            """
            SELECT COUNT(*) FROM rides r
            WHERE r.deleted_at IS NULL AND (
                (SELECT COUNT(*) FROM sensor_logs s
                 WHERE s.ride_session_id=r.ride_session_id) < r.collected_frame_count
                OR
                (SELECT COUNT(*) FROM accident_events a
                 WHERE a.ride_session_id=r.ride_session_id) < r.accident_count
            )
            """
        )
        incomplete_rides = cur.fetchone()[0]
        audit(cur, principal.user_id, "ADMIN_OPERATIONS_VIEWED", "SYSTEM", None)
    return {
        "health": health,
        "last_24_hours": {
            "server_errors": server_errors,
            "request_errors": request_errors,
            "logged_failures": logged_failures,
            "incomplete_rides": incomplete_rides,
        },
        "alerts": [
            alert for alert, active in (
                ("DATABASE_DOWN", health["database"] != "UP"),
                ("STORAGE_LOW", health["storage"] != "UP"),
                ("BACKUP_FAILED", health["backup"].get("status") in ("FAILED", "STATUS_CORRUPT")),
                ("INCOMPLETE_RIDES", incomplete_rides > 0),
                ("SERVER_ERRORS", server_errors > 0),
            ) if active
        ],
    }


@app.get("/admin/backups/status")
def admin_backup_status(request: Request):
    require_admin(request)
    return backup_status()


@app.post("/admin/backups/run")
async def admin_run_backup(request: Request):
    principal = require_admin(request)
    result = await asyncio.to_thread(create_backup, "ADMIN")
    with db_transaction() as cur:
        audit(
            cur,
            principal.user_id,
            "ADMIN_BACKUP_RUN",
            "BACKUP",
            result.get("file_name"),
            {"status": result.get("status")},
        )
    if result["status"] == "FAILED":
        raise HTTPException(status_code=500, detail="backup failed; see backup status")
    return result


@app.get("/admin/sync/issues")
def admin_sync_issues(
    request: Request,
    limit: int = Query(100, ge=1, le=500),
):
    principal = require_admin(request)
    with db_transaction() as cur:
        cur.execute(
            """
            WITH received AS (
                SELECT r.ride_session_id,
                       r.collected_frame_count AS expected_sensor_logs,
                       r.accident_count AS expected_accidents,
                       COUNT(DISTINCT s.sensor_log_id) AS received_sensor_logs,
                       COUNT(DISTINCT a.accident_event_id) AS received_accidents,
                       MAX(s.created_at) AS last_sensor_received_at,
                       r.ended_at
                FROM rides r
                LEFT JOIN sensor_logs s ON s.ride_session_id=r.ride_session_id
                LEFT JOIN accident_events a ON a.ride_session_id=r.ride_session_id
                WHERE r.deleted_at IS NULL
                GROUP BY r.ride_session_id, r.collected_frame_count,
                         r.accident_count, r.ended_at
            )
            SELECT ride_session_id, expected_sensor_logs, received_sensor_logs,
                   GREATEST(expected_sensor_logs-received_sensor_logs, 0),
                   expected_accidents, received_accidents,
                   GREATEST(expected_accidents-received_accidents, 0),
                   last_sensor_received_at, ended_at
            FROM received
            WHERE received_sensor_logs < expected_sensor_logs
               OR received_accidents < expected_accidents
            ORDER BY ended_at DESC
            LIMIT %s
            """,
            (limit,)
        )
        rows = cur.fetchall()
        cur.execute(
            """
            SELECT COUNT(*) FROM rides r
            WHERE r.deleted_at IS NULL AND (
                (SELECT COUNT(*) FROM sensor_logs s
                 WHERE s.ride_session_id=r.ride_session_id) < r.collected_frame_count
                OR
                (SELECT COUNT(*) FROM accident_events a
                 WHERE a.ride_session_id=r.ride_session_id) < r.accident_count
            )
            """
        )
        total = cur.fetchone()[0]
        audit(cur, principal.user_id, "ADMIN_SYNC_ISSUES_VIEWED", "RIDE", None)
    return {
        "incomplete_ride_count": total,
        "items": [
            {
                "ride_session_id": row[0],
                "expected_sensor_logs": row[1],
                "received_sensor_logs": row[2],
                "missing_sensor_logs": row[3],
                "expected_accidents": row[4],
                "received_accidents": row[5],
                "missing_accidents": row[6],
                "last_sensor_received_at": row[7].isoformat() if row[7] else None,
                "ended_at": row[8],
            }
            for row in rows
        ],
    }


@app.get("/admin/system-errors")
def admin_system_errors(
    request: Request,
    limit: int = Query(100, ge=1, le=500),
):
    principal = require_admin(request)
    with db_transaction() as cur:
        cur.execute(
            """
            SELECT system_log_id, request_id, level, endpoint, status_code,
                   message, details, created_at
            FROM system_logs
            WHERE level IN ('WARN', 'ERROR')
            ORDER BY created_at DESC LIMIT %s
            """,
            (limit,)
        )
        rows = cur.fetchall()
        audit(cur, principal.user_id, "ADMIN_SYSTEM_ERRORS_VIEWED", "SYSTEM_LOG", None)
    return [
        {
            "system_log_id": row[0],
            "request_id": row[1],
            "level": row[2],
            "endpoint": row[3],
            "status_code": row[4],
            "message": row[5],
            "details": row[6],
            "created_at": row[7].isoformat(),
        }
        for row in rows
    ]


@app.get("/admin/audit-logs")
def admin_audit_logs(
    request: Request,
    limit: int = Query(100, ge=1, le=500),
    offset: int = Query(0, ge=0),
):
    principal = require_admin(request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT audit_log_id, actor_user_id, action, target_type,
                       target_id, details, created_at
                FROM audit_logs ORDER BY created_at DESC LIMIT %s OFFSET %s
                """,
                (limit, offset)
            )
            rows = cur.fetchall()
            audit(cur, principal.user_id, "ADMIN_AUDIT_LOGS_VIEWED", "AUDIT_LOG", None)
        conn.commit()
    finally:
        conn.close()
    return [
        {
            "audit_log_id": row[0], "actor_user_id": row[1], "action": row[2],
            "target_type": row[3], "target_id": row[4], "details": row[5],
            "created_at": row[6].isoformat()
        }
        for row in rows
    ]


@app.get("/admin/retention/status")
def admin_retention_status(request: Request):
    require_admin(request)
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT retention_run_id, trigger_type, status, started_at,
                       completed_at, result, error_message
                FROM retention_runs ORDER BY started_at DESC LIMIT 1
                """
            )
            latest = cur.fetchone()
            cur.execute("SELECT COUNT(*) FROM file_deletion_queue")
            queued_files = cur.fetchone()[0]
    finally:
        conn.close()
    latest_payload = None
    if latest:
        latest_payload = {
            "run_id": latest[0],
            "trigger_type": latest[1],
            "status": latest[2],
            "started_at": latest[3].isoformat(),
            "completed_at": latest[4].isoformat() if latest[4] else None,
            "result": latest[5] or {},
            "error_message": latest[6],
        }
    return {
        "latest": latest_payload,
        "queued_file_deletions": queued_files,
        "interval_seconds": RETENTION_INTERVAL_SECONDS,
        "raw_retention_days": RAW_RETENTION_DAYS,
        "system_log_retention_days": SYSTEM_LOG_RETENTION_DAYS,
        "ride_recovery_days": RIDE_RECOVERY_DAYS,
    }


@app.post("/admin/retention/run")
async def admin_run_retention(request: Request):
    principal = require_admin(request)
    result = await asyncio.to_thread(
        apply_retention_policy,
        None,
        "MANUAL",
        principal.user_id,
    )
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            audit(
                cur,
                principal.user_id,
                "RETENTION_RUN_REQUESTED",
                "SYSTEM",
                str(result.get("run_id")) if result.get("run_id") else None,
                {"status": result["status"]},
            )
        conn.commit()
    finally:
        conn.close()
    return result
