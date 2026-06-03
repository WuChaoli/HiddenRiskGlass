from unittest.mock import patch

import pytest

from android_py.cli import build_parser


def test_parser_has_doctor_subcommand():
    parser = build_parser()
    with pytest.raises(SystemExit):
        parser.parse_args(["doctor", "--help"])


def test_parser_has_build_subcommand():
    parser = build_parser()
    with pytest.raises(SystemExit):
        parser.parse_args(["build", "--help"])


def test_parser_global_serial_option():
    parser = build_parser()
    args = parser.parse_args(["--serial", "abc123", "doctor"])
    assert args.serial == "abc123"
