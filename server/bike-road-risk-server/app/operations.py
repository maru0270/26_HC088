import hashlib
import json
import logging
import os
import re
import shutil
import subprocess
import time
import threading
from logging.handlers import RotatingFileHandler
from pathlib import Path

from app.db import get_connection

PROJECT_DIR = Path(__file__).resolve().parent.parent
BACKUP_DIR = Path(os.getenv("BIKE_BACKUP_DIR", PROJECT_DIR / "backups")).resolve()
LOG_DIR = Path(os.getenv("BIKE_LOG_DIR", PROJECT_DIR / "logs")).resolve()
BACKUP_STATUS_FILE = BACKUP_DIR / "backup_status.json"
BACKUP_RETENTION_DAYS = int(os.getenv("BIKE_BACKUP_RETENTION_DAYS", "14"))
MIN_FREE_BYTES = int(os.getenv("BIKE_MIN_FREE_BYTES", str(2 * 1024**3)))
_backup_lock = threading.Lock()


class SensitiveDataFilter(logging.Filter):
    _patterns = (
        (re.compile(r"(?i)(authorization[\"'=:\s]+bearer\s+)[^\s,\"']+"), r"\1***"),
        (re.compile(r"(?i)(password|refresh_token|access_token)([\"'=:\s]+)[^\s,\"']+"), r"\1\2***"),
        (re.compile(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}"), "***@***"),
    )

    def filter(self, record: logging.LogRecord) -> bool:
        message = record.getMessage()
        for pattern, replacement in self._patterns:
            message = pattern.sub(replacement, message)
        record.msg = message
        record.args = ()
        return True


def configure_operational_logging():
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    logger = logging.getLogger("bikeeyes.server")
    if any(isinstance(handler, RotatingFileHandler) for handler in logger.handlers):
        return logger
    handler = RotatingFileHandler(
        LOG_DIR / "server.log",
        maxBytes=int(os.getenv("BIKE_LOG_MAX_BYTES", str(10 * 1024**2))),
        backupCount=int(os.getenv("BIKE_LOG_BACKUP_COUNT", "10")),
        encoding="utf-8",
    )
    handler.addFilter(SensitiveDataFilter())
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    logger.addHandler(handler)
    logger.setLevel(getattr(logging, os.getenv("BIKE_LOG_LEVEL", "INFO").upper(), logging.INFO))
    return logger


def _postgres_env() -> dict:
    env = os.environ.copy()
    env["PGPASSWORD"] = os.getenv("BIKE_DB_PASSWORD", "aibike0000")
    return env


def _postgres_tool(name: str) -> str:
    bin_dir = os.getenv("BIKE_PG_BIN_DIR")
    candidate = Path(bin_dir) / f"{name}.exe" if bin_dir else None
    if candidate and candidate.is_file():
        return str(candidate)
    resolved = shutil.which(name) or shutil.which(f"{name}.exe")
    if not resolved and os.name == "nt":
        program_files = Path(os.getenv("ProgramFiles", r"C:\Program Files"))
        postgres_root = program_files / "PostgreSQL"
        if postgres_root.is_dir():
            installations = sorted(
                (path for path in postgres_root.iterdir() if path.is_dir()),
                key=lambda path: path.name,
                reverse=True,
            )
            for installation in installations:
                discovered = installation / "bin" / f"{name}.exe"
                if discovered.is_file():
                    resolved = str(discovered)
                    break
    if not resolved:
        raise RuntimeError(f"{name} not found; configure BIKE_PG_BIN_DIR")
    return resolved


def _db_args(database: str | None = None) -> list[str]:
    return [
        "--host", os.getenv("BIKE_DB_HOST", "localhost"),
        "--port", os.getenv("BIKE_DB_PORT", "5432"),
        "--username", os.getenv("BIKE_DB_USER", "postgres"),
        "--dbname", database or os.getenv("BIKE_DB_NAME", "bike_db"),
    ]


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_backup(path: Path) -> dict:
    if not path.is_file() or path.stat().st_size == 0:
        raise RuntimeError("backup file is missing or empty")
    result = subprocess.run(
        [_postgres_tool("pg_restore"), "--list", str(path)],
        env=_postgres_env(),
        capture_output=True,
        text=True,
        timeout=300,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"pg_restore validation failed: {result.stderr[-1000:]}")
    return {
        "size_bytes": path.stat().st_size,
        "sha256": sha256_file(path),
        "toc_entries": sum(1 for line in result.stdout.splitlines() if line and not line.startswith(";")),
    }


def create_backup(trigger: str = "MANUAL") -> dict:
    if not _backup_lock.acquire(blocking=False):
        return {"status": "ALREADY_RUNNING", "trigger": trigger}
    try:
        return _create_backup_locked(trigger)
    finally:
        _backup_lock.release()


def _create_backup_locked(trigger: str) -> dict:
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    started_at = int(time.time() * 1000)
    database = os.getenv("BIKE_DB_NAME", "bike_db")
    output = BACKUP_DIR / f"{database}_{time.strftime('%Y%m%d_%H%M%S')}.dump"
    status = {"status": "RUNNING", "trigger": trigger, "started_at": started_at}
    BACKUP_STATUS_FILE.write_text(json.dumps(status, ensure_ascii=False), encoding="utf-8")
    try:
        result = subprocess.run(
            [_postgres_tool("pg_dump"), *_db_args(), "--format=custom", "--file", str(output)],
            env=_postgres_env(),
            capture_output=True,
            text=True,
            timeout=int(os.getenv("BIKE_BACKUP_TIMEOUT_SECONDS", "1800")),
            check=False,
        )
        if result.returncode != 0:
            raise RuntimeError(f"pg_dump failed: {result.stderr[-1000:]}")
        validation = validate_backup(output)
        status.update({
            "status": "SUCCESS",
            "completed_at": int(time.time() * 1000),
            "file_name": output.name,
            **validation,
        })
        prune_old_backups()
    except Exception as exception:
        output.unlink(missing_ok=True)
        status.update({
            "status": "FAILED",
            "completed_at": int(time.time() * 1000),
            "error": str(exception)[:2000],
        })
    BACKUP_STATUS_FILE.write_text(json.dumps(status, ensure_ascii=False, indent=2), encoding="utf-8")
    return status


def prune_old_backups():
    cutoff = time.time() - BACKUP_RETENTION_DAYS * 86400
    for candidate in BACKUP_DIR.glob("*.dump"):
        if candidate.stat().st_mtime < cutoff:
            candidate.unlink()


def backup_status() -> dict:
    if not BACKUP_STATUS_FILE.is_file():
        return {"status": "NEVER_RUN", "retention_days": BACKUP_RETENTION_DAYS}
    try:
        status = json.loads(BACKUP_STATUS_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {"status": "STATUS_CORRUPT", "retention_days": BACKUP_RETENTION_DAYS}
    status["retention_days"] = BACKUP_RETENTION_DAYS
    return status


def operational_health() -> dict:
    started = time.monotonic()
    db_error = None
    connection = None
    try:
        connection = get_connection()
        with connection.cursor() as cursor:
            cursor.execute("SELECT 1")
            cursor.fetchone()
    except Exception as exception:
        db_error = exception.__class__.__name__
    finally:
        if connection is not None:
            connection.close()
    storage = shutil.disk_usage(BACKUP_DIR.parent)
    return {
        "database": "UP" if db_error is None else "DOWN",
        "database_error": db_error,
        "database_latency_ms": round((time.monotonic() - started) * 1000, 2),
        "storage": "UP" if storage.free >= MIN_FREE_BYTES else "LOW",
        "storage_free_bytes": storage.free,
        "storage_total_bytes": storage.total,
        "minimum_free_bytes": MIN_FREE_BYTES,
        "backup": backup_status(),
    }
