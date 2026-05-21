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
    # SMTP 配置（新增）
    smtp_host: str
    smtp_port: int
    smtp_user: str
    smtp_pass: str
    smtp_from: str
    smtp_tls: bool


def load_settings(require_admin_password: bool = True) -> Settings:
    data_dir = Path(os.environ.get("APK_UPDATE_DATA_DIR", APP_ROOT)).resolve()
    admin_password = os.environ.get("ADMIN_PASSWORD", "")
    if require_admin_password and not admin_password:
        raise RuntimeError("ADMIN_PASSWORD must be set before starting the APK update server")

    session_secret = os.environ.get("SESSION_SECRET") or secrets.token_urlsafe(32)
    cookie_secure = os.environ.get("SESSION_COOKIE_SECURE", "").lower() in {"1", "true", "yes"}
    # SMTP 配置（新增）
    smtp_host = os.environ.get("SMTP_HOST", "")
    smtp_port = int(os.environ.get("SMTP_PORT", "587"))
    smtp_user = os.environ.get("SMTP_USER", "")
    smtp_pass = os.environ.get("SMTP_PASS", "")
    smtp_from = os.environ.get("SMTP_FROM", "")
    smtp_tls = os.environ.get("SMTP_TLS", "true").lower() not in {"0", "false", "no"}
    releases_dir = data_dir / "releases"
    return Settings(
        app_root=APP_ROOT,
        data_dir=data_dir,
        releases_dir=releases_dir,
        database_path=data_dir / "apk_update_server.sqlite3",
        admin_password=admin_password,
        session_secret=session_secret,
        session_cookie_secure=cookie_secure,
        # SMTP 配置（新增）
        smtp_host=smtp_host,
        smtp_port=smtp_port,
        smtp_user=smtp_user,
        smtp_pass=smtp_pass,
        smtp_from=smtp_from,
        smtp_tls=smtp_tls,
    )
