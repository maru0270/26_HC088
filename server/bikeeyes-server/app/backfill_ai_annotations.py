from __future__ import annotations

import argparse
from datetime import datetime
from pathlib import Path

from app.ai_annotation import save_shape_annotation
from app.ai_service import EXPECTED_CLASSES, IMAGE_UPLOAD_DIR
from app.db import get_connection


def stored_path(value: str) -> Path:
    candidate = Path(value)
    if not candidate.is_absolute():
        candidate = IMAGE_UPLOAD_DIR / candidate
    resolved = candidate.resolve()
    if resolved != IMAGE_UPLOAD_DIR and IMAGE_UPLOAD_DIR not in resolved.parents:
        raise RuntimeError("image path escapes configured storage")
    return resolved


def annotated_relative_path(captured_at: int, event_uuid: str) -> str:
    captured = datetime.fromtimestamp(captured_at / 1000)
    return (
        Path("annotated") / captured.strftime("%Y") / captured.strftime("%m")
        / captured.strftime("%d")
        / f"bike_{captured:%Y%m%d_%H%M%S}_{event_uuid.replace('-', '')[:8]}.jpg"
    ).as_posix()


def main(apply: bool, all_jobs: bool = False) -> None:
    conn = get_connection()
    aligned = regenerated = relinked = source_missing = failed = 0
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT j.ai_analysis_job_id,j.event_uuid,j.image_path,
                       j.annotated_image_path,s.captured_at
                FROM ai_analysis_jobs j
                JOIN sensor_logs s ON s.sensor_log_id=j.sensor_log_id
                WHERE j.status IN ('COMPLETED','PARTIAL') AND j.image_path IS NOT NULL
                ORDER BY j.ai_analysis_job_id
                """
            )
            jobs = cur.fetchall()
        for job_id, event_uuid, image_path, current_annotated_path, captured_at in jobs:
            try:
                source = stored_path(image_path)
                if not source.is_file():
                    source_missing += 1
                    continue
                relative = annotated_relative_path(captured_at, event_uuid)
                destination = stored_path(relative)
                current = (
                    stored_path(current_annotated_path)
                    if current_annotated_path else None
                )
                is_aligned = (
                    current_annotated_path == relative
                    and current is not None
                    and current.is_file()
                )
                if is_aligned and not all_jobs:
                    aligned += 1
                    continue

                if not apply:
                    continue

                with conn.cursor() as cur:
                    cur.execute(
                        """
                        SELECT stage,class_name,confidence,bbox_xyxy,polygon
                        FROM ai_detections WHERE ai_analysis_job_id=%s
                        ORDER BY stage,confidence DESC,ai_detection_id
                        """,
                        (job_id,),
                    )
                    rows = cur.fetchall()
                    detections = [
                        {"stage": row[0], "class_name": row[1], "confidence": row[2],
                         "bbox_xyxy": row[3], "polygon": row[4]}
                        for row in rows
                    ]
                    if all_jobs or not destination.is_file():
                        save_shape_annotation(source, destination, detections)
                        regenerated += 1
                    cur.execute(
                        "UPDATE ai_analysis_jobs SET annotated_image_path=%s WHERE ai_analysis_job_id=%s",
                        (relative, job_id),
                    )
                    for stage, classes in EXPECTED_CLASSES.items():
                        for class_name in classes:
                            matches = [
                                item for item in detections
                                if item["stage"] == stage and item["class_name"].lower() == class_name
                            ]
                            cur.execute(
                                """
                                INSERT INTO ai_detection_class_results (
                                    ai_analysis_job_id,stage,class_name,detected,
                                    detection_count,max_confidence
                                ) VALUES (%s,%s,%s,%s,%s,%s)
                                ON CONFLICT (ai_analysis_job_id,stage,class_name) DO UPDATE SET
                                    detected=EXCLUDED.detected,
                                    detection_count=EXCLUDED.detection_count,
                                    max_confidence=EXCLUDED.max_confidence,
                                    updated_at=CURRENT_TIMESTAMP
                                """,
                                (
                                    job_id, stage, class_name, bool(matches), len(matches),
                                    max((item["confidence"] for item in matches), default=None),
                                ),
                            )
                    relinked += 1
                conn.commit()
            except Exception as exc:
                conn.rollback()
                failed += 1
                print(f"FAILED job={job_id} event={event_uuid}: {type(exc).__name__}: {exc}")
    finally:
        conn.close()
    mode = "APPLY" if apply else "DRY-RUN"
    pending = len(jobs) - aligned - source_missing - failed
    print(
        f"{mode} eligible={len(jobs)} aligned={aligned} pending={pending} "
        f"regenerated={regenerated} relinked={relinked} "
        f"source_missing={source_missing} failed={failed}"
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    parser.add_argument(
        "--all",
        action="store_true",
        help="regenerate every eligible annotation, including already aligned files",
    )
    args = parser.parse_args()
    main(args.apply, args.all)
