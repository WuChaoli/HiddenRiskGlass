from __future__ import annotations


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
