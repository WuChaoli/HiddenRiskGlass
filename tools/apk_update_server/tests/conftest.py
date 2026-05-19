from __future__ import annotations

import sys
from pathlib import Path

import pytest


@pytest.fixture
def isolated_env(monkeypatch, tmp_path):
    server_root = Path(__file__).resolve().parents[1]
    server_root_text = str(server_root)
    if server_root_text not in sys.path:
        sys.path.insert(0, server_root_text)

    monkeypatch.setenv("APK_UPDATE_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("ADMIN_PASSWORD", "test-password")
    monkeypatch.setenv("SESSION_SECRET", "test-session-secret")
    monkeypatch.delenv("SESSION_COOKIE_SECURE", raising=False)

    return tmp_path
