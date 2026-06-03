from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from android_py.doctor import run_doctor


def test_doctor_all_checks_pass(mock_env_vars, monkeypatch):
    monkeypatch.setattr(Path, "exists", lambda self: True)
    monkeypatch.setattr(Path, "is_dir", lambda self: True)
    monkeypatch.setattr(Path, "is_file", lambda self: True)

    with patch("android_py.doctor.subprocess.run") as mock_run:
        mock_run.return_value = MagicMock(returncode=0, stdout="openjdk version 21")
        result = run_doctor(device=False)
        assert result == 0


def test_doctor_missing_java_home(mock_env_vars, monkeypatch):
    monkeypatch.delenv("JAVA_HOME")
    result = run_doctor(device=False)
    assert result == 2
