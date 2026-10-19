from __future__ import annotations

import argparse

from app.ai_service import store_classified_image_copies
from app.db import ensure_schema, get_connection


def backfill(*, dry_run: bool = False, limit: int | None = None) -> dict:
    ensure_schema()
    conn = get_connection()
    result = {"jobs": 0, "copies": 0, "missing": 0, "errors": []}
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT j.ai_analysis_job_id,j.sensor_log_id,j.event_uuid,
                       s.captured_at,j.image_path,
                       COALESCE(array_agg(DISTINCT d.class_name)
                           FILTER (WHERE d.class_name IS NOT NULL), ARRAY[]::varchar[])
                FROM ai_analysis_jobs j
                JOIN sensor_logs s ON s.sensor_log_id=j.sensor_log_id
                LEFT JOIN ai_detections d
                  ON d.ai_analysis_job_id=j.ai_analysis_job_id
                WHERE j.status IN ('COMPLETED','PARTIAL')
                  AND j.image_path IS NOT NULL
                GROUP BY j.ai_analysis_job_id,s.captured_at
                ORDER BY j.ai_analysis_job_id
                LIMIT %s
                """,
                (limit,),
            )
            rows = cur.fetchall()
        for job_id, sensor_log_id, event_uuid, captured_at, image_path, classes in rows:
            result["jobs"] += 1
            try:
                if dry_run:
                    result["copies"] += max(len(classes), 1)
                    continue
                with conn.cursor() as cur:
                    paths = store_classified_image_copies(
                        cur,
                        job_id=job_id,
                        sensor_log_id=sensor_log_id,
                        event_uuid=event_uuid,
                        captured_at=captured_at,
                        source_image_path=image_path,
                        class_names=set(classes),
                    )
                conn.commit()
                result["copies"] += len(paths)
            except FileNotFoundError as exc:
                conn.rollback()
                result["missing"] += 1
                result["errors"].append(f"job {job_id}: {exc}")
            except Exception as exc:
                conn.rollback()
                result["errors"].append(f"job {job_id}: {type(exc).__name__}: {exc}")
        return result
    finally:
        conn.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Backfill AI class image copies")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--limit", type=int)
    args = parser.parse_args()
    print(backfill(dry_run=args.dry_run, limit=args.limit))


if __name__ == "__main__":
    main()

