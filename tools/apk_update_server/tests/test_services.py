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
