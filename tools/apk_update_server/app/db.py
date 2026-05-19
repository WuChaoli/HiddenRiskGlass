from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from collections.abc import Iterator

from app.config import Settings


SCHEMA = """
CREATE TABLE IF NOT EXISTS releases (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    version_name TEXT NOT NULL,
    version_code INTEGER NOT NULL,
    channel TEXT NOT NULL DEFAULT 'default',
    apk_filename TEXT NOT NULL,
    apk_sha256 TEXT NOT NULL,
    apk_size_bytes INTEGER NOT NULL,
    release_notes TEXT NOT NULL DEFAULT '',
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(channel, version_code)
);

CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS device_rules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT,
    channel TEXT NOT NULL DEFAULT 'default',
    min_version_code INTEGER,
    max_version_code INTEGER,
    target_version_code INTEGER,
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS check_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT,
    current_version_name TEXT,
    current_version_code INTEGER,
    channel TEXT NOT NULL DEFAULT 'default',
    matched_release_id INTEGER,
    result TEXT NOT NULL,
    user_agent TEXT,
    remote_addr TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(matched_release_id) REFERENCES releases(id) ON DELETE SET NULL
);
"""


def connect_db(settings: Settings) -> sqlite3.Connection:
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(settings.database_path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    return conn


def init_db(settings: Settings) -> None:
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    settings.releases_dir.mkdir(parents=True, exist_ok=True)
    with db_session(settings) as conn:
        conn.executescript(SCHEMA)


@contextmanager
def db_session(settings: Settings) -> Iterator[sqlite3.Connection]:
    conn = connect_db(settings)
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
