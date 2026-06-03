from pathlib import Path
from unittest.mock import MagicMock, patch

from android_py.gradle import run_gradle


def test_run_gradle_removes_localhost_proxy(mock_env_vars, monkeypatch):
    """Gradle wrapper should strip localhost proxy variables."""
    monkeypatch.setenv("HTTP_PROXY", "http://127.0.0.1:7890")
    monkeypatch.setenv("HTTPS_PROXY", "http://127.0.0.1:7890")

    mock_result = MagicMock()
    mock_result.returncode = 0

    with patch("android_py.gradle.subprocess.run", return_value=mock_result) as mock_run:
        with patch("android_py.gradle.resolve_gradle", return_value=["./gradlew"]):
            run_gradle([":app:assembleDebug"], project_root=Path("/project"))
            call_env = mock_run.call_args.kwargs["env"]
            assert "HTTP_PROXY" not in call_env
            assert "HTTPS_PROXY" not in call_env
