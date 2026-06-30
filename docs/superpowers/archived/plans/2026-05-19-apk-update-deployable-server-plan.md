# APK Update Deployable Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the local APK update tool into a deployable FastAPI update service with admin login, SQLite-backed releases, default latest updates, and `nscode`-targeted device updates.

**Architecture:** Add a FastAPI app under `tools/apk_update_server/app/` while preserving the current tool directory and generated `releases/` storage. Server-side logic is split into database setup, authentication, service functions, HTTP routes, templates, and tests. Android migration is a second phase that changes update checks from static manifest fetches to the dynamic `/api/v1/updates/check` endpoint using `RokidSdkManager.getSerialNumber()` as `nscode`.

**Tech Stack:** Python 3, FastAPI, Uvicorn, SQLite, Jinja2, python-multipart, pytest, FastAPI TestClient, Kotlin, OkHttp, Gson.

---

## Scope

This plan implements both phases from the spec:

1. Server-side FastAPI update service.
2. Android client migration to the dynamic update check API.

The server phase is independently testable before any Android changes. If execution needs to be split, complete Tasks 1-9 first and verify the server, then complete Tasks 10-12 for Android.

## File Structure

- Create `tools/apk_update_server/requirements.txt`: Python runtime and test dependencies.
- Create `tools/apk_update_server/app/__init__.py`: package marker.
- Create `tools/apk_update_server/app/config.py`: environment configuration and data paths.
- Create `tools/apk_update_server/app/db.py`: SQLite connection and schema initialization.
- Create `tools/apk_update_server/app/schemas.py`: response model helpers and constants.
- Create `tools/apk_update_server/app/auth.py`: admin password verification and session helpers.
- Create `tools/apk_update_server/app/services.py`: APK publishing, release lookup, default latest manifest, `nscode` matching, check event logging.
- Create `tools/apk_update_server/app/main.py`: FastAPI routes, templates, static file mounting, startup checks.
- Create `tools/apk_update_server/app/templates/login.html`: admin login page.
- Create `tools/apk_update_server/app/templates/admin.html`: release and device-rule admin page.
- Create `tools/apk_update_server/app/static/admin.css`: minimal admin UI styles.
- Modify `tools/apk_update_server/server.py`: compatibility wrapper that starts the FastAPI app through Uvicorn.
- Modify `tools/apk_update_server/serve.ps1`: installable server entrypoint that starts `server.py`.
- Modify `tools/apk_update_server/README.md`: document dependencies, environment variables, admin flow, API, deployment notes.
- Create `tools/apk_update_server/tests/conftest.py`: isolated temp data directory and FastAPI client fixture.
- Create `tools/apk_update_server/tests/test_services.py`: database and matching-rule tests.
- Create `tools/apk_update_server/tests/test_api.py`: auth, publishing, check endpoint, static compatibility tests.
- Modify `app/src/main/java/com/rokid/glass/updater/AppUpdateInfo.kt`: add dynamic check response type.
- Modify `app/src/main/java/com/rokid/glass/updater/AppUpdateClient.kt`: add dynamic check endpoint call and static fallback.
- Modify `app/src/main/java/com/rokid/glass/updater/AppUpdateManager.kt`: pass `nscode` into the client and handle no-update responses.
- No UI activity layout changes are required.

## Task 1: Add Python Dependency and App Skeleton

**Files:**
- Create: `tools/apk_update_server/requirements.txt`
- Create: `tools/apk_update_server/app/__init__.py`
- Create: `tools/apk_update_server/app/config.py`

- [ ] **Step 1: Add Python requirements**

Create `tools/apk_update_server/requirements.txt`:

```text
fastapi==0.115.6
uvicorn[standard]==0.32.1
jinja2==3.1.4
python-multipart==0.0.19
pytest==8.3.4
httpx==0.28.1
```

- [ ] **Step 2: Create app package marker**

Create `tools/apk_update_server/app/__init__.py`:

```python
"""Deployable APK update server package."""
```

- [ ] **Step 3: Add configuration module**

Create `tools/apk_update_server/app/config.py`:

```python
from __future__ import annotations

import os
import secrets
from dataclasses import dataclass
from pathlib import Path


APP_ROOT = Path(__file__).resolve().parents[1]
PROJECT_ROOT = APP_ROOT.parent.parent


@dataclass(frozen=True)
class Settings:
    app_root: Path
    data_dir: Path
    releases_dir: Path
    database_path: Path
    admin_password: str
    session_secret: str
    session_cookie_secure: bool


def load_settings(require_admin_password: bool = True) -> Settings:
    data_dir = Path(os.environ.get("APK_UPDATE_DATA_DIR", APP_ROOT)).resolve()
    admin_password = os.environ.get("ADMIN_PASSWORD", "")
    if require_admin_password and not admin_password:
        raise RuntimeError("ADMIN_PASSWORD must be set before starting the APK update server")

    session_secret = os.environ.get("SESSION_SECRET") or secrets.token_urlsafe(32)
    cookie_secure = os.environ.get("SESSION_COOKIE_SECURE", "").lower() in {"1", "true", "yes"}
    releases_dir = data_dir / "releases"
    return Settings(
        app_root=APP_ROOT,
        data_dir=data_dir,
        releases_dir=releases_dir,
        database_path=data_dir / "apk_update_server.sqlite3",
        admin_password=admin_password,
        session_secret=session_secret,
        session_cookie_secure=cookie_secure,
    )
```

- [ ] **Step 4: Install dependencies locally**

Run:

```powershell
python -m pip install -r .\tools\apk_update_server\requirements.txt
```

Expected: command exits with code `0`.

- [ ] **Step 5: Verify imports compile**

Run:

```powershell
python -m py_compile .\tools\apk_update_server\app\config.py
```

Expected: command exits with code `0`.

- [ ] **Step 6: Commit skeleton**

Run:

```powershell
git add -- tools\apk_update_server\requirements.txt tools\apk_update_server\app\__init__.py tools\apk_update_server\app\config.py
git commit -m "chore: add apk update server fastapi skeleton"
```

Expected: commit succeeds.

## Task 2: Add SQLite Schema and Database Helpers

**Files:**
- Create: `tools/apk_update_server/app/db.py`
- Test: `tools/apk_update_server/tests/conftest.py`
- Test: `tools/apk_update_server/tests/test_services.py`

- [ ] **Step 1: Create test fixtures**

Create `tools/apk_update_server/tests/conftest.py`:

```python
from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))


@pytest.fixture()
def isolated_env(tmp_path, monkeypatch):
    monkeypatch.setenv("APK_UPDATE_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("ADMIN_PASSWORD", "test-password")
    monkeypatch.setenv("SESSION_SECRET", "test-session-secret")
    monkeypatch.delenv("SESSION_COOKIE_SECURE", raising=False)
    return tmp_path
```

- [ ] **Step 2: Write failing database initialization test**

Create `tools/apk_update_server/tests/test_services.py`:

```python
from __future__ import annotations

from app.config import load_settings
from app.db import connect_db, init_db


def test_init_db_creates_required_tables(isolated_env):
    settings = load_settings()
    init_db(settings)

    with connect_db(settings) as conn:
        rows = conn.execute(
            "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name"
        ).fetchall()

    table_names = {row["name"] for row in rows}
    assert {"releases", "settings", "device_rules", "check_events"}.issubset(table_names)
```

- [ ] **Step 3: Run test to verify it fails**

Run:

```powershell
python -m pytest .\tools\apk_update_server\tests\test_services.py::test_init_db_creates_required_tables -q
```

Expected: FAIL with `ModuleNotFoundError: No module named 'app.db'`.

- [ ] **Step 4: Implement database helpers**

Create `tools/apk_update_server/app/db.py`:

```python
from __future__ import annotations

import sqlite3
from contextlib import contextmanager
from typing import Iterator

from .config import Settings


SCHEMA = """
CREATE TABLE IF NOT EXISTS releases (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    version_code INTEGER NOT NULL,
    version_name TEXT NOT NULL,
    apk_path TEXT NOT NULL,
    apk_url TEXT NOT NULL,
    sha256 TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    release_notes TEXT NOT NULL DEFAULT '',
    mandatory INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'active',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS device_rules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nscode TEXT NOT NULL UNIQUE,
    release_id INTEGER NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1,
    note TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (release_id) REFERENCES releases(id)
);

CREATE TABLE IF NOT EXISTS check_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nscode TEXT NOT NULL DEFAULT '',
    current_version_code INTEGER NOT NULL,
    matched_release_id INTEGER,
    result TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (matched_release_id) REFERENCES releases(id)
);
"""


def connect_db(settings: Settings) -> sqlite3.Connection:
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(settings.database_path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    return conn


def init_db(settings: Settings) -> None:
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    settings.releases_dir.mkdir(parents=True, exist_ok=True)
    with connect_db(settings) as conn:
        conn.executescript(SCHEMA)
        conn.commit()


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
```

- [ ] **Step 5: Run database test**

Run:

```powershell
python -m pytest .\tools\apk_update_server\tests\test_services.py::test_init_db_creates_required_tables -q
```

Expected: PASS.

- [ ] **Step 6: Commit database helpers**

Run:

```powershell
git add -- tools\apk_update_server\app\db.py tools\apk_update_server\tests\conftest.py tools\apk_update_server\tests\test_services.py
git commit -m "feat: add apk update server database schema"
```

Expected: commit succeeds.

## Task 3: Add Release Publishing and Matching Services

**Files:**
- Create: `tools/apk_update_server/app/schemas.py`
- Create: `tools/apk_update_server/app/services.py`
- Modify: `tools/apk_update_server/tests/test_services.py`

- [ ] **Step 1: Add failing service tests**

Append to `tools/apk_update_server/tests/test_services.py`:

```python
from io import BytesIO

from app.services import (
    create_device_rule,
    get_latest_manifest,
    publish_release,
    resolve_update,
    set_default_release,
)


def test_publish_release_writes_apk_and_manifest_fields(isolated_env):
    settings = load_settings()
    init_db(settings)

    release = publish_release(
        settings=settings,
        filename="app-standard-debug.apk",
        fileobj=BytesIO(b"fake apk bytes"),
        version_code=10,
        version_name="1.0.10",
        release_notes="server test",
        mandatory=False,
        base_url="http://127.0.0.1:8080",
    )

    assert release["version_code"] == 10
    assert release["version_name"] == "1.0.10"
    assert release["size_bytes"] == len(b"fake apk bytes")
    assert len(release["sha256"]) == 64
    assert (settings.releases_dir / "10_1.0.10" / "app.apk").is_file()


def test_default_release_resolves_update(isolated_env):
    settings = load_settings()
    init_db(settings)
    release = publish_release(
        settings=settings,
        filename="app.apk",
        fileobj=BytesIO(b"default apk"),
        version_code=11,
        version_name="1.0.11",
        release_notes="default",
        mandatory=True,
        base_url="http://updates.test",
    )
    set_default_release(settings, release["id"])

    result = resolve_update(settings, nscode="", current_version_code=1)

    assert result["versionCode"] == 11
    assert result["mandatory"] is True
    assert result["apkUrl"] == "http://updates.test/releases/1/app.apk"


def test_nscode_rule_overrides_default_release(isolated_env):
    settings = load_settings()
    init_db(settings)
    default_release = publish_release(
        settings=settings,
        filename="default.apk",
        fileobj=BytesIO(b"default apk"),
        version_code=20,
        version_name="2.0.0",
        release_notes="default",
        mandatory=False,
        base_url="http://updates.test",
    )
    target_release = publish_release(
        settings=settings,
        filename="target.apk",
        fileobj=BytesIO(b"target apk"),
        version_code=30,
        version_name="3.0.0",
        release_notes="target",
        mandatory=False,
        base_url="http://updates.test",
    )
    set_default_release(settings, default_release["id"])
    create_device_rule(settings, nscode="RK-001", release_id=target_release["id"], note="pilot")

    target_result = resolve_update(settings, nscode="RK-001", current_version_code=1)
    other_result = resolve_update(settings, nscode="RK-002", current_version_code=1)

    assert target_result["versionCode"] == 30
    assert other_result["versionCode"] == 20


def test_resolve_update_returns_no_update_when_current_is_new_enough(isolated_env):
    settings = load_settings()
    init_db(settings)
    release = publish_release(
        settings=settings,
        filename="app.apk",
        fileobj=BytesIO(b"default apk"),
        version_code=11,
        version_name="1.0.11",
        release_notes="default",
        mandatory=False,
        base_url="http://updates.test",
    )
    set_default_release(settings, release["id"])

    result = resolve_update(settings, nscode="RK-001", current_version_code=11)

    assert result == {"updateAvailable": False}


def test_latest_manifest_returns_default_release(isolated_env):
    settings = load_settings()
    init_db(settings)
    release = publish_release(
        settings=settings,
        filename="app.apk",
        fileobj=BytesIO(b"default apk"),
        version_code=12,
        version_name="1.0.12",
        release_notes="default latest",
        mandatory=False,
        base_url="http://updates.test",
    )
    set_default_release(settings, release["id"])

    manifest = get_latest_manifest(settings)

    assert manifest["versionCode"] == 12
    assert manifest["apkUrl"] == "http://updates.test/releases/1/app.apk"
```

- [ ] **Step 2: Run service tests to verify they fail**

Run:

```powershell
python -m pytest .\tools\apk_update_server\tests\test_services.py -q
```

Expected: FAIL with missing `app.services`.

- [ ] **Step 3: Add schema constants**

Create `tools/apk_update_server/app/schemas.py`:

```python
from __future__ import annotations


RESULT_UPDATE = "update"
RESULT_NO_UPDATE = "no_update"
RESULT_NO_RELEASE = "no_release"
STATUS_ACTIVE = "active"
STATUS_DISABLED = "disabled"


def no_update_response() -> dict:
    return {"updateAvailable": False}
```

- [ ] **Step 4: Implement service layer**

Create `tools/apk_update_server/app/services.py`:

```python
from __future__ import annotations

import re
import shutil
import tempfile
from hashlib import sha256
from pathlib import Path
from typing import BinaryIO

from .config import Settings
from .db import connect_db, db_session
from .schemas import RESULT_NO_RELEASE, RESULT_NO_UPDATE, RESULT_UPDATE, STATUS_ACTIVE, no_update_response


def sha256_file(path: Path) -> str:
    digest = sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_version_dir(version_code: int, version_name: str) -> str:
    safe_name = re.sub(r"[^A-Za-z0-9._-]+", "_", version_name).strip("._-") or "version"
    return f"{version_code}_{safe_name}"


def manifest_from_release(row) -> dict:
    return {
        "versionCode": int(row["version_code"]),
        "versionName": row["version_name"],
        "apkUrl": row["apk_url"],
        "sha256": row["sha256"],
        "sizeBytes": int(row["size_bytes"]),
        "releaseNotes": row["release_notes"],
        "mandatory": bool(row["mandatory"]),
    }


def publish_release(
    *,
    settings: Settings,
    filename: str,
    fileobj: BinaryIO,
    version_code: int,
    version_name: str,
    release_notes: str,
    mandatory: bool,
    base_url: str,
) -> dict:
    if version_code <= 0:
        raise ValueError("versionCode must be a positive integer")
    version_name = version_name.strip()
    if not version_name:
        raise ValueError("versionName is required")
    if not filename.lower().endswith(".apk"):
        raise ValueError("APK filename must end with .apk")

    release_dir = settings.releases_dir / safe_version_dir(version_code, version_name)
    release_dir.mkdir(parents=True, exist_ok=True)
    apk_path = release_dir / "app.apk"
    temp_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, dir=release_dir, suffix=".upload") as temp_file:
            temp_path = Path(temp_file.name)
            shutil.copyfileobj(fileobj, temp_file)
        if temp_path.stat().st_size <= 0:
            raise ValueError("APK file is empty")
        shutil.move(str(temp_path), apk_path)
        digest = sha256_file(apk_path)
        size_bytes = apk_path.stat().st_size

        with db_session(settings) as conn:
            cursor = conn.execute(
                """
                INSERT INTO releases (
                    version_code, version_name, apk_path, apk_url, sha256,
                    size_bytes, release_notes, mandatory, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    version_code,
                    version_name,
                    str(apk_path),
                    f"{base_url.rstrip('/')}/releases/{{release_id}}/app.apk",
                    digest,
                    size_bytes,
                    release_notes.strip(),
                    1 if mandatory else 0,
                    STATUS_ACTIVE,
                ),
            )
            release_id = int(cursor.lastrowid)
            apk_url = f"{base_url.rstrip('/')}/releases/{release_id}/app.apk"
            conn.execute("UPDATE releases SET apk_url = ? WHERE id = ?", (apk_url, release_id))
            row = conn.execute("SELECT * FROM releases WHERE id = ?", (release_id,)).fetchone()
            return dict(row)
    except Exception:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)
        raise


def set_default_release(settings: Settings, release_id: int) -> None:
    with db_session(settings) as conn:
        row = conn.execute(
            "SELECT id FROM releases WHERE id = ? AND status = ?",
            (release_id, STATUS_ACTIVE),
        ).fetchone()
        if row is None:
            raise ValueError("active release not found")
        conn.execute(
            "INSERT INTO settings(key, value) VALUES('default_release_id', ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (str(release_id),),
        )


def create_device_rule(settings: Settings, *, nscode: str, release_id: int, note: str = "") -> dict:
    normalized = nscode.strip()
    if not normalized:
        raise ValueError("nscode is required")
    with db_session(settings) as conn:
        release = conn.execute(
            "SELECT id FROM releases WHERE id = ? AND status = ?",
            (release_id, STATUS_ACTIVE),
        ).fetchone()
        if release is None:
            raise ValueError("active release not found")
        conn.execute(
            """
            INSERT INTO device_rules(nscode, release_id, enabled, note, updated_at)
            VALUES(?, ?, 1, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(nscode) DO UPDATE SET
                release_id = excluded.release_id,
                enabled = 1,
                note = excluded.note,
                updated_at = CURRENT_TIMESTAMP
            """,
            (normalized, release_id, note.strip()),
        )
        row = conn.execute("SELECT * FROM device_rules WHERE nscode = ?", (normalized,)).fetchone()
        return dict(row)


def delete_device_rule(settings: Settings, rule_id: int) -> None:
    with db_session(settings) as conn:
        conn.execute("DELETE FROM device_rules WHERE id = ?", (rule_id,))


def list_admin_state(settings: Settings) -> dict:
    with connect_db(settings) as conn:
        releases = conn.execute("SELECT * FROM releases ORDER BY created_at DESC, id DESC").fetchall()
        rules = conn.execute(
            """
            SELECT device_rules.*, releases.version_code, releases.version_name
            FROM device_rules
            JOIN releases ON releases.id = device_rules.release_id
            ORDER BY device_rules.updated_at DESC, device_rules.id DESC
            """
        ).fetchall()
        events = conn.execute(
            "SELECT * FROM check_events ORDER BY created_at DESC, id DESC LIMIT 30"
        ).fetchall()
        default_row = conn.execute(
            "SELECT value FROM settings WHERE key = 'default_release_id'"
        ).fetchone()
    return {
        "releases": [dict(row) for row in releases],
        "device_rules": [dict(row) for row in rules],
        "check_events": [dict(row) for row in events],
        "default_release_id": int(default_row["value"]) if default_row else None,
    }


def get_release_by_id(settings: Settings, release_id: int):
    with connect_db(settings) as conn:
        return conn.execute(
            "SELECT * FROM releases WHERE id = ? AND status = ?",
            (release_id, STATUS_ACTIVE),
        ).fetchone()


def _select_target_release(conn, nscode: str):
    if nscode:
        row = conn.execute(
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
        if row is not None:
            return row

    default_row = conn.execute("SELECT value FROM settings WHERE key = 'default_release_id'").fetchone()
    if default_row is None:
        return None
    return conn.execute(
        "SELECT * FROM releases WHERE id = ? AND status = ?",
        (int(default_row["value"]), STATUS_ACTIVE),
    ).fetchone()


def _record_check(settings: Settings, *, nscode: str, current_version_code: int, release_id, result: str) -> None:
    with db_session(settings) as conn:
        conn.execute(
            """
            INSERT INTO check_events(nscode, current_version_code, matched_release_id, result)
            VALUES(?, ?, ?, ?)
            """,
            (nscode, current_version_code, release_id, result),
        )


def resolve_update(settings: Settings, *, nscode: str, current_version_code: int) -> dict:
    if current_version_code <= 0:
        raise ValueError("currentVersionCode must be a positive integer")
    normalized = nscode.strip()
    with connect_db(settings) as conn:
        row = _select_target_release(conn, normalized)
    if row is None:
        _record_check(
            settings,
            nscode=normalized,
            current_version_code=current_version_code,
            release_id=None,
            result=RESULT_NO_RELEASE,
        )
        return no_update_response()
    if int(row["version_code"]) <= current_version_code:
        _record_check(
            settings,
            nscode=normalized,
            current_version_code=current_version_code,
            release_id=int(row["id"]),
            result=RESULT_NO_UPDATE,
        )
        return no_update_response()
    _record_check(
        settings,
        nscode=normalized,
        current_version_code=current_version_code,
        release_id=int(row["id"]),
        result=RESULT_UPDATE,
    )
    return manifest_from_release(row)


def get_latest_manifest(settings: Settings) -> dict | None:
    with connect_db(settings) as conn:
        row = _select_target_release(conn, "")
    if row is None:
        return None
    return manifest_from_release(row)
```

- [ ] **Step 5: Run service tests**

Run:

```powershell
python -m pytest .\tools\apk_update_server\tests\test_services.py -q
```

Expected: PASS.

- [ ] **Step 6: Compile service modules**

Run:

```powershell
python -m py_compile .\tools\apk_update_server\app\schemas.py .\tools\apk_update_server\app\services.py
```

Expected: command exits with code `0`.

- [ ] **Step 7: Commit service layer**

Run:

```powershell
git add -- tools\apk_update_server\app\schemas.py tools\apk_update_server\app\services.py tools\apk_update_server\tests\test_services.py
git commit -m "feat: add apk update release matching services"
```

Expected: commit succeeds.

## Task 4: Add Authentication Helpers

**Files:**
- Create: `tools/apk_update_server/app/auth.py`
- Test: `tools/apk_update_server/tests/test_api.py`

- [ ] **Step 1: Write failing auth test**

Create `tools/apk_update_server/tests/test_api.py`:

```python
from __future__ import annotations

from fastapi.testclient import TestClient


def test_admin_requires_login(isolated_env):
    from app.main import create_app

    client = TestClient(create_app())
    response = client.get("/admin", follow_redirects=False)

    assert response.status_code == 303
    assert response.headers["location"] == "/login"
```

- [ ] **Step 2: Run auth test to verify it fails**

Run:

```powershell
python -m pytest .\tools\apk_update_server\tests\test_api.py::test_admin_requires_login -q
```

Expected: FAIL with missing `app.main`.

- [ ] **Step 3: Add auth helpers**

Create `tools/apk_update_server/app/auth.py`:

```python
from __future__ import annotations

import hmac

from fastapi import Request
from starlette.responses import RedirectResponse

from .config import Settings


SESSION_ADMIN_KEY = "admin_authenticated"


def verify_password(settings: Settings, password: str) -> bool:
    return hmac.compare_digest(password, settings.admin_password)


def mark_logged_in(request: Request) -> None:
    request.session[SESSION_ADMIN_KEY] = True


def mark_logged_out(request: Request) -> None:
    request.session.clear()


def is_logged_in(request: Request) -> bool:
    return request.session.get(SESSION_ADMIN_KEY) is True


def require_admin(request: Request):
    if not is_logged_in(request):
        return RedirectResponse("/login", status_code=303)
    return None
```

- [ ] **Step 4: Compile auth helper**

Run:

```powershell
python -m py_compile .\tools\apk_update_server\app\auth.py
```

Expected: command exits with code `0`.

Do not commit yet; Task 5 wires this into routes and makes the failing test pass.

## Task 5: Add FastAPI Routes, Templates, and Static Admin UI

**Files:**
- Create: `tools/apk_update_server/app/main.py`
- Create: `tools/apk_update_server/app/templates/login.html`
- Create: `tools/apk_update_server/app/templates/admin.html`
- Create: `tools/apk_update_server/app/static/admin.css`
- Modify: `tools/apk_update_server/tests/test_api.py`

- [ ] **Step 1: Add API tests**

Append to `tools/apk_update_server/tests/test_api.py`:

```python
from pathlib import Path


def login(client: TestClient) -> None:
    response = client.post(
        "/login",
        data={"password": "test-password"},
        follow_redirects=False,
    )
    assert response.status_code == 303
    assert response.headers["location"] == "/admin"


def test_login_allows_admin_access(isolated_env):
    from app.main import create_app

    client = TestClient(create_app())
    login(client)

    response = client.get("/admin")

    assert response.status_code == 200
    assert "APK 更新后台" in response.text


def test_publish_default_and_check_update(isolated_env):
    from app.main import create_app

    client = TestClient(create_app())
    login(client)

    publish_response = client.post(
        "/admin/releases",
        data={
            "versionCode": "101",
            "versionName": "1.0.101",
            "releaseNotes": "api test",
            "mandatory": "on",
            "makeDefault": "on",
        },
        files={"apk": ("app.apk", b"api apk bytes", "application/vnd.android.package-archive")},
        follow_redirects=False,
    )
    assert publish_response.status_code == 303

    check_response = client.get("/api/v1/updates/check?nscode=RK-001&currentVersionCode=1")

    assert check_response.status_code == 200
    payload = check_response.json()
    assert payload["versionCode"] == 101
    assert payload["versionName"] == "1.0.101"
    assert payload["mandatory"] is True


def test_nscode_rule_overrides_default_over_api(isolated_env):
    from app.main import create_app

    client = TestClient(create_app())
    login(client)
    client.post(
        "/admin/releases",
        data={
            "versionCode": "201",
            "versionName": "2.0.1",
            "releaseNotes": "default",
            "makeDefault": "on",
        },
        files={"apk": ("default.apk", b"default apk", "application/vnd.android.package-archive")},
        follow_redirects=False,
    )
    client.post(
        "/admin/releases",
        data={
            "versionCode": "301",
            "versionName": "3.0.1",
            "releaseNotes": "target",
        },
        files={"apk": ("target.apk", b"target apk", "application/vnd.android.package-archive")},
        follow_redirects=False,
    )
    client.post(
        "/admin/device-rules",
        data={"nscode": "RK-TARGET", "releaseId": "2", "note": "pilot"},
        follow_redirects=False,
    )

    target = client.get("/api/v1/updates/check?nscode=RK-TARGET&currentVersionCode=1").json()
    other = client.get("/api/v1/updates/check?nscode=RK-OTHER&currentVersionCode=1").json()

    assert target["versionCode"] == 301
    assert other["versionCode"] == 201


def test_latest_manifest_compatibility_endpoint(isolated_env):
    from app.main import create_app

    client = TestClient(create_app())
    login(client)
    client.post(
        "/admin/releases",
        data={
            "versionCode": "401",
            "versionName": "4.0.1",
            "releaseNotes": "latest",
            "makeDefault": "on",
        },
        files={"apk": ("latest.apk", b"latest apk", "application/vnd.android.package-archive")},
        follow_redirects=False,
    )

    response = client.get("/releases/latest/update.json")

    assert response.status_code == 200
    assert response.json()["versionCode"] == 401


def test_release_apk_download_endpoint(isolated_env):
    from app.main import create_app

    client = TestClient(create_app())
    login(client)
    client.post(
        "/admin/releases",
        data={"versionCode": "501", "versionName": "5.0.1", "releaseNotes": "download"},
        files={"apk": ("download.apk", b"download apk", "application/vnd.android.package-archive")},
        follow_redirects=False,
    )

    response = client.get("/releases/1/app.apk")

    assert response.status_code == 200
    assert response.content == b"download apk"
```

- [ ] **Step 2: Run API tests to verify they fail**

Run:

```powershell
python -m pytest .\tools\apk_update_server\tests\test_api.py -q
```

Expected: FAIL because `app.main` is not implemented.

- [ ] **Step 3: Create admin stylesheet**

Create `tools/apk_update_server/app/static/admin.css`:

```css
:root {
  color-scheme: light;
  --bg: #f6f8fb;
  --panel: #ffffff;
  --text: #172033;
  --muted: #647084;
  --line: #d8dee8;
  --accent: #0f766e;
  --danger: #b42318;
}

* { box-sizing: border-box; }
body {
  margin: 0;
  font-family: "Segoe UI", Arial, sans-serif;
  background: var(--bg);
  color: var(--text);
}
main {
  width: min(1120px, calc(100vw - 32px));
  margin: 28px auto 48px;
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}
.panel {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 18px;
  margin: 14px 0;
}
h1 { margin: 0; font-size: 28px; }
h2 { margin: 0 0 14px; font-size: 19px; }
form.grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}
label { display: grid; gap: 6px; font-weight: 600; }
input, textarea, select {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 9px 10px;
  font: inherit;
  background: white;
}
textarea { min-height: 88px; resize: vertical; }
button {
  border: 0;
  border-radius: 6px;
  padding: 10px 14px;
  background: var(--accent);
  color: white;
  font-weight: 700;
  cursor: pointer;
}
button.secondary { background: #334155; }
button.danger { background: var(--danger); }
.full { grid-column: 1 / -1; }
.hint { color: var(--muted); font-size: 13px; font-weight: 400; }
table { width: 100%; border-collapse: collapse; }
th, td {
  border-bottom: 1px solid var(--line);
  padding: 9px 8px;
  text-align: left;
  vertical-align: top;
}
code { overflow-wrap: anywhere; }
.inline { display: inline; }
.alert {
  border: 1px solid var(--line);
  background: #eef6ff;
  padding: 10px 12px;
  border-radius: 6px;
  margin-bottom: 12px;
}
@media (max-width: 760px) {
  form.grid { grid-template-columns: 1fr; }
  .topbar { align-items: flex-start; flex-direction: column; }
}
```

- [ ] **Step 4: Create login template**

Create `tools/apk_update_server/app/templates/login.html`:

```html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>APK 更新后台登录</title>
  <link rel="stylesheet" href="/static/admin.css">
</head>
<body>
  <main>
    <section class="panel">
      <h1>APK 更新后台登录</h1>
      {% if error %}<p class="alert">{{ error }}</p>{% endif %}
      <form method="post" action="/login" class="grid">
        <label class="full">
          管理员密码
          <input type="password" name="password" autocomplete="current-password" required>
        </label>
        <button type="submit">登录</button>
      </form>
    </section>
  </main>
</body>
</html>
```

- [ ] **Step 5: Create admin template**

Create `tools/apk_update_server/app/templates/admin.html`:

```html
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>APK 更新后台</title>
  <link rel="stylesheet" href="/static/admin.css">
</head>
<body>
  <main>
    <div class="topbar">
      <h1>APK 更新后台</h1>
      <form method="post" action="/logout"><button class="secondary" type="submit">退出</button></form>
    </div>
    {% if message %}<p class="alert">{{ message }}</p>{% endif %}

    <section class="panel">
      <h2>发布 APK</h2>
      <form method="post" action="/admin/releases" enctype="multipart/form-data" class="grid">
        <label class="full">APK 文件<input type="file" name="apk" accept=".apk" required></label>
        <label>versionCode<input type="number" name="versionCode" min="1" step="1" required></label>
        <label>versionName<input type="text" name="versionName" required></label>
        <label class="full">releaseNotes<textarea name="releaseNotes"></textarea></label>
        <label><span><input type="checkbox" name="mandatory"> 强制更新</span></label>
        <label><span><input type="checkbox" name="makeDefault"> 设为默认最新版</span></label>
        <button type="submit">发布</button>
      </form>
    </section>

    <section class="panel">
      <h2>发布版本</h2>
      <table>
        <thead>
          <tr><th>ID</th><th>版本</th><th>默认</th><th>APK</th><th>SHA-256</th><th>操作</th></tr>
        </thead>
        <tbody>
          {% for release in releases %}
          <tr>
            <td>{{ release.id }}</td>
            <td>{{ release.version_name }} ({{ release.version_code }})</td>
            <td>{{ "是" if release.id == default_release_id else "否" }}</td>
            <td><a href="/releases/{{ release.id }}/app.apk">下载</a></td>
            <td><code>{{ release.sha256 }}</code></td>
            <td>
              <form method="post" action="/admin/default-release" class="inline">
                <input type="hidden" name="releaseId" value="{{ release.id }}">
                <button type="submit">设为默认</button>
              </form>
            </td>
          </tr>
          {% endfor %}
        </tbody>
      </table>
    </section>

    <section class="panel">
      <h2>nscode 定向规则</h2>
      <form method="post" action="/admin/device-rules" class="grid">
        <label>nscode<input type="text" name="nscode" required></label>
        <label>
          目标版本
          <select name="releaseId" required>
            {% for release in releases %}
            <option value="{{ release.id }}">{{ release.version_name }} ({{ release.version_code }})</option>
            {% endfor %}
          </select>
        </label>
        <label class="full">备注<input type="text" name="note"></label>
        <button type="submit">保存规则</button>
      </form>
      <table>
        <thead>
          <tr><th>nscode</th><th>目标版本</th><th>备注</th><th>操作</th></tr>
        </thead>
        <tbody>
          {% for rule in device_rules %}
          <tr>
            <td><code>{{ rule.nscode }}</code></td>
            <td>{{ rule.version_name }} ({{ rule.version_code }})</td>
            <td>{{ rule.note }}</td>
            <td>
              <form method="post" action="/admin/device-rules/{{ rule.id }}/delete" class="inline">
                <button class="danger" type="submit">删除</button>
              </form>
            </td>
          </tr>
          {% endfor %}
        </tbody>
      </table>
    </section>

    <section class="panel">
      <h2>最近检查</h2>
      <table>
        <thead>
          <tr><th>时间</th><th>nscode</th><th>当前版本</th><th>命中版本</th><th>结果</th></tr>
        </thead>
        <tbody>
          {% for event in check_events %}
          <tr>
            <td>{{ event.created_at }}</td>
            <td><code>{{ event.nscode }}</code></td>
            <td>{{ event.current_version_code }}</td>
            <td>{{ event.matched_release_id or "-" }}</td>
            <td>{{ event.result }}</td>
          </tr>
          {% endfor %}
        </tbody>
      </table>
    </section>
  </main>
</body>
</html>
```

- [ ] **Step 6: Implement FastAPI routes**

Create `tools/apk_update_server/app/main.py`:

```python
from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from starlette.middleware.sessions import SessionMiddleware

from .auth import is_logged_in, mark_logged_in, mark_logged_out, require_admin, verify_password
from .config import Settings, load_settings
from .db import init_db
from .services import (
    create_device_rule,
    delete_device_rule,
    get_latest_manifest,
    get_release_by_id,
    list_admin_state,
    publish_release,
    resolve_update,
    set_default_release,
)


TEMPLATE_DIR = Path(__file__).resolve().parent / "templates"
STATIC_DIR = Path(__file__).resolve().parent / "static"
templates = Jinja2Templates(directory=str(TEMPLATE_DIR))


def build_base_url(request: Request) -> str:
    return str(request.base_url).rstrip("/")


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or load_settings()
    init_db(settings)
    app = FastAPI(title="APK Update Server")
    app.state.settings = settings
    app.add_middleware(
        SessionMiddleware,
        secret_key=settings.session_secret,
        https_only=settings.session_cookie_secure,
        same_site="lax",
    )
    app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")

    @app.get("/login")
    def login_page(request: Request):
        if is_logged_in(request):
            return RedirectResponse("/admin", status_code=303)
        return templates.TemplateResponse("login.html", {"request": request, "error": ""})

    @app.post("/login")
    async def login(request: Request, password: str = Form(...)):
        if not verify_password(settings, password):
            return templates.TemplateResponse(
                "login.html",
                {"request": request, "error": "密码错误"},
                status_code=401,
            )
        mark_logged_in(request)
        return RedirectResponse("/admin", status_code=303)

    @app.post("/logout")
    async def logout(request: Request):
        mark_logged_out(request)
        return RedirectResponse("/login", status_code=303)

    @app.get("/")
    def root():
        return RedirectResponse("/admin", status_code=303)

    @app.get("/admin")
    def admin(request: Request, message: str = ""):
        redirect = require_admin(request)
        if redirect:
            return redirect
        state = list_admin_state(settings)
        return templates.TemplateResponse(
            "admin.html",
            {"request": request, "message": message, **state},
        )

    @app.post("/admin/releases")
    async def admin_create_release(
        request: Request,
        apk: UploadFile = File(...),
        versionCode: int = Form(...),
        versionName: str = Form(...),
        releaseNotes: str = Form(""),
        mandatory: str | None = Form(None),
        makeDefault: str | None = Form(None),
    ):
        redirect = require_admin(request)
        if redirect:
            return redirect
        release = publish_release(
            settings=settings,
            filename=apk.filename or "",
            fileobj=apk.file,
            version_code=versionCode,
            version_name=versionName,
            release_notes=releaseNotes,
            mandatory=mandatory == "on",
            base_url=build_base_url(request),
        )
        if makeDefault == "on":
            set_default_release(settings, int(release["id"]))
        return RedirectResponse("/admin?message=release-published", status_code=303)

    @app.post("/admin/default-release")
    async def admin_default_release(request: Request, releaseId: int = Form(...)):
        redirect = require_admin(request)
        if redirect:
            return redirect
        set_default_release(settings, releaseId)
        return RedirectResponse("/admin?message=default-release-updated", status_code=303)

    @app.post("/admin/device-rules")
    async def admin_device_rule(
        request: Request,
        nscode: str = Form(...),
        releaseId: int = Form(...),
        note: str = Form(""),
    ):
        redirect = require_admin(request)
        if redirect:
            return redirect
        create_device_rule(settings, nscode=nscode, release_id=releaseId, note=note)
        return RedirectResponse("/admin?message=device-rule-saved", status_code=303)

    @app.post("/admin/device-rules/{rule_id}/delete")
    async def admin_delete_device_rule(request: Request, rule_id: int):
        redirect = require_admin(request)
        if redirect:
            return redirect
        delete_device_rule(settings, rule_id)
        return RedirectResponse("/admin?message=device-rule-deleted", status_code=303)

    @app.get("/api/v1/updates/check")
    def check_update(nscode: str = "", currentVersionCode: int = 0):
        try:
            return JSONResponse(resolve_update(settings, nscode=nscode, current_version_code=currentVersionCode))
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc

    @app.get("/releases/latest/update.json")
    def latest_manifest():
        manifest = get_latest_manifest(settings)
        if manifest is None:
            raise HTTPException(status_code=404, detail="No default release configured")
        return JSONResponse(manifest)

    @app.get("/releases/latest/app.apk")
    def latest_apk():
        manifest = get_latest_manifest(settings)
        if manifest is None:
            raise HTTPException(status_code=404, detail="No default release configured")
        release_id = int(manifest["apkUrl"].rstrip("/").split("/")[-2])
        release = get_release_by_id(settings, release_id)
        if release is None:
            raise HTTPException(status_code=404, detail="Release not found")
        return FileResponse(release["apk_path"], media_type="application/vnd.android.package-archive")

    @app.get("/releases/{release_id}/app.apk")
    def release_apk(release_id: int):
        release = get_release_by_id(settings, release_id)
        if release is None:
            raise HTTPException(status_code=404, detail="Release not found")
        return FileResponse(release["apk_path"], media_type="application/vnd.android.package-archive")

    return app


app = create_app()
```

- [ ] **Step 7: Run API tests**

Run:

```powershell
python -m pytest .\tools\apk_update_server\tests\test_api.py -q
```

Expected: PASS.

- [ ] **Step 8: Run all server tests**

Run:

```powershell
python -m pytest .\tools\apk_update_server\tests -q
```

Expected: PASS.

- [ ] **Step 9: Compile FastAPI modules**

Run:

```powershell
python -m py_compile .\tools\apk_update_server\app\main.py .\tools\apk_update_server\app\auth.py
```

Expected: command exits with code `0`.

- [ ] **Step 10: Commit API and templates**

Run:

```powershell
git add -- tools\apk_update_server\app\auth.py tools\apk_update_server\app\main.py tools\apk_update_server\app\templates tools\apk_update_server\app\static tools\apk_update_server\tests\test_api.py
git commit -m "feat: add deployable apk update server api"
```

Expected: commit succeeds.

## Task 6: Update Server Entrypoints and Documentation

**Files:**
- Modify: `tools/apk_update_server/server.py`
- Modify: `tools/apk_update_server/serve.ps1`
- Modify: `tools/apk_update_server/README.md`

- [ ] **Step 1: Replace `server.py` with FastAPI compatibility wrapper**

Replace `tools/apk_update_server/server.py` with:

```python
#!/usr/bin/env python3
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import uvicorn

ROOT = Path(__file__).resolve().parent


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Serve the deployable APK update server.")
    parser.add_argument("--host", default="0.0.0.0", help="Host to bind. Default: 0.0.0.0")
    parser.add_argument("--port", type=int, default=8080, help="Port to bind. Default: 8080")
    parser.add_argument("--reload", action="store_true", help="Enable uvicorn reload for local development.")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    uvicorn.run(
        "app.main:app",
        host=args.host,
        port=args.port,
        reload=args.reload,
        app_dir=str(ROOT),
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 2: Keep `serve.ps1` entrypoint and add admin password check message**

Replace `tools/apk_update_server/serve.ps1` with:

```powershell
param(
    [int]$Port = 8080,
    [string]$HostName = "0.0.0.0",
    [switch]$Reload
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $Root

if (-not $env:ADMIN_PASSWORD) {
    Write-Error "ADMIN_PASSWORD must be set before starting the APK update server."
}

Write-Host "Serving deployable APK update server from $Root"
Write-Host "Admin URL: http://$HostName`:$Port/admin"
Write-Host "Check API: http://$HostName`:$Port/api/v1/updates/check?nscode=<nscode>&currentVersionCode=<versionCode>"

$argsList = @(".\server.py", "--host", $HostName, "--port", "$Port")
if ($Reload) {
    $argsList += "--reload"
}
python @argsList
```

- [ ] **Step 3: Update README**

Replace `tools/apk_update_server/README.md` with:

```markdown
# APK Update Server

Deployable APK update service for Rokid Glass update testing and controlled rollout.

## Install dependencies

```powershell
python -m pip install -r .\tools\apk_update_server\requirements.txt
```

## Start locally

```powershell
$env:ADMIN_PASSWORD = "change-me"
$env:SESSION_SECRET = "local-dev-secret"
.\tools\apk_update_server\serve.ps1 -Port 8080
```

Open:

```text
http://127.0.0.1:8080/admin
```

## Environment variables

- `ADMIN_PASSWORD`: required admin password.
- `SESSION_SECRET`: session signing secret. Set a fixed value in deployment.
- `SESSION_COOKIE_SECURE`: set to `true` when served behind HTTPS.
- `APK_UPDATE_DATA_DIR`: optional persistent data directory. Defaults to `tools/apk_update_server`.

## Device check API

```text
GET /api/v1/updates/check?nscode=<nscode>&currentVersionCode=<versionCode>
```

`nscode` should come from `RokidSdkManager.getSerialNumber()` on the glasses.

If an update is available, the response is compatible with `AppUpdateInfo`:

```json
{
  "versionCode": 3,
  "versionName": "2.0.6",
  "apkUrl": "http://127.0.0.1:8080/releases/1/app.apk",
  "sha256": "...",
  "sizeBytes": 12345678,
  "releaseNotes": "本次更新说明",
  "mandatory": false
}
```

If no update is available:

```json
{
  "updateAvailable": false
}
```

## Compatibility endpoints

Old static-manifest clients can still use:

```text
http://<server>/releases/latest/update.json
http://<server>/releases/latest/app.apk
```

These endpoints use the configured default release and do not apply `nscode` targeting.

## Admin capabilities

- Upload APK releases.
- Set the default release.
- Add or replace `nscode -> release` rules.
- Delete `nscode` rules.
- View recent update check events.

## Command-line fallback

The legacy manifest generator remains available for local fallback:

```powershell
python .\tools\apk_update_server\generate_manifest.py --help
```

## Deployment notes

First version is intended for a single server instance. Keep SQLite and `releases/` on persistent storage. Put Nginx in front for TLS. If APK download traffic becomes high, serve the `releases/` directory directly from Nginx and keep FastAPI for admin and check APIs.
```

- [ ] **Step 4: Verify help output**

Run:

```powershell
python .\tools\apk_update_server\server.py --help
```

Expected: output includes `--host`, `--port`, and `--reload`.

- [ ] **Step 5: Run all server tests**

Run:

```powershell
python -m pytest .\tools\apk_update_server\tests -q
```

Expected: PASS.

- [ ] **Step 6: Commit entrypoint and docs**

Run:

```powershell
git add -- tools\apk_update_server\server.py tools\apk_update_server\serve.ps1 tools\apk_update_server\README.md
git commit -m "docs: document deployable apk update server"
```

Expected: commit succeeds.

## Task 7: Local Server Smoke Test

**Files:**
- No source edits expected.
- Generated files under `tools/apk_update_server/releases/` and `tools/apk_update_server/apk_update_server.sqlite3` are local artifacts.

- [ ] **Step 1: Start server in background**

Run:

```powershell
$env:ADMIN_PASSWORD = "test-password"
$env:SESSION_SECRET = "test-session-secret"
$server = Start-Process -FilePath python -ArgumentList @(".\tools\apk_update_server\server.py", "--host", "127.0.0.1", "--port", "18085") -PassThru -WindowStyle Hidden
Start-Sleep -Seconds 2
```

Expected: process starts and `$server.HasExited` is `False`.

- [ ] **Step 2: Confirm login page responds**

Run:

```powershell
(Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18085/login).StatusCode
```

Expected: `200`.

- [ ] **Step 3: Confirm unauthenticated admin redirects**

Run:

```powershell
$response = Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18085/admin -MaximumRedirection 0 -ErrorAction SilentlyContinue
$response.StatusCode
$response.Headers.Location
```

Expected: status is `303` and location is `/login`.

- [ ] **Step 4: Confirm no default release returns 404 for compatibility manifest**

Run:

```powershell
try {
    Invoke-WebRequest -UseBasicParsing http://127.0.0.1:18085/releases/latest/update.json
} catch {
    $_.Exception.Response.StatusCode.value__
}
```

Expected: `404`.

- [ ] **Step 5: Stop server**

Run:

```powershell
Stop-Process -Id $server.Id -Force
```

Expected: process stops.

- [ ] **Step 6: Clean local generated artifacts**

Run:

```powershell
Remove-Item -LiteralPath .\tools\apk_update_server\apk_update_server.sqlite3 -Force -ErrorAction SilentlyContinue
Get-ChildItem -LiteralPath .\tools\apk_update_server\releases -Recurse -Filter *.upload -ErrorAction SilentlyContinue | Remove-Item -Force
```

Expected: no command error.

## Task 8: Add Android Dynamic Check Models

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/updater/AppUpdateInfo.kt`

- [ ] **Step 1: Add no-update response model**

Modify `app/src/main/java/com/rokid/glass/updater/AppUpdateInfo.kt` to:

```kotlin
package com.rokid.glass.updater

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val releaseNotes: String,
    val mandatory: Boolean = false,
)

data class AppUpdateServerResponse(
    val updateAvailable: Boolean? = null,
    val versionCode: Int? = null,
    val versionName: String? = null,
    val apkUrl: String? = null,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val releaseNotes: String? = null,
    val mandatory: Boolean = false,
) {
    fun toUpdateInfoOrNull(): AppUpdateInfo? {
        if (updateAvailable == false) return null
        val code = versionCode ?: return null
        val name = versionName ?: return null
        val url = apkUrl ?: return null
        val digest = sha256 ?: return null
        val size = sizeBytes ?: return null
        return AppUpdateInfo(
            versionCode = code,
            versionName = name,
            apkUrl = url,
            sha256 = digest,
            sizeBytes = size,
            releaseNotes = releaseNotes.orEmpty(),
            mandatory = mandatory,
        )
    }
}

data class AppUpdateCheckResult(
    val info: AppUpdateInfo?,
    val currentVersionCode: Int,
) {
    val hasUpdate: Boolean = info != null && info.versionCode > currentVersionCode
}
```

- [ ] **Step 2: Compile Kotlin**

Run:

```powershell
.\gradlew :app:compileStandardDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit model change**

Run:

```powershell
git add -- app\src\main\java\com\rokid\glass\updater\AppUpdateInfo.kt
git commit -m "feat: add dynamic update response model"
```

Expected: commit succeeds.

## Task 9: Add Android Dynamic Update Client

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/updater/AppUpdateClient.kt`

- [ ] **Step 1: Update client implementation**

Replace `app/src/main/java/com/rokid/glass/updater/AppUpdateClient.kt` with:

```kotlin
package com.rokid.glass.updater

import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class AppUpdateClient(
    private val checkUrl: String = DEFAULT_CHECK_URL,
    private val fallbackManifestUrl: String = DEFAULT_MANIFEST_URL,
    private val httpClient: OkHttpClient = defaultHttpClient,
    private val gson: Gson = Gson(),
) {
    @Throws(IOException::class)
    fun checkUpdate(nscode: String, currentVersionCode: Int): AppUpdateInfo? {
        return runCatching {
            fetchDynamic(nscode, currentVersionCode)
        }.getOrElse { error ->
            Log.w(TAG, "Dynamic update check failed, fallback to static manifest: ${error.message}")
            fetchLatest().takeIf { it.versionCode > currentVersionCode }
        }
    }

    @Throws(IOException::class)
    fun fetchLatest(): AppUpdateInfo {
        val request = Request.Builder()
            .url(fallbackManifestUrl)
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Update manifest request failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Update manifest body is empty")
            return gson.fromJson(body, AppUpdateInfo::class.java)
        }
    }

    @Throws(IOException::class)
    private fun fetchDynamic(nscode: String, currentVersionCode: Int): AppUpdateInfo? {
        val url = Uri.parse(checkUrl).buildUpon()
            .appendQueryParameter("nscode", nscode)
            .appendQueryParameter("currentVersionCode", currentVersionCode.toString())
            .build()
            .toString()
        Log.i(TAG, "Checking update dynamic nscodeEmpty=${nscode.isBlank()} currentVersionCode=$currentVersionCode")
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            Log.i(TAG, "Dynamic update check HTTP ${response.code}")
            if (!response.isSuccessful) {
                throw IOException("Dynamic update check failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Dynamic update check body is empty")
            val parsed = gson.fromJson(body, AppUpdateServerResponse::class.java)
            return parsed.toUpdateInfoOrNull()
        }
    }

    companion object {
        private const val TAG = "AppUpdateClient"
        const val DEFAULT_CHECK_URL = "http://192.168.1.152:8080/api/v1/updates/check"
        const val DEFAULT_MANIFEST_URL = "http://192.168.1.152:8080/releases/latest/update.json"

        private val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }
}
```

- [ ] **Step 2: Compile Kotlin**

Run:

```powershell
.\gradlew :app:compileStandardDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit client change**

Run:

```powershell
git add -- app\src\main\java\com\rokid\glass\updater\AppUpdateClient.kt
git commit -m "feat: check apk updates through dynamic endpoint"
```

Expected: commit succeeds.

## Task 10: Pass nscode from Android Update Manager

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/updater/AppUpdateManager.kt`

- [ ] **Step 1: Import RokidSdkManager and use dynamic client**

Modify `app/src/main/java/com/rokid/glass/updater/AppUpdateManager.kt`:

Add import near the other imports:

```kotlin
import android.util.Log
import com.rokid.glass.hiddenrisk.RokidSdkManager
```

Replace this line in `checkForUpdate()`:

```kotlin
val latest = client.fetchLatest()
```

with:

```kotlin
val nscode = RokidSdkManager.getSerialNumber()
Log.i(TAG, "Checking update nscodeEmpty=${nscode.isBlank()} currentVersion=$currentVersion")
val latest = client.checkUpdate(nscode = nscode, currentVersionCode = currentVersion)
    ?: return AppUpdateCheckResult(null, currentVersion)
```

Add `TAG` in the companion object:

```kotlin
private const val TAG = "AppUpdateManager"
```

- [ ] **Step 2: Compile Kotlin**

Run:

```powershell
.\gradlew :app:compileStandardDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit manager change**

Run:

```powershell
git add -- app\src\main\java\com\rokid\glass\updater\AppUpdateManager.kt
git commit -m "feat: send nscode during apk update checks"
```

Expected: commit succeeds.

## Task 11: End-to-End Verification

**Files:**
- No planned source edits.

- [ ] **Step 1: Run Python tests**

Run:

```powershell
python -m pytest .\tools\apk_update_server\tests -q
```

Expected: PASS.

- [ ] **Step 2: Run server compile check**

Run:

```powershell
python -m py_compile `
  .\tools\apk_update_server\server.py `
  .\tools\apk_update_server\app\config.py `
  .\tools\apk_update_server\app\db.py `
  .\tools\apk_update_server\app\auth.py `
  .\tools\apk_update_server\app\schemas.py `
  .\tools\apk_update_server\app\services.py `
  .\tools\apk_update_server\app\main.py
```

Expected: command exits with code `0`.

- [ ] **Step 3: Run Android compile check**

Run:

```powershell
.\gradlew :app:compileStandardDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Build standard debug APK**

Run:

```powershell
.\gradlew :app:assembleStandardDebug
```

Expected: BUILD SUCCESSFUL and `app/build/outputs/apk/standard/debug/app-standard-debug.apk` exists.

- [ ] **Step 5: Manual server publish check**

Run server:

```powershell
$env:ADMIN_PASSWORD = "test-password"
$env:SESSION_SECRET = "test-session-secret"
.\tools\apk_update_server\serve.ps1 -Port 8080
```

In browser:

```text
http://127.0.0.1:8080/admin
```

Expected:

- Login succeeds with `test-password`.
- APK upload succeeds.
- Default release can be set.
- `nscode` rule can be saved.

- [ ] **Step 6: Verify dynamic API manually**

Run:

```powershell
Invoke-RestMethod "http://127.0.0.1:8080/api/v1/updates/check?nscode=RK-TARGET&currentVersionCode=1"
Invoke-RestMethod "http://127.0.0.1:8080/releases/latest/update.json"
```

Expected:

- Dynamic API returns update JSON for a target with a configured newer version.
- Compatibility endpoint returns the default release manifest.

- [ ] **Step 7: Optional real-device smoke test**

Only run if a Rokid device is connected and the update URL constants point at the server reachable from the glasses.

Run:

```powershell
adb devices
.\gradlew :app:installStandardDebug
adb logcat -c
adb shell monkey -p com.rokid.glesse -c android.intent.category.LAUNCHER 1
Start-Sleep -Seconds 10
adb logcat -d | rg "AppUpdateClient|AppUpdateManager|AppUpdatePrompt"
```

Expected:

- Device is listed.
- Install succeeds.
- Logs include dynamic update check HTTP status.
- If the server returns a newer target version, update prompt opens.

## Task 12: Final Git Hygiene

**Files:**
- No source edits expected unless verification reveals a defect.

- [ ] **Step 1: Check generated artifacts are ignored or cleaned**

Run:

```powershell
git status --short
```

Expected intentional tracked changes are already committed. Generated APKs, SQLite database, and `releases/**/*.json` do not appear as tracked additions.

- [ ] **Step 2: Check diff is empty for planned files**

Run:

```powershell
git diff -- tools\apk_update_server app\src\main\java\com\rokid\glass\updater
```

Expected: no output after commits.

- [ ] **Step 3: Report remaining unrelated untracked files**

Run:

```powershell
git status --short
```

Expected: unrelated pre-existing files such as `agent-orchestrator.yaml` are reported but not added.
