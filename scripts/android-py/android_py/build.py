"""Build APK commands."""

import logging
from pathlib import Path

from android_py.env import EnvConfig
from android_py.gradle import run_gradle

logger = logging.getLogger(__name__)

APK_DEBUG = "app/build/outputs/apk/standard/debug/app-standard-debug.apk"
APK_RELEASE = "app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk"


def run_build(debug: bool = False, release: bool = False) -> int:
    env = EnvConfig()
    project_root = Path(__file__).parent.parent.parent.parent

    if debug:
        task = ":app:assembleStandardDebug"
        apk_path = project_root / APK_DEBUG
    elif release:
        task = ":app:assembleStandardRelease"
        apk_path = project_root / APK_RELEASE
    else:
        logger.error("Specify --debug or --release")
        return 1

    logger.info(f"Building: {task}")
    result = run_gradle([task], project_root=project_root)
    if result.returncode != 0:
        logger.error(f"Build failed:\n{result.stderr}")
        return 3

    if not apk_path.exists():
        logger.error(f"Expected APK not found: {apk_path}")
        return 3

    logger.info(f"Build success: {apk_path}")
    return 0
