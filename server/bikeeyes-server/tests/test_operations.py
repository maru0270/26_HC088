import json
import logging
from pathlib import Path

from app import operations


def test_sensitive_log_filter_masks_credentials_and_email():
    record = logging.LogRecord(
        "test", logging.INFO, __file__, 1,
        "Authorization: Bearer secret-token password=secret user@example.com",
        (), None,
    )
    assert operations.SensitiveDataFilter().filter(record) is True
    assert "secret-token" not in record.msg
    assert "password=secret" not in record.msg
    assert "user@example.com" not in record.msg


def test_validate_backup_hashes_nonempty_dump(monkeypatch, tmp_path):
    backup = tmp_path / "test.dump"
    backup.write_bytes(b"backup")

    class Result:
        returncode = 0
        stdout = "; header\n1; table\n2; data\n"
        stderr = ""

    monkeypatch.setattr(operations, "_postgres_tool", lambda _name: "pg_restore")
    monkeypatch.setattr(operations.subprocess, "run", lambda *_args, **_kwargs: Result())
    result = operations.validate_backup(backup)

    assert result["size_bytes"] == 6
    assert result["toc_entries"] == 2
    assert len(result["sha256"]) == 64


def test_backup_status_handles_corrupt_status(monkeypatch, tmp_path):
    status_file = tmp_path / "backup_status.json"
    status_file.write_text("{broken", encoding="utf-8")
    monkeypatch.setattr(operations, "BACKUP_STATUS_FILE", status_file)

    assert operations.backup_status()["status"] == "STATUS_CORRUPT"
