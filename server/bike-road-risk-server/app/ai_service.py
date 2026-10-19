from __future__ import annotations

from pathlib import Path
import logging
import os
import re
import shutil
from datetime import datetime

from psycopg2.extras import Json

from app.ai_pipeline import AiPipelineConfig, AiPipelineProvider, PipelineResult
from app.db import get_connection
from app.hazard_rules import (
    AI_SENSOR_SUPPORT_DELTA,
    CLASS_RULES,
    SENSOR_EVIDENCE_MIN_SPEED_MPS,
    apply_hazard_rules,
    image_matched_impact,
    merge_eligible_detections,
    median_speed_around_impact,
)
from app.demo_monitor import record_demo_event
from app.ai_annotation import save_shape_annotation


logger = logging.getLogger("bikeeyes.ai")
AI_CONFIG = AiPipelineConfig.from_env()
AI_PROVIDER = AiPipelineProvider(AI_CONFIG)
IMAGE_UPLOAD_DIR = Path(
    os.getenv("BIKE_IMAGE_UPLOAD_DIR", r"D:\Hanium\uploads\images")
).resolve()
AI_QUEUE_BATCH_SIZE = int(os.getenv("BIKE_AI_QUEUE_BATCH_SIZE", "20"))
NO_DETECTION_IMAGE_GRACE_MS = 2 * 60 * 1000
CLASSIFIED_ROOT_NAME = "classified"
ANNOTATED_ROOT_NAME = "annotated"
EXPECTED_CLASSES = {
    "BBOX": ("alligator", "bollard", "flood_area", "manhole", "pothole"),
    "SEGMENTATION": ("linear",),
}


def ai_status() -> dict:
    errors = AI_CONFIG.readiness_errors()
    return {
        "enabled": AI_CONFIG.enabled,
        "ready": AI_CONFIG.enabled and not errors,
        "execution_order": ["BBOX", "SEGMENTATION"],
        "same_original_image": True,
        "device": AI_CONFIG.device,
        "image_size": AI_CONFIG.image_size,
        "durable_queue": True,
        "hazard_candidate_thresholds": {
            class_name: rule[1] for class_name, rule in CLASS_RULES.items()
        },
        "bbox": {
            "model_path": str(AI_CONFIG.bbox.model_path),
            "model_version": AI_CONFIG.bbox.model_version,
            "confidence": AI_CONFIG.bbox.confidence,
            "iou": AI_CONFIG.bbox.iou,
        },
        "segmentation": {
            "model_path": str(AI_CONFIG.segmentation.model_path),
            "model_version": AI_CONFIG.segmentation.model_version,
            "confidence": AI_CONFIG.segmentation.confidence,
            "iou": AI_CONFIG.segmentation.iou,
        },
        "errors": errors,
    }


def enqueue_ai_analysis(
    cursor,
    sensor_log_id: int,
    event_uuid: str,
    image_path: str,
) -> int | None:
    """Persists WAITING in the same transaction as the sensor log."""
    if not AI_CONFIG.enabled:
        return None
    cursor.execute(
        """
        INSERT INTO ai_analysis_jobs (
            sensor_log_id, event_uuid, image_path, status, config_snapshot
        ) VALUES (%s, %s, %s, 'WAITING', %s)
        ON CONFLICT (event_uuid) DO NOTHING
        RETURNING ai_analysis_job_id
        """,
        (sensor_log_id, event_uuid, image_path, Json(AI_CONFIG.snapshot())),
    )
    row = cursor.fetchone()
    if row is not None:
        return row[0]
    cursor.execute(
        "SELECT ai_analysis_job_id FROM ai_analysis_jobs WHERE event_uuid=%s",
        (event_uuid,),
    )
    return cursor.fetchone()[0]


def process_ai_job(job_id: int) -> bool:
    """Atomically claims one WAITING job; safe against duplicate background workers."""
    claimed = _claim_job(job_id)
    if claimed is None:
        return False
    event_uuid, image_path = claimed
    try:
        result = AI_PROVIDER.analyze(_stored_image_path(image_path))
        _finish_job(job_id, result)
    except Exception as exception:
        logger.exception("AI analysis failed for event_uuid=%s", event_uuid)
        _fail_job(job_id, f"{type(exception).__name__}: {exception}")
    return True


def _claim_job(job_id: int) -> tuple[str, str] | None:
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE ai_analysis_jobs SET
                    status='RUNNING',
                    error_message=NULL,
                    started_at=CURRENT_TIMESTAMP,
                    completed_at=NULL,
                    attempt_count=attempt_count+1
                WHERE ai_analysis_job_id=%s AND status='WAITING'
                RETURNING event_uuid,image_path
                """,
                (job_id,),
            )
            row = cur.fetchone()
        conn.commit()
        return row
    finally:
        conn.close()


def _stored_image_path(image_path: str) -> Path:
    candidate = Path(image_path)
    if not candidate.is_absolute():
        candidate = IMAGE_UPLOAD_DIR / candidate
    resolved = candidate.resolve()
    if resolved != IMAGE_UPLOAD_DIR and IMAGE_UPLOAD_DIR not in resolved.parents:
        raise RuntimeError("AI image path escapes configured storage")
    if not resolved.is_file():
        raise FileNotFoundError(f"AI source image not found: {image_path}")
    return resolved


def _safe_class_name(class_name: str) -> str:
    normalized = re.sub(r"[^0-9A-Za-z가-힣_-]+", "_", class_name.strip().lower())
    return normalized.strip("_-") or "etc"


def _classified_relative_path(
    captured_at: int,
    event_uuid: str,
    class_name: str,
) -> str:
    safe_class = _safe_class_name(class_name)
    captured = datetime.fromtimestamp(captured_at / 1000)
    filename = (
        f"bike_{captured:%Y%m%d_%H%M%S}"
        f"({safe_class})_{event_uuid.replace('-', '')[:8]}.jpg"
    )
    return (
        Path(CLASSIFIED_ROOT_NAME)
        / safe_class
        / captured.strftime("%Y")
        / captured.strftime("%m")
        / captured.strftime("%d")
        / filename
    ).as_posix()


def store_classified_image_copies(
    cursor,
    *,
    job_id: int,
    sensor_log_id: int | None,
    event_uuid: str,
    captured_at: int,
    source_image_path: str,
    class_names: set[str],
) -> list[str]:
    """Copy one untouched source per detected class, or one etc copy when empty."""
    source_file = _stored_image_path(source_image_path)
    normalized_classes = {
        _safe_class_name(class_name) for class_name in class_names if class_name.strip()
    } or {"etc"}
    stored_paths: list[str] = []
    for class_name in sorted(normalized_classes):
        relative_path = _classified_relative_path(captured_at, event_uuid, class_name)
        destination = (IMAGE_UPLOAD_DIR / relative_path).resolve()
        if IMAGE_UPLOAD_DIR not in destination.parents:
            raise RuntimeError("classified image path escapes configured storage")
        destination.parent.mkdir(parents=True, exist_ok=True)
        if not destination.is_file():
            shutil.copy2(source_file, destination)
        cursor.execute(
            """
            INSERT INTO ai_classified_images (
                ai_analysis_job_id, sensor_log_id, event_uuid, class_name,
                source_image_path, classified_image_path
            ) VALUES (%s,%s,%s,%s,%s,%s)
            ON CONFLICT (ai_analysis_job_id, class_name) DO UPDATE SET
                sensor_log_id=EXCLUDED.sensor_log_id,
                event_uuid=EXCLUDED.event_uuid,
                source_image_path=EXCLUDED.source_image_path,
                classified_image_path=EXCLUDED.classified_image_path,
                updated_at=CURRENT_TIMESTAMP
            """,
            (
                job_id, sensor_log_id, event_uuid, class_name,
                source_image_path, relative_path,
            ),
        )
        stored_paths.append(relative_path)
    cursor.execute(
        """
        DELETE FROM ai_classified_images
        WHERE ai_analysis_job_id=%s AND NOT (class_name = ANY(%s))
        """,
        (job_id, list(normalized_classes)),
    )
    return stored_paths


def _annotated_relative_path(captured_at: int, event_uuid: str) -> str:
    captured = datetime.fromtimestamp(captured_at / 1000)
    filename = f"bike_{captured:%Y%m%d_%H%M%S}_{event_uuid.replace('-', '')[:8]}.jpg"
    return (
        Path(ANNOTATED_ROOT_NAME)
        / captured.strftime("%Y") / captured.strftime("%m") / captured.strftime("%d")
        / filename
    ).as_posix()


def store_detection_summaries(cursor, job_id: int, stages) -> None:
    by_stage = {str(stage.stage).upper(): stage.detections for stage in stages}
    for stage, expected_classes in EXPECTED_CLASSES.items():
        detections = by_stage.get(stage, [])
        for class_name in expected_classes:
            matches = [d for d in detections if d.class_name.lower() == class_name]
            cursor.execute(
                """
                INSERT INTO ai_detection_class_results (
                    ai_analysis_job_id,stage,class_name,detected,detection_count,max_confidence
                ) VALUES (%s,%s,%s,%s,%s,%s)
                ON CONFLICT (ai_analysis_job_id,stage,class_name) DO UPDATE SET
                    detected=EXCLUDED.detected,
                    detection_count=EXCLUDED.detection_count,
                    max_confidence=EXCLUDED.max_confidence,
                    updated_at=CURRENT_TIMESTAMP
                """,
                (
                    job_id, stage, class_name, bool(matches), len(matches),
                    max((d.confidence for d in matches), default=None),
                ),
            )


def store_annotated_image(
    cursor,
    *,
    job_id: int,
    captured_at: int,
    event_uuid: str,
    source_image_path: str,
    stages,
) -> str:
    relative_path = _annotated_relative_path(captured_at, event_uuid)
    destination = (IMAGE_UPLOAD_DIR / relative_path).resolve()
    if IMAGE_UPLOAD_DIR not in destination.parents:
        raise RuntimeError("annotated image path escapes configured storage")
    raw_detections = [
        (
            index,
            detection.stage,
            detection.model_version,
            detection.class_name,
            detection.confidence,
            detection.bbox_xyxy,
            detection.polygon,
        )
        for index, detection in enumerate(
            detection for stage in stages for detection in stage.detections
        )
    ]
    detections = [
        {
            "class_name": detection[3],
            "confidence": detection[4],
            "bbox_xyxy": detection[5],
            "polygon": detection[6],
        }
        for detection in merge_eligible_detections(raw_detections)
    ]
    save_shape_annotation(_stored_image_path(source_image_path), destination, detections)
    cursor.execute(
        "UPDATE ai_analysis_jobs SET annotated_image_path=%s WHERE ai_analysis_job_id=%s",
        (relative_path, job_id),
    )
    return relative_path


def recover_interrupted_ai_jobs() -> int:
    if not AI_CONFIG.enabled:
        return 0
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE ai_analysis_jobs SET
                    status='WAITING', error_message='Recovered after server restart',
                    started_at=NULL, completed_at=NULL
                WHERE status='RUNNING'
                """
            )
            recovered = cur.rowcount
        conn.commit()
        return recovered
    finally:
        conn.close()


def process_pending_ai_jobs(limit: int | None = None) -> int:
    if not AI_CONFIG.enabled:
        return 0
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT ai_analysis_job_id
                FROM ai_analysis_jobs
                WHERE status='WAITING'
                ORDER BY created_at,ai_analysis_job_id
                LIMIT %s
                """,
                (limit or AI_QUEUE_BATCH_SIZE,),
            )
            job_ids = [row[0] for row in cur.fetchall()]
    finally:
        conn.close()
    return sum(process_ai_job(job_id) for job_id in job_ids)


def _create_review_item_if_eligible(cur, source, result: PipelineResult, detection_count: int) -> bool:
    """Create an admin-only review item for a sensor-supported AI false negative."""
    if not source or result.status != "COMPLETED" or detection_count != 0:
        return False
    # source indexes: ride, event, user, device, sensor id, captured, image,
    # sensor_samples, peak_at, latitude, longitude, speed, accel x/y/z.
    samples = source[7] or []
    impact_delta, impact_at = image_matched_impact(
        samples, source[5], source[12] or 0.0, source[13] or 0.0, source[14] or 0.0,
        source[8],
    )
    speed_median = median_speed_around_impact(
        samples, impact_at, source[5], source[11],
    )
    if impact_delta < AI_SENSOR_SUPPORT_DELTA or speed_median < SENSOR_EVIDENCE_MIN_SPEED_MPS:
        return False
    cur.execute(
        """
        INSERT INTO ai_review_items(
            sensor_log_id,event_uuid,ride_session_id,image_path,captured_at,
            peak_sample_at,latitude,longitude,peak_impact_delta,speed_median,
            sensor_samples,status
        ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,'PENDING')
        ON CONFLICT (sensor_log_id) WHERE sensor_log_id IS NOT NULL
        DO UPDATE SET image_path=EXCLUDED.image_path,
                      peak_sample_at=EXCLUDED.peak_sample_at,
                      latitude=EXCLUDED.latitude,
                      longitude=EXCLUDED.longitude,
                      peak_impact_delta=EXCLUDED.peak_impact_delta,
                      speed_median=EXCLUDED.speed_median,
                      sensor_samples=EXCLUDED.sensor_samples,
                      updated_at=CURRENT_TIMESTAMP
        """,
        (
            source[4], source[1], source[0], source[6], source[5], impact_at,
            source[9], source[10], impact_delta, speed_median, Json(samples),
        ),
    )
    # A pending review item is protected from the two-minute AI-negative
    # cleanup. The normal 365-day raw retention still applies.
    cur.execute(
        """
        UPDATE sensor_logs SET image_delete_after=NULL,
            image_retention_reason=CASE
                WHEN image_retention_reason='ACCIDENT_BLACKBOX'
                THEN image_retention_reason ELSE 'STANDARD' END
        WHERE sensor_log_id=%s
        """,
        (source[4],),
    )
    return True


def _finish_job(job_id: int, result: PipelineResult) -> None:
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT s.ride_session_id,s.event_uuid,s.user_id,s.device_id,
                       s.sensor_log_id,s.captured_at,j.image_path,
                       s.sensor_samples,s.peak_sample_at,s.latitude,s.longitude,s.speed,
                       s.accel_x,s.accel_y,s.accel_z
                FROM ai_analysis_jobs j
                JOIN sensor_logs s ON s.sensor_log_id=j.sensor_log_id
                WHERE j.ai_analysis_job_id=%s
                """,
                (job_id,),
            )
            source = cur.fetchone()
            cur.execute("DELETE FROM ai_detections WHERE ai_analysis_job_id=%s", (job_id,))
            for stage in result.stages:
                for detection in stage.detections:
                    cur.execute(
                        """
                        INSERT INTO ai_detections (
                            ai_analysis_job_id, stage, model_version,
                            class_name, class_id, confidence,
                            bbox_xyxy, polygon, raw_result
                        ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)
                        """,
                        (
                            job_id, detection.stage, detection.model_version,
                            detection.class_name, detection.class_id, detection.confidence,
                            Json(detection.bbox_xyxy), Json(detection.polygon), Json(detection.raw),
                        ),
                    )
            stage_summary = [
                {
                    "stage": stage.stage,
                    "model_version": stage.model_version,
                    "duration_ms": stage.duration_ms,
                    "detection_count": len(stage.detections),
                    "error": stage.error,
                }
                for stage in result.stages
            ]
            detected_classes = {
                detection.class_name
                for stage in result.stages
                for detection in stage.detections
            }
            detection_count = sum(
                len(stage.detections) for stage in result.stages
            )
            # Raw rows are retained for audit, but retention/review decisions
            # use the same confidence and same-damage IoU filter as evidence
            # linking. A low-confidence-only result is therefore an AI miss.
            cur.execute(
                """
                SELECT ai_detection_id,stage,model_version,class_name,confidence,bbox_xyxy
                FROM ai_detections WHERE ai_analysis_job_id=%s
                ORDER BY ai_detection_id
                """,
                (job_id,),
            )
            eligible_detection_count = len(merge_eligible_detections(cur.fetchall()))
            if source:
                store_classified_image_copies(
                    cur,
                    job_id=job_id,
                    sensor_log_id=source[4],
                    event_uuid=source[1],
                    captured_at=source[5],
                    source_image_path=source[6],
                    class_names=detected_classes,
                )
                store_detection_summaries(cur, job_id, result.stages)
                store_annotated_image(
                    cur,
                    job_id=job_id,
                    captured_at=source[5],
                    event_uuid=source[1],
                    source_image_path=source[6],
                    stages=result.stages,
                )
            cur.execute(
                """
                UPDATE ai_analysis_jobs SET
                    status=%s,
                    image_sha256=%s,
                    source_width=%s,
                    source_height=%s,
                    config_snapshot=%s,
                    total_duration_ms=%s,
                    stage_summary=%s,
                    error_message=%s,
                    completed_at=CURRENT_TIMESTAMP
                WHERE ai_analysis_job_id=%s
                """,
                (
                    result.status,
                    result.image_sha256,
                    result.source_width,
                    result.source_height,
                    Json(result.config_snapshot),
                    result.total_duration_ms,
                    Json(stage_summary),
                    "; ".join(filter(None, (stage.error for stage in result.stages))) or None,
                    job_id,
                ),
            )
            if source:
                # A missing detection is only authoritative when both serial stages
                # completed.  A failed/partial inference must retain its source so it
                # can be inspected or re-analysed instead of being mistaken for a
                # genuinely empty road image.
                if result.status == "COMPLETED" and eligible_detection_count == 0:
                    cur.execute(
                        """
                        UPDATE sensor_logs s SET
                            image_delete_after=(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP) * 1000)::BIGINT + %s,
                            image_retention_reason='AI_NO_DETECTION'
                        WHERE s.sensor_log_id=%s
                          AND NOT EXISTS (
                              SELECT 1 FROM accident_image_evidence e
                              WHERE e.sensor_log_id=s.sensor_log_id
                          )
                        """,
                        (NO_DETECTION_IMAGE_GRACE_MS, source[4]),
                    )
                else:
                    cur.execute(
                        """
                        UPDATE sensor_logs SET
                            image_delete_after=NULL,
                            image_retention_reason=CASE
                                WHEN image_retention_reason='ACCIDENT_BLACKBOX'
                                THEN image_retention_reason ELSE 'STANDARD' END
                        WHERE sensor_log_id=%s
                        """,
                        (source[4],),
                    )
            # Review eligibility uses the same class confidence and IoU rules
            # as hazard evidence. Raw low-confidence/duplicate rows remain in
            # ai_detections for audit, but do not suppress an AI-miss review.
            _create_review_item_if_eligible(
                cur, source, result, eligible_detection_count,
            )
            linked_evidence = apply_hazard_rules(cur, job_id)
            if source:
                ride_id, event_uuid, user_id, device_id = source[:4]
                for stage in result.stages:
                    stage_code = str(stage.stage).upper()
                    category = "AI_BBOX" if stage_code == "BBOX" else "AI_SEG"
                    label = "객체" if category == "AI_BBOX" else "균열 영역"
                    message = (
                        f"{label} {len(stage.detections)}건을 탐지했습니다."
                        if category == "AI_BBOX"
                        else f"동일한 원본 이미지에서 {label} {len(stage.detections)}건을 탐지했습니다."
                    )
                    record_demo_event(
                        cur,
                        category=category,
                        event_type=f"{stage_code}_ANALYSIS_COMPLETED",
                        message=message,
                        status="분석 완료",
                        ride_session_id=ride_id,
                        event_uuid=event_uuid,
                        user_id=user_id,
                        device_id=device_id,
                        details={
                            "detection_count": len(stage.detections),
                            "duration_ms": stage.duration_ms,
                            "same_original_image": True,
                        },
                    )
                if linked_evidence:
                    record_demo_event(
                        cur,
                        category="HAZARD",
                        event_type="HAZARD_EVIDENCE_LINKED",
                        message=f"노면 분석 근거 {linked_evidence}건을 위험구간 후보에 연결했습니다.",
                        status="후보 등록",
                        ride_session_id=ride_id,
                        event_uuid=event_uuid,
                        user_id=user_id,
                        device_id=device_id,
                        details={"linked_evidence_count": linked_evidence},
                    )
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def _fail_job(job_id: int, error_message: str) -> None:
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE ai_analysis_jobs SET
                    status='FAILED', error_message=%s, completed_at=CURRENT_TIMESTAMP
                WHERE ai_analysis_job_id=%s
                """,
                (error_message[:2000], job_id),
            )
        conn.commit()
    finally:
        conn.close()


def list_analysis_jobs(limit: int = 100) -> list[dict]:
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT j.ai_analysis_job_id, j.event_uuid, j.sensor_log_id,
                       j.status, j.image_sha256, j.total_duration_ms,
                       j.stage_summary, j.error_message,
                       (EXTRACT(EPOCH FROM j.created_at) * 1000)::BIGINT,
                       COUNT(d.ai_detection_id), j.image_path,
                       j.source_width, j.source_height, j.config_snapshot,
                       j.attempt_count
                FROM ai_analysis_jobs j
                LEFT JOIN ai_detections d ON d.ai_analysis_job_id=j.ai_analysis_job_id
                GROUP BY j.ai_analysis_job_id
                ORDER BY j.created_at DESC
                LIMIT %s
                """,
                (limit,),
            )
            return [
                {
                    "job_id": row[0], "event_uuid": row[1], "sensor_log_id": row[2],
                    "status": row[3], "image_sha256": row[4], "total_duration_ms": row[5],
                    "stages": row[6] or [], "error": row[7], "created_at": row[8],
                    "detection_count": row[9], "image_path": row[10],
                    "source_width": row[11], "source_height": row[12],
                    "config_snapshot": row[13] or {}, "attempt_count": row[14],
                }
                for row in cur.fetchall()
            ]
    finally:
        conn.close()


def ai_queue_status() -> dict:
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT status,COUNT(*)
                FROM ai_analysis_jobs
                GROUP BY status
                """
            )
            counts = {row[0]: row[1] for row in cur.fetchall()}
            cur.execute(
                """
                SELECT (EXTRACT(EPOCH FROM MIN(created_at)) * 1000)::BIGINT
                FROM ai_analysis_jobs WHERE status='WAITING'
                """
            )
            oldest_waiting_at = cur.fetchone()[0]
        return {
            "counts": counts,
            "oldest_waiting_at": oldest_waiting_at,
            "batch_size": AI_QUEUE_BATCH_SIZE,
        }
    finally:
        conn.close()
