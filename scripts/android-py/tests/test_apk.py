from dataclasses import dataclass
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from android_py.apk import ApkInfo, apk_badging_line, apk_info, apk_certificate_sha256


@dataclass
class MockEnv:
    android_home: Path = Path("/opt/android-sdk")
    android_build_tools_version: str = "34.0.0"


def test_apk_badging_line(mock_env_vars):
    mock_result = MagicMock()
    mock_result.stdout = "package: name='com.rokid.glesse' versionCode='11' versionName='2.0.9'\n"
    mock_result.returncode = 0

    with patch("android_py.apk.subprocess.run", return_value=mock_result) as mock_run:
        env = MockEnv()
        line = apk_badging_line(Path("test.apk"), env)
        assert "com.rokid.glesse" in line
        mock_run.assert_called_once()


def test_apk_info_parsing(mock_env_vars):
    badging = "package: name='com.rokid.glesse' versionCode='11' versionName='2.0.9'"
    env = MockEnv()

    with patch("android_py.apk.apk_badging_line", return_value=badging):
        info = apk_info(Path("test.apk"), env)
        assert info.package_name == "com.rokid.glesse"
        assert info.version_code == "11"
        assert info.version_name == "2.0.9"


def test_apk_certificate_sha256(mock_env_vars):
    mock_result = MagicMock()
    mock_result.stdout = "Signer #1 certificate SHA-256 digest: ab:cd:ef:12\n"
    mock_result.returncode = 0

    with patch("android_py.apk.subprocess.run", return_value=mock_result) as mock_run:
        env = MockEnv()
        digest = apk_certificate_sha256(Path("test.apk"), env)
        assert digest == "ab:cd:ef:12"
