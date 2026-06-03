from pathlib import Path
from unittest.mock import MagicMock, patch

from android_py.adb import run_adb


def test_run_adb_subprocess(mock_env_vars):
    mock_result = MagicMock()
    mock_result.returncode = 0
    mock_result.stdout = "List of devices attached\ndevice1\tdevice\n"

    with patch("android_py.adb.subprocess.run", return_value=mock_result) as mock_run:
        with patch("android_py.adb.resolve_adb", return_value=Path("C:/adb.exe")):
            result = run_adb(["devices"], android_home=Path("/sdk"), win_android_adb=Path("C:/adb.exe"))
            assert result.returncode == 0
            mock_run.assert_called_once()
