from __future__ import annotations

import json
import os
import secrets
from dataclasses import dataclass
from pathlib import Path

# 自动加载 .env 文件（如果存在）
try:
    from dotenv import load_dotenv
    _dotenv_path = Path(__file__).resolve().parents[1] / ".env"
    if _dotenv_path.is_file():
        load_dotenv(dotenv_path=str(_dotenv_path), override=False)
except ImportError:
    pass  # python-dotenv 未安装时静默跳过


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
    admin_username: str
    admin_password: str
    session_secret: str
    session_cookie_secure: bool
    server_name: str
    verification_code_length: int
    verification_code_expires_minutes: int
    verification_code_send_cooldown_seconds: int
    password_min_length: int
    chunk_size_bytes: int


def load_settings(require_admin_password: bool = True) -> Settings:
    data_dir = Path(os.environ.get("APK_UPDATE_DATA_DIR", APP_ROOT)).resolve()
    admin_username = os.environ.get("ADMIN_USERNAME", "admin")
    admin_password = os.environ.get("ADMIN_PASSWORD", "")
    if require_admin_password and not admin_password:
        raise RuntimeError("ADMIN_PASSWORD must be set before starting the APK update server")

    session_secret = os.environ.get("SESSION_SECRET") or secrets.token_urlsafe(32)
    cookie_secure = os.environ.get("SESSION_COOKIE_SECURE", "").lower() in {"1", "true", "yes"}

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
        admin_username=admin_username,
        admin_password=admin_password,
        session_secret=session_secret,
        session_cookie_secure=cookie_secure,
        server_name=server_name,
        verification_code_length=verification_code_length,
        verification_code_expires_minutes=verification_code_expires_minutes,
        verification_code_send_cooldown_seconds=verification_code_send_cooldown_seconds,
        password_min_length=password_min_length,
        chunk_size_bytes=chunk_size_bytes,
    )
