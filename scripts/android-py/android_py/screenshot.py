"""Screenshot capture."""

import logging
from pathlib import Path

from android_py.adb import run_adb
from android_py.env import EnvConfig

logger = logging.getLogger(__name__)


def run_screenshot(path: str, serial: str | None = None) -> int:
    env = EnvConfig()
    out_path = Path(path)

    adb_args = ["shell", "screencap", "-p", "/data/local/tmp/screen.png"]
    if serial:
        adb_args = ["-s", serial] + adb_args
    result = run_adb(adb_args, android_home=env.android_home, win_android_adb=env.win_android_adb)
    if result.returncode != 0:
        logger.error(f"Screenshot capture failed: {result.stderr}")
        return 4

    pull_args = ["pull", "/data/local/tmp/screen.png", str(out_path)]
    if serial:
        pull_args = ["-s", serial] + pull_args
    result = run_adb(pull_args, android_home=env.android_home, win_android_adb=env.win_android_adb)
    if result.returncode != 0:
        logger.error(f"Screenshot pull failed: {result.stderr}")
        return 4

    logger.info(f"Screenshot saved: {out_path}")
    return 0
