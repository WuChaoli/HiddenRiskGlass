from unittest.mock import patch

from android_py.cli import main


def test_cli_doctor_with_missing_env():
    with patch.dict("os.environ", {}, clear=True):
        result = main(["doctor"])
        assert result == 2


def test_cli_build_without_flag():
    with patch("android_py.build.run_build", return_value=1):
        result = main(["build"])
        assert result == 1  # Requires --debug or --release
