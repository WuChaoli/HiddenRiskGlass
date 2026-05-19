from __future__ import annotations

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
