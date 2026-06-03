"""Install APK to device."""

import logging
from pathlib import Path

from android_py.adb import run_adb
from android_py.env import EnvConfig

logger = logging.getLogger(__name__)

APK_DEBUG = "app/build/outputs/apk/standard/debug/app-standard-debug.apk"


def run_install(debug: bool = False, serial: str | None = None) -> int:
    env = EnvConfig()
    project_root = Path(__file__).parent.parent.parent.parent
    apk_path = project_root / APK_DEBUG

    if not apk_path.exists():
        logger.info("APK not found, triggering build first...")
        from android_py.build import run_build
        if run_build(debug=True) != 0:
            return 3

    adb_args = ["install", "-r", str(apk_path)]
    if serial:
        adb_args = ["-s", serial] + adb_args

    logger.info(f"Installing: {apk_path}")
    result = run_adb(adb_args, android_home=env.android_home, win_android_adb=env.win_android_adb)
    if result.returncode != 0:
        logger.error(f"Install failed:\n{result.stderr}")
        return 4

    logger.info("Install success")
    return 0
