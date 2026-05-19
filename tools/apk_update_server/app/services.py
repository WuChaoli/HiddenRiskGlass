from __future__ import annotations

import hashlib
import re
import shutil
import tempfile
import uuid
from pathlib import Path
from sqlite3 import Row
from typing import BinaryIO

from app.config import Settings
from app.db import db_session
from app.schemas import (
    RESULT_NO_RELEASE,
    RESULT_NO_UPDATE,
    RESULT_UPDATE,
    STATUS_ACTIVE,
    no_update_response,
)


DEFAULT_RELEASE_KEY = "default_release_id"
CHUNK_SIZE = 1024 * 1024


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(CHUNK_SIZE), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_version_dir(version_code: int, version_name: str) -> str:
    clean_name = re.sub(r"[^A-Za-z0-9._-]+", "-", version_name.strip()).strip(".-")
    if not clean_name:
        clean_name = "release"
    return f"{int(version_code)}-{clean_name}"


def unique_release_dir(settings: Settings, version_code: int, version_name: str) -> Path:
    prefix = safe_version_dir(version_code, version_name)
    for _ in range(10):
        release_dir = settings.releases_dir / f"{prefix}-{uuid.uuid4().hex[:12]}"
        try:
            release_dir.mkdir(parents=True, exist_ok=False)
            return release_dir
        except FileExistsError:
            continue
    raise RuntimeError("failed to allocate release directory")


def release_apk_url(row: Row, base_url: str | None = None) -> str:
    effective_base_url = base_url
    if effective_base_url is None:
        stored_url = str(row["apk_url"])
        prefix = "/releases/"
        marker = stored_url.find(prefix)
        if marker > 0:
            effective_base_url = stored_url[:marker]
        else:
            return stored_url
    return f"{effective_base_url.rstrip('/')}/releases/{int(row['id'])}/app.apk"


def manifest_from_release(row: Row, base_url: str | None = None) -> dict[str, object]:
    return {
        "versionCode": row["version_code"],
        "versionName": row["version_name"],
        "apkUrl": release_apk_url(row, base_url),
        "sha256": row["sha256"],
        "sizeBytes": row["size_bytes"],
        "releaseNotes": row["release_notes"],
        "mandatory": bool(row["mandatory"]),
    }


def publish_release(
    settings: Settings,
    filename: str,
    fileobj: BinaryIO,
    version_code: int,
    version_name: str,
    release_notes: str,
    mandatory: bool,
    base_url: str,
) -> dict[str, object]:
    if int(version_code) <= 0:
        raise ValueError("version_code must be positive")
    version_name = version_name.strip()
    if not version_name:
        raise ValueError("version_name is required")

    original_name = Path(filename).name
    if not original_name.lower().endswith(".apk"):
        raise ValueError("filename must end with .apk")

    release_dir = unique_release_dir(settings, version_code, version_name)
    apk_path = release_dir / "app.apk"
    temp_path: Path | None = None
    final_apk_written = False

    try:
        with tempfile.NamedTemporaryFile(delete=False, dir=release_dir, suffix=".upload") as temp_file:
            temp_path = Path(temp_file.name)
            shutil.copyfileobj(fileobj, temp_file, CHUNK_SIZE)

        size_bytes = temp_path.stat().st_size
        if size_bytes <= 0:
            raise ValueError("uploaded APK is empty")

        shutil.move(str(temp_path), apk_path)
        temp_path = None
        final_apk_written = True

        digest = sha256_file(apk_path)
        placeholder_apk_url = f"{base_url.rstrip('/')}/releases/pending/app.apk"

        with db_session(settings) as conn:
            cursor = conn.execute(
                """
                INSERT INTO releases (
                    version_code,
                    version_name,
                    apk_path,
                    apk_url,
                    sha256,
                    size_bytes,
                    release_notes,
                    mandatory,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    int(version_code),
                    version_name,
                    str(apk_path),
                    placeholder_apk_url,
                    digest,
                    size_bytes,
                    release_notes.strip(),
                    1 if mandatory else 0,
                    STATUS_ACTIVE,
                ),
            )
            release_id = int(cursor.lastrowid)
            apk_url = f"{base_url.rstrip('/')}/releases/{release_id}/app.apk"
            conn.execute(
                "UPDATE releases SET apk_url = ? WHERE id = ?",
                (apk_url, release_id),
            )
            row = conn.execute(
                "SELECT * FROM releases WHERE id = ?",
                (release_id,),
            ).fetchone()

        return manifest_from_release(row)
    except Exception:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)
        if final_apk_written:
            apk_path.unlink(missing_ok=True)
        try:
            release_dir.rmdir()
        except OSError:
            pass
        raise


def set_default_release(settings: Settings, release_id: int) -> None:
    release = get_release_by_id(settings, release_id)
    if release is None or release["status"] != STATUS_ACTIVE:
        raise ValueError("default release must exist and be active")

    with db_session(settings) as conn:
        conn.execute(
            """
            INSERT INTO settings (key, value)
            VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
            """,
            (DEFAULT_RELEASE_KEY, str(int(release_id))),
        )


def create_device_rule(settings: Settings, nscode: str, release_id: int, note: str = "") -> int:
    if not nscode.strip():
        raise ValueError("nscode is required")

    release = get_release_by_id(settings, release_id)
    if release is None or release["status"] != STATUS_ACTIVE:
        raise ValueError("rule release must exist and be active")

    with db_session(settings) as conn:
        cursor = conn.execute(
            """
            INSERT INTO device_rules (nscode, release_id, enabled, note)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(nscode) DO UPDATE SET
                release_id = excluded.release_id,
                enabled = excluded.enabled,
                note = excluded.note,
                updated_at = CURRENT_TIMESTAMP
            """,
            (nscode.strip(), int(release_id), 1, note.strip()),
        )
        row = conn.execute(
            "SELECT id FROM device_rules WHERE nscode = ?",
            (nscode.strip(),),
        ).fetchone()
    return int(row["id"] if row is not None else cursor.lastrowid)


def delete_device_rule(settings: Settings, rule_id: int) -> None:
    with db_session(settings) as conn:
        conn.execute("DELETE FROM device_rules WHERE id = ?", (int(rule_id),))


def list_admin_state(settings: Settings) -> dict[str, object]:
    with db_session(settings) as conn:
        releases = conn.execute(
            "SELECT * FROM releases ORDER BY created_at DESC, id DESC"
        ).fetchall()
        rules = conn.execute(
            """
            SELECT
                device_rules.*,
                releases.version_code,
                releases.version_name
            FROM device_rules
            JOIN releases ON releases.id = device_rules.release_id
            ORDER BY device_rules.created_at DESC, device_rules.id DESC
            """
        ).fetchall()
        check_events = conn.execute(
            """
            SELECT * FROM check_events
            ORDER BY created_at DESC, id DESC
            LIMIT 30
            """
        ).fetchall()
        default_release_id = _get_default_release_id(conn)

    return {
        "default_release_id": default_release_id,
        "releases": [dict(row) for row in releases],
        "device_rules": [dict(row) for row in rules],
        "check_events": [dict(row) for row in check_events],
    }


def get_release_by_id(settings: Settings, release_id: int) -> Row | None:
    with db_session(settings) as conn:
        return conn.execute(
            "SELECT * FROM releases WHERE id = ?",
            (int(release_id),),
        ).fetchone()


def resolve_update(
    settings: Settings,
    nscode: str,
    current_version_code: int,
    base_url: str | None = None,
) -> dict[str, object]:
    if int(current_version_code) <= 0:
        raise ValueError("current_version_code must be positive")

    with db_session(settings) as conn:
        release = _find_rule_release(conn, nscode.strip())
        if release is None:
            default_release_id = _get_default_release_id(conn)
            if default_release_id is not None:
                release = conn.execute(
                    "SELECT * FROM releases WHERE id = ? AND status = ?",
                    (default_release_id, STATUS_ACTIVE),
                ).fetchone()

        if release is None:
            _insert_check_event(conn, nscode, current_version_code, None, RESULT_NO_RELEASE)
            return no_update_response()

        release_id = int(release["id"])
        if int(current_version_code) >= int(release["version_code"]):
            _insert_check_event(conn, nscode, current_version_code, release_id, RESULT_NO_UPDATE)
            return no_update_response()

        _insert_check_event(conn, nscode, current_version_code, release_id, RESULT_UPDATE)
        response = {"updateAvailable": True}
        response.update(manifest_from_release(release, base_url))
        return response


def get_latest_manifest(settings: Settings, base_url: str | None = None) -> dict[str, object] | None:
    with db_session(settings) as conn:
        default_release_id = _get_default_release_id(conn)
        if default_release_id is None:
            return None
        release = conn.execute(
            "SELECT * FROM releases WHERE id = ? AND status = ?",
            (default_release_id, STATUS_ACTIVE),
        ).fetchone()
        if release is None:
            return None
        return manifest_from_release(release, base_url)


def _get_default_release_id(conn) -> int | None:
    row = conn.execute(
        "SELECT value FROM settings WHERE key = ?",
        (DEFAULT_RELEASE_KEY,),
    ).fetchone()
    if row is None:
        return None
    return int(row["value"])


def _find_rule_release(conn, nscode: str) -> Row | None:
    if not nscode:
        return None
    return conn.execute(
        """
        SELECT releases.*
        FROM device_rules
        JOIN releases ON releases.id = device_rules.release_id
        WHERE device_rules.nscode = ?
          AND device_rules.enabled = 1
          AND releases.status = ?
        """,
        (nscode, STATUS_ACTIVE),
    ).fetchone()


def _insert_check_event(
    conn,
    nscode: str,
    current_version_code: int,
    matched_release_id: int | None,
    result: str,
) -> None:
    conn.execute(
        """
        INSERT INTO check_events (
            nscode,
            current_version_code,
            matched_release_id,
            result
        )
        VALUES (?, ?, ?, ?)
        """,
        (nscode, int(current_version_code), matched_release_id, result),
    )
