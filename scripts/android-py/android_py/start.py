"""Activity starter via ADB."""

import logging

from android_py.adb import run_adb
from android_py.env import EnvConfig

logger = logging.getLogger(__name__)


def run_start(class_name: str, extras: list[str], serial: str | None = None) -> int:
    env = EnvConfig()
    adb_args = ["shell", "am", "start", "-n", f"com.rokid.glesse/{class_name}"]

    for extra in extras:
        if "=" in extra:
            key, value = extra.split("=", 1)
            adb_args.extend(["--es", key, value])

    if serial:
        adb_args = ["-s", serial] + adb_args

    result = run_adb(adb_args, android_home=env.android_home, win_android_adb=env.win_android_adb)
    if result.returncode != 0:
        logger.error(f"Start activity failed: {result.stderr}")
        return 4

    logger.info(f"Started: {class_name}")
    return 0
