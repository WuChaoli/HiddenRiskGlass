"""Environment configuration — load .env and validate settings."""

import os
from pathlib import Path

from pydantic_settings import BaseSettings


class EnvConfig(BaseSettings):
    """Configuration loaded from .env file."""

    java_home: Path
    android_home: Path
    android_compile_sdk: str
    android_ndk_version: str
    android_cmake_version: str
    android_build_tools_version: str
    win_android_adb: Path | None = None
    release_keystore_path: Path | None = None
    release_key_alias: str | None = None
    release_key_password: str | None = None
    release_cert_sha256: str | None = None

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"
        extra = "ignore"


def load_env(project_root: Path) -> EnvConfig:
    """Load environment config from project root .env file."""
    old_cwd = os.getcwd()
    try:
        os.chdir(project_root)
        return EnvConfig()
    finally:
        os.chdir(old_cwd)
