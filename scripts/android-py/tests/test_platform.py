from pathlib import Path
from unittest.mock import patch

import pytest

import android_py.platform_ as platform


def test_is_wsl_on_linux_kernel():
    with patch("android_py.platform_._platform.release", return_value="5.15.0-microsoft-standard-WSL2"):
        assert platform.is_wsl() is True


def test_is_wsl_on_native_linux():
    with patch("android_py.platform_._platform.release", return_value="5.15.0-generic"):
        assert platform.is_wsl() is False


def test_is_windows():
    with patch("android_py.platform_.sys.platform", "win32"):
        assert platform.is_windows() is True


def test_resolve_adb_in_wsl():
    with patch("android_py.platform_.is_wsl", return_value=True):
        result = platform.resolve_adb(
            android_home=Path("/opt/android-sdk"),
            win_android_adb=Path("C:/Users/test/adb.exe"),
        )
        assert result == Path("C:/Users/test/adb.exe")


def test_resolve_adb_in_windows():
    with patch("android_py.platform_.is_wsl", return_value=False):
        with patch("android_py.platform_.is_windows", return_value=True):
            result = platform.resolve_adb(
                android_home=Path("C:/Android/Sdk"),
                win_android_adb=None,
            )
            assert result == Path("C:/Android/Sdk/platform-tools/adb.exe")


def test_resolve_adb_wsl_requires_win_adb():
    with patch("android_py.platform_.is_wsl", return_value=True):
        with pytest.raises(ValueError, match="win_android_adb is required in WSL"):
            platform.resolve_adb(
                android_home=Path("/opt/android-sdk"),
                win_android_adb=None,
            )


def test_resolve_gradle_on_windows():
    with patch("android_py.platform_.is_windows", return_value=True):
        assert platform.resolve_gradle() == ["gradlew.bat"]


def test_resolve_gradle_on_non_windows():
    with patch("android_py.platform_.is_windows", return_value=False):
        assert platform.resolve_gradle() == ["./gradlew"]
