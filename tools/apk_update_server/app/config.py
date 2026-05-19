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
