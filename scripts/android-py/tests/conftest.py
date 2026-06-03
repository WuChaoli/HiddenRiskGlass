from pathlib import Path

import pytest


@pytest.fixture
def project_root() -> Path:
    """Return the project root directory."""
    # tests/ is at scripts/android-py/tests/
    return Path(__file__).parent.parent.parent.parent


@pytest.fixture
def mock_env_vars(monkeypatch):
    """Set standard mock env vars for testing."""
    env = {
        "JAVA_HOME": "/usr/lib/jvm/java-21",
        "ANDROID_HOME": "/opt/android-sdk",
        "ANDROID_COMPILE_SDK": "34",
        "ANDROID_NDK_VERSION": "29.0.14206865",
        "ANDROID_CMAKE_VERSION": "3.22.1",
        "ANDROID_BUILD_TOOLS_VERSION": "34.0.0",
        "WIN_ANDROID_ADB": "C:\\Users\\test\\adb.exe",
    }
    for key, value in env.items():
        monkeypatch.setenv(key, value)
    return env
