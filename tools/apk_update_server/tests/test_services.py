from __future__ import annotations

from io import BytesIO
from pathlib import Path
import sqlite3

import pytest


def test_init_db_creates_required_tables(isolated_env):
    from app.config import load_settings
    from app.db import connect_db, init_db

    settings = load_settings()

    init_db(settings)

    with connect_db(settings) as conn:
        table_names = {
            row["name"]
            for row in conn.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
            ).fetchall()
        }

    assert {"releases", "settings", "device_rules", "check_events"} <= table_names


def test_device_rules_release_id_enforces_foreign_key(isolated_env):
    from app.config import load_settings
    from app.db import db_session, init_db

    settings = load_settings()
    init_db(settings)

    with pytest.raises(sqlite3.IntegrityError):
        with db_session(settings) as conn:
            conn.execute(
                """
                INSERT INTO device_rules (nscode, release_id, enabled, note)
                VALUES (?, ?, ?, ?)
                """,
                ("NSCODE-001", 999, 1, "missing release"),
            )


def test_device_rules_schema_matches_nscode_design(isolated_env):
    from app.config import load_settings
    from app.db import connect_db, init_db

    settings = load_settings()
    init_db(settings)

    with connect_db(settings) as conn:
        columns = {
            row["name"]
            for row in conn.execute("PRAGMA table_info(device_rules)").fetchall()
        }

    assert {"nscode", "release_id"} <= columns
    assert not {
        "device_id",
        "channel",
        "target_version_code",
        "min_version_code",
        "max_version_code",
    } & columns


def test_init_db_rolls_back_partial_schema_on_failure(isolated_env, monkeypatch):
    from app.config import load_settings
    from app.db import connect_db, init_db
    import app.db

    settings = load_settings()
    monkeypatch.setattr(
        app.db,
        "SCHEMA",
        """
        CREATE TABLE partial_table (
            id INTEGER PRIMARY KEY
        );
        INVALID SQL;
        """,
    )

    with pytest.raises(sqlite3.Error):
        init_db(settings)

    with connect_db(settings) as conn:
        partial_table = conn.execute(
            """
            SELECT name FROM sqlite_master
            WHERE type = 'table' AND name = 'partial_table'
            """
        ).fetchone()

    assert partial_table is None


def publish_test_release(
    settings,
    version_code: int,
    version_name: str,
    payload: bytes = b"apk-bytes",
):
    from app.services import publish_release

    return publish_release(
        settings=settings,
        filename="app-standard-debug.apk",
        fileobj=BytesIO(payload),
        version_code=version_code,
        version_name=version_name,
        release_notes=f"notes {version_name}",
        mandatory=True,
        base_url="http://127.0.0.1:8080",
    )


def test_publish_release_writes_apk_and_manifest_fields(isolated_env):
    from app.config import load_settings
    from app.db import connect_db, init_db
    from app.services import publish_release

    settings = load_settings()
    init_db(settings)

    manifest = publish_release(
        settings=settings,
        filename="app-standard-debug.apk",
        fileobj=BytesIO(b"apk-content"),
        version_code=3,
        version_name="2.0.6",
        release_notes="测试发布",
        mandatory=True,
        base_url="http://127.0.0.1:8080/",
    )

    assert manifest["versionCode"] == 3
    assert manifest["versionName"] == "2.0.6"
    assert manifest["apkUrl"] == "http://127.0.0.1:8080/releases/3-2.0.6/app.apk"
    assert manifest["sizeBytes"] == len(b"apk-content")
    assert manifest["releaseNotes"] == "测试发布"
    assert manifest["mandatory"] is True

    with connect_db(settings) as conn:
        release = conn.execute("SELECT * FROM releases").fetchone()

    assert Path(release["apk_path"]).read_bytes() == b"apk-content"
    assert release["version_code"] == 3
    assert release["version_name"] == "2.0.6"
    assert release["apk_url"] == manifest["apkUrl"]
    assert release["sha256"] == manifest["sha256"]
    assert release["size_bytes"] == len(b"apk-content")
    assert release["release_notes"] == "测试发布"
    assert release["mandatory"] == 1
    assert release["status"] == "active"


def test_default_release_resolves_update(isolated_env):
    from app.config import load_settings
    from app.db import connect_db, init_db
    from app.schemas import RESULT_UPDATE
    from app.services import resolve_update, set_default_release

    settings = load_settings()
    init_db(settings)
    publish_test_release(settings, 3, "2.0.6")
    with connect_db(settings) as conn:
        release_id = conn.execute("SELECT id FROM releases").fetchone()["id"]

    set_default_release(settings, release_id)
    response = resolve_update(settings, "NSCODE-001", 2)

    assert response["updateAvailable"] is True
    assert response["versionCode"] == 3
    with connect_db(settings) as conn:
        event = conn.execute("SELECT * FROM check_events").fetchone()
    assert event["nscode"] == "NSCODE-001"
    assert event["current_version_code"] == 2
    assert event["matched_release_id"] == release_id
    assert event["result"] == RESULT_UPDATE


def test_nscode_rule_overrides_default_release(isolated_env):
    from app.config import load_settings
    from app.db import connect_db, init_db
    from app.services import create_device_rule, resolve_update, set_default_release

    settings = load_settings()
    init_db(settings)
    publish_test_release(settings, 3, "2.0.6")
    publish_test_release(settings, 5, "2.1.0")
    with connect_db(settings) as conn:
        release_ids = [
            row["id"]
            for row in conn.execute("SELECT id FROM releases ORDER BY version_code").fetchall()
        ]

    set_default_release(settings, release_ids[0])
    create_device_rule(settings, "NSCODE-OVERRIDE", release_ids[1], note="canary")

    response = resolve_update(settings, "NSCODE-OVERRIDE", 2)

    assert response["updateAvailable"] is True
    assert response["versionCode"] == 5
    with connect_db(settings) as conn:
        event = conn.execute("SELECT * FROM check_events").fetchone()
    assert event["matched_release_id"] == release_ids[1]


def test_resolve_update_returns_false_when_current_version_is_new_enough(isolated_env):
    from app.config import load_settings
    from app.db import connect_db, init_db
    from app.schemas import RESULT_NO_UPDATE
    from app.services import resolve_update, set_default_release

    settings = load_settings()
    init_db(settings)
    publish_test_release(settings, 3, "2.0.6")
    with connect_db(settings) as conn:
        release_id = conn.execute("SELECT id FROM releases").fetchone()["id"]
    set_default_release(settings, release_id)

    response = resolve_update(settings, "NSCODE-001", 3)

    assert response == {"updateAvailable": False}
    with connect_db(settings) as conn:
        event = conn.execute("SELECT * FROM check_events").fetchone()
    assert event["matched_release_id"] == release_id
    assert event["result"] == RESULT_NO_UPDATE


def test_get_latest_manifest_returns_default_release(isolated_env):
    from app.config import load_settings
    from app.db import connect_db, init_db
    from app.services import get_latest_manifest, set_default_release

    settings = load_settings()
    init_db(settings)
    publish_test_release(settings, 3, "2.0.6")
    publish_test_release(settings, 5, "2.1.0")
    with connect_db(settings) as conn:
        release_id = conn.execute(
            "SELECT id FROM releases WHERE version_code = 3"
        ).fetchone()["id"]
    set_default_release(settings, release_id)

    manifest = get_latest_manifest(settings)

    assert manifest["versionCode"] == 3
    assert manifest["versionName"] == "2.0.6"
