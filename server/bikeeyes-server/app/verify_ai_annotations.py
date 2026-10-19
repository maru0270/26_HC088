from pathlib import Path

from app.ai_service import IMAGE_UPLOAD_DIR
from app.db import get_connection


def main() -> None:
    conn = get_connection()
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT COUNT(*),
                       COUNT(*) FILTER (WHERE annotated_image_path IS NOT NULL)
                FROM ai_analysis_jobs
                WHERE status IN ('COMPLETED','PARTIAL') AND image_path IS NOT NULL
                """
            )
            eligible, linked = cur.fetchone()
            cur.execute("SELECT COUNT(*) FROM ai_detection_class_results")
            class_results = cur.fetchone()[0]
            cur.execute(
                """
                SELECT annotated_image_path FROM ai_analysis_jobs
                WHERE annotated_image_path IS NOT NULL
                """
            )
            paths = [row[0] for row in cur.fetchall()]
    finally:
        conn.close()
    missing = []
    for value in paths:
        candidate = Path(value)
        if not candidate.is_absolute():
            candidate = IMAGE_UPLOAD_DIR / candidate
        if not candidate.is_file():
            missing.append(value)
    print(
        f"eligible={eligible} linked={linked} files={len(paths) - len(missing)} "
        f"missing={len(missing)} class_results={class_results}"
    )
    if eligible != linked or missing:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
