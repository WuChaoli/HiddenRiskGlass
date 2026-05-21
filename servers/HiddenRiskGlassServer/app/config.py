from __future__ import annotations

import json
import os
import secrets
from dataclasses import dataclass
from pathlib import Path


APP_ROOT = Path(__file__).resolve().parents[1]
PROJECT_ROOT = APP_ROOT.parent.parent


def _load_json_config(app_root: Path) -> dict:
    config_path = app_root / "config.json"
    if config_path.is_file():
        return json.loads(config_path.read_text(encoding="utf-8"))
    return {}


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
    # JSON 配置字段（新增）
    server_name: str
    verification_code_length: int
    verification_code_expires_minutes: int
    verification_code_send_cooldown_seconds: int
    password_min_length: int
    chunk_size_bytes: int


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

    json_config = _load_json_config(APP_ROOT)
    auth_config = json_config.get("auth", {})
    upload_config = json_config.get("upload", {})

    def _env_int(name: str, default: int) -> int:
        val = os.environ.get(name)
        if val is not None and val != "":
            return int(val)
        return default

    server_name = os.environ.get("SERVER_NAME") or json_config.get("server_name", "HiddenRiskGlassServer")
    verification_code_length = _env_int("VERIFICATION_CODE_LENGTH", auth_config.get("verification_code_length", 6))
    verification_code_expires_minutes = _env_int("VERIFICATION_CODE_EXPIRES_MINUTES", auth_config.get("verification_code_expires_minutes", 15))
    verification_code_send_cooldown_seconds = _env_int("VERIFICATION_CODE_SEND_COOLDOWN_SECONDS", auth_config.get("verification_code_send_cooldown_seconds", 60))
    password_min_length = _env_int("PASSWORD_MIN_LENGTH", auth_config.get("password_min_length", 8))
    chunk_size_bytes = _env_int("CHUNK_SIZE_BYTES", upload_config.get("chunk_size_bytes", 1048576))

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
        server_name=server_name,
        verification_code_length=verification_code_length,
        verification_code_expires_minutes=verification_code_expires_minutes,
        verification_code_send_cooldown_seconds=verification_code_send_cooldown_seconds,
        password_min_length=password_min_length,
        chunk_size_bytes=chunk_size_bytes,
    )
