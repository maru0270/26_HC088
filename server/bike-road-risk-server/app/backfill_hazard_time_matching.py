from __future__ import annotations

import argparse
import time

from app.db import ensure_schema, get_connection
from app.hazard_rules import apply_hazard_rules
from app.operations import create_backup


def _counts(cur) -> dict[str, int]:
    cur.execute(
        """
        SELECT
            COUNT(*),
            COUNT(*) FILTER (
                WHERE details->>'temporary_rule_version'='2026-06-27-v2-time-matched'
            ),
            COUNT(*) FILTER (WHERE z.zone_category='USER_HAZARD')
        FROM hazard_zone_evidence e
        JOIN hazard_zones z ON z.zone_id=e.zone_id
        """
    )
    total, time_matched, user_hazard = cur.fetchone()
    cur.execute(
        """
        SELECT COUNT(*) FROM hazard_zones
        WHERE zone_category='USER_HAZARD' AND status='ARCHIVED'
        """
    )
    return {
        "evidence_total": int(total),
        "time_matched_evidence": int(time_matched),
        "user_hazard_evidence": int(user_hazard),
        "archived_user_zones": int(cur.fetchone()[0]),
    }


def backfill(*, apply: bool = False, limit: int | None = None) -> dict:
    ensure_schema()
    backup = None
    if apply:
        backup = create_backup(trigger="HAZARD_TIME_MATCHING_BACKFILL")
        if backup.get("status") != "SUCCESS":
            raise RuntimeError(f"database backup failed: {backup}")

    conn = get_connection()
    started = time.monotonic()
    processed = linked = failed = 0
    try:
        with conn.cursor() as cur:
            before = _counts(cur)
            cur.execute(
                """
                SELECT j.ai_analysis_job_id
                FROM ai_analysis_jobs j
                WHERE j.status IN ('COMPLETED','PARTIAL')
                  AND (
                      EXISTS (
                          SELECT 1 FROM ai_detections d
                          WHERE d.ai_analysis_job_id=j.ai_analysis_job_id
                      )
                      OR EXISTS (
                          SELECT 1 FROM hazard_zone_evidence e
                          WHERE e.sensor_log_id=j.sensor_log_id
                            AND (
                                e.ai_detection_id IS NOT NULL
                                OR e.details ? 'temporary_rule_version'
                            )
                      )
                  )
                ORDER BY j.ai_analysis_job_id
                LIMIT %s
                """,
                (limit,),
            )
            job_ids = [row[0] for row in cur.fetchall()]
            for job_id in job_ids:
                try:
                    linked += apply_hazard_rules(cur, job_id)
                    processed += 1
                except Exception:
                    failed += 1
                    raise
            after = _counts(cur)
        if apply:
            conn.commit()
        else:
            conn.rollback()
        return {
            "mode": "APPLY" if apply else "DRY_RUN",
            "jobs_selected": len(job_ids),
            "jobs_processed": processed,
            "jobs_failed": failed,
            "new_evidence_linked": linked,
            "evidence_removed": max(
                0, before["evidence_total"] + linked - after["evidence_total"],
            ),
            "before": before,
            "after": after,
            "elapsed_seconds": round(time.monotonic() - started, 2),
            "backup": backup,
        }
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Recalculate historical AI hazard evidence with time-matched sensors",
    )
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--limit", type=int)
    args = parser.parse_args()
    print(backfill(apply=args.apply, limit=args.limit))


if __name__ == "__main__":
    main()
