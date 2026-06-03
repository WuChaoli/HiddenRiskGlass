from pathlib import Path

import pytest

from android_py.env import EnvConfig, load_env


def test_env_config_from_mock_env(mock_env_vars):
    """EnvConfig reads from environment variables."""
    config = EnvConfig()
    assert config.java_home == Path("/usr/lib/jvm/java-21")
    assert config.android_home == Path("/opt/android-sdk")
    assert config.android_compile_sdk == "34"
    assert config.android_ndk_version == "29.0.14206865"
    assert config.win_android_adb == Path("C:\\Users\\test\\adb.exe")


def test_env_config_missing_required(monkeypatch):
    """Missing required fields raise validation error."""
    monkeypatch.delenv("JAVA_HOME", raising=False)
    monkeypatch.delenv("ANDROID_HOME", raising=False)
    with pytest.raises(Exception):
        EnvConfig()


def test_load_env_changes_directory(project_root, mock_env_vars):
    """load_env switches to project root before loading."""
    config = load_env(project_root)
    assert config.android_home == Path("/opt/android-sdk")
