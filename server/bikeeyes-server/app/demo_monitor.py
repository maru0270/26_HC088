from __future__ import annotations

import logging
import os
from ipaddress import ip_address
from pathlib import Path
from typing import Any

from fastapi import HTTPException, Request
from psycopg2.extras import Json

from app.db import get_connection
from app.operations import operational_health


logger = logging.getLogger("bikeeyes.demo_monitor")
STATIC_DIR = Path(__file__).resolve().parent / "static" / "demo-monitor"
DEMO_MONITOR_ENABLED = os.getenv("BIKE_DEMO_MONITOR_ENABLED", "true").lower() == "true"
DEMO_MONITOR_ALLOW_REMOTE = os.getenv("BIKE_DEMO_MONITOR_ALLOW_REMOTE", "false").lower() == "true"
CATEGORY_LABELS = {
    "AUTH": "계정",
    "RIDE": "주행",
    "GPS": "GPS",
    "SENSOR": "센서",
    "SENSOR_PEAK": "센서-PEAK",
    "IMAGE": "이미지",
    "AI_BBOX": "AI-BBOX",
    "AI_SEG": "AI-SEG",
    "ROAD_ANOMALY": "노면이상",
    "HAZARD": "위험요소",
    "ACCIDENT": "사고감지",
    "SYNC": "동기화",
    "SYSTEM": "시스템",
}


def _masked_email(value: str | None) -> str | None:
    if not value or "@" not in value:
        return None
    local, domain = value.split("@", 1)
    visible = local[:2]
    return f"{visible}{'*' * max(2, len(local) - len(visible))}@{domain}"


def require_demo_access(request: Request) -> None:
    if not DEMO_MONITOR_ENABLED:
        raise HTTPException(status_code=404, detail="demo monitor disabled")
    if DEMO_MONITOR_ALLOW_REMOTE:
        return
    host = request.client.host if request.client else ""
    try:
        address = ip_address(host)
    except ValueError:
        raise HTTPException(status_code=403, detail="local network access only")
    if not (address.is_loopback or address.is_private):
        raise HTTPException(status_code=403, detail="local network access only")


def record_demo_event(
    cur,
    *,
    category: str,
    event_type: str,
    message: str,
    status: str,
    ride_session_id: str | None = None,
    event_uuid: str | None = None,
    user_id: int | None = None,
    device_id: str | None = None,
    details: dict[str, Any] | None = None,
    visible: bool = True,
) -> None:
    """Record a presentation event in the caller's transaction.

    The event is deliberately separate from operational/error logs. Callers
    should only use stable Korean summaries and never secrets or raw payloads.
    """
    cur.execute(
        """
        INSERT INTO demo_monitor_events (
            ride_session_id,event_uuid,user_id,device_id,
            category,event_type,message,status,details,is_visible
        ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
        """,
        (
            ride_session_id,
            event_uuid,
            user_id,
            device_id,
            category,
            event_type,
            message[:500],
            status[:30],
            Json(details or {}),
            visible,
        ),
    )


def record_demo_event_safely(**kwargs) -> None:
    """Best-effort helper for work outside an existing DB transaction."""
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            record_demo_event(cur, **kwargs)
        conn.commit()
    except Exception:
        conn.rollback()
        logger.exception("failed to store demo monitor event")
    finally:
        conn.close()


def _current_ride(cur) -> tuple | None:
    cur.execute(
        """
        SELECT ride_session_id,started_at,ended_at,collected_frame_count,
               accident_count,updated_at
        FROM rides
        WHERE deleted_at IS NULL
        ORDER BY updated_at DESC,created_at DESC
        LIMIT 1
        """
    )
    return cur.fetchone()


def monitor_status() -> dict:
    # Local import avoids a cycle because the AI worker also records demo events.
    from app.ai_service import ai_status

    health = operational_health()
    model = ai_status()
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            ride = _current_ride(cur)
            current = None
            if ride:
                ride_id = ride[0]
                cur.execute(
                    """
                    SELECT
                        COUNT(*),
                        COUNT(*) FILTER (WHERE latitude IS NOT NULL AND longitude IS NOT NULL),
                        COUNT(*) FILTER (WHERE image_path IS NOT NULL)
                    FROM sensor_logs WHERE ride_session_id=%s
                    """,
                    (ride_id,),
                )
                sensor_count, gps_count, image_count = cur.fetchone()
                cur.execute(
                    """
                    SELECT
                        COUNT(d.ai_detection_id) FILTER (WHERE d.stage='BBOX'),
                        COUNT(d.ai_detection_id) FILTER (WHERE d.stage='SEGMENTATION')
                    FROM ai_analysis_jobs j
                    LEFT JOIN ai_detections d ON d.ai_analysis_job_id=j.ai_analysis_job_id
                    JOIN sensor_logs s ON s.sensor_log_id=j.sensor_log_id
                    WHERE s.ride_session_id=%s
                    """,
                    (ride_id,),
                )
                bbox_count, seg_count = cur.fetchone()
                cur.execute(
                    "SELECT COUNT(*) FROM accident_events WHERE ride_session_id=%s",
                    (ride_id,),
                )
                accident_count = cur.fetchone()[0]
                cur.execute(
                    """
                    SELECT COUNT(*) FROM hazard_zone_evidence e
                    JOIN sensor_logs s ON s.sensor_log_id=e.sensor_log_id
                    WHERE s.ride_session_id=%s
                    """,
                    (ride_id,),
                )
                evidence_count = cur.fetchone()[0]
                cur.execute(
                    """
                    SELECT status FROM demo_monitor_events
                    WHERE ride_session_id=%s AND category='SYNC'
                    ORDER BY created_at DESC,demo_event_id DESC LIMIT 1
                    """,
                    (ride_id,),
                )
                sync_row = cur.fetchone()
                cur.execute(
                    """
                    SELECT category FROM demo_monitor_events
                    WHERE ride_session_id=%s AND is_visible=TRUE
                    ORDER BY demo_event_id
                    """,
                    (ride_id,),
                )
                completed_categories = {row[0] for row in cur.fetchall()}
                collected = sensor_count > 0
                has_image = image_count > 0
                bbox_done = "AI_BBOX" in completed_categories
                seg_done = "AI_SEG" in completed_categories
                pipeline = [
                    {"label": "앱 수집", "state": "done" if collected else "active"},
                    {"label": "서버 전송", "state": "done" if collected else "pending"},
                    {
                        "label": "BBOX 추론",
                        "state": "done" if bbox_done else (
                            "active" if has_image else ("skipped" if collected else "pending")
                        ),
                    },
                    {
                        "label": "동일 원본 SEG 추론",
                        "state": "done" if seg_done else (
                            "active" if bbox_done else ("skipped" if collected and not has_image else "pending")
                        ),
                    },
                    {
                        "label": "DB 저장",
                        "state": "done" if collected and (not has_image or seg_done) else "pending",
                    },
                    {
                        "label": "후보 분류",
                        "state": "done" if seg_done else (
                            "skipped" if collected and not has_image else "pending"
                        ),
                    },
                ]
                current = {
                    "ride_session_id": ride_id,
                    "started_at": ride[1],
                    "ended_at": ride[2],
                    "sensor_count": sensor_count,
                    "gps_count": gps_count,
                    "image_count": image_count,
                    "bbox_count": bbox_count or 0,
                    "segmentation_count": seg_count or 0,
                    "hazard_evidence_count": evidence_count,
                    "accident_count": accident_count,
                    "sync_status": sync_row[0] if sync_row else "처리 중",
                    "pipeline": pipeline,
                }
            cur.execute(
                "SELECT created_at FROM demo_monitor_events ORDER BY created_at DESC LIMIT 1"
            )
            last_event = cur.fetchone()
    finally:
        conn.close()
    return {
        "server": "정상",
        "database": "정상" if health.get("database") == "UP" else "확인 필요",
        "storage": "정상" if health.get("storage") == "UP" else "확인 필요",
        "bbox_model": "준비 완료" if model.get("ready") else "확인 필요",
        "segmentation_model": "준비 완료" if model.get("ready") else "확인 필요",
        "last_event_at": last_event[0].isoformat() if last_event else None,
        "current_ride": current,
    }


def monitor_events(limit: int = 100, after_id: int = 0) -> list[dict]:
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT e.demo_event_id,e.category,e.event_type,e.message,e.status,
                       e.ride_session_id,e.details,e.created_at,e.user_id,
                       u.nickname,u.email,e.device_id
                FROM demo_monitor_events e
                LEFT JOIN users u ON u.user_id=e.user_id
                WHERE e.is_visible=TRUE AND e.demo_event_id>%s
                ORDER BY e.demo_event_id ASC
                LIMIT %s
                """,
                (after_id, limit),
            )
            rows = cur.fetchall()
    finally:
        conn.close()
    return [
        {
            "id": row[0],
            "category": CATEGORY_LABELS.get(row[1], row[1]),
            "category_code": row[1],
            "event_type": row[2],
            "message": row[3],
            "status": row[4],
            "ride_session_id": row[5],
            "details": row[6] or {},
            "created_at": row[7].isoformat(),
            "time": row[7].strftime("%H:%M:%S"),
            "account": (
                f"{row[9] or '사용자'} (ID {row[8]}, {_masked_email(row[10])})"
                if row[8] is not None
                else (f"비로그인 기기 ({row[11]})" if row[11] else "시스템")
            ),
        }
        for row in rows
    ]
