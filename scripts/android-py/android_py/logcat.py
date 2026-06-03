"""Logcat wrapper."""

import logging
import subprocess

from android_py.adb import run_adb
from android_py.env import EnvConfig

logger = logging.getLogger(__name__)


def run_logcat(clear: bool = False, tag: str | None = None, serial: str | None = None) -> int:
    env = EnvConfig()

    if clear:
        clear_args = ["logcat", "-c"]
        if serial:
            clear_args = ["-s", serial] + clear_args
        run_adb(clear_args, android_home=env.android_home, win_android_adb=env.win_android_adb)
        logger.info("Log buffer cleared")

    logcat_args = ["logcat"]
    if tag:
        logcat_args.extend(["-v", "tag", tag + ":D", "*:S"])
    if serial:
        logcat_args = ["-s", serial] + logcat_args

    logger.info("Starting logcat... (Ctrl+C to stop)")
    result = run_adb(logcat_args, android_home=env.android_home, win_android_adb=env.win_android_adb, check=False)
    return result.returncode
