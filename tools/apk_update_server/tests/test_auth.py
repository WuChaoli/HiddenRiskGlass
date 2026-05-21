from __future__ import annotations


def test_database_initializes_with_users_table(isolated_env):
    from app.config import load_settings
    from app.db import init_db, connect_db

    settings = load_settings()
    init_db(settings)
    with connect_db(settings) as conn:
        tables = conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('users', 'verification_codes')"
        ).fetchall()
        table_names = {row["name"] for row in tables}
        assert "users" in table_names
        assert "verification_codes" in table_names
