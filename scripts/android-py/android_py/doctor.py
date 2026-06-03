"""Environment checker — validates JDK, SDK, NDK, CMake, Gradle, and device."""

import logging
import subprocess
from pathlib import Path

from android_py.env import EnvConfig, load_env
from android_py.platform_ import resolve_adb

logger = logging.getLogger(__name__)


def _check_dir(path: Path, name: str) -> bool:
    if not path.exists():
        logger.error(f"Missing {name}: {path}")
        return False
    if not path.is_dir():
        logger.error(f"Not a directory: {path}")
        return False
    return True


def _check_file(path: Path, name: str) -> bool:
    if not path.exists():
        logger.error(f"Missing {name}: {path}")
        return False
    if not path.is_file():
        logger.error(f"Not a file: {path}")
        return False
    return True


def run_doctor(device: bool = False) -> int:
    try:
        env = EnvConfig()
    except Exception as e:
        logger.error(f"Failed to load .env configuration: {e}")
        return 2

    ok = True

    # Check JAVA_HOME
    if not _check_dir(env.java_home, "JAVA_HOME"):
        ok = False
    elif not _check_file(env.java_home / "bin" / "java", "java executable"):
        ok = False
    else:
        try:
            result = subprocess.run([str(env.java_home / "bin" / "java"), "-version"], capture_output=True, text=True)
            logger.info(f"JAVA_HOME={env.java_home} (java -version returned {result.returncode})")
        except Exception as e:
            logger.error(f"Failed to run java: {e}")
            ok = False

    # Check ANDROID_HOME
    if not _check_dir(env.android_home, "ANDROID_HOME"):
        ok = False
    else:
        logger.info(f"ANDROID_HOME={env.android_home}")

    # Check compile SDK
    compile_sdk = env.android_home / "platforms" / f"android-{env.android_compile_sdk}"
    if not _check_dir(compile_sdk, f"compileSdk API {env.android_compile_sdk}"):
        ok = False

    # Check NDK
    ndk = env.android_home / "ndk" / env.android_ndk_version
    if not _check_dir(ndk, f"NDK {env.android_ndk_version}"):
        ok = False

    # Check CMake
    cmake = env.android_home / "cmake" / env.android_cmake_version
    if not _check_dir(cmake, f"CMake {env.android_cmake_version}"):
        ok = False

    # Check build-tools
    build_tools = env.android_home / "build-tools" / env.android_build_tools_version
    if not _check_dir(build_tools, f"build-tools {env.android_build_tools_version}"):
        ok = False

    # Check gradlew
    project_root = Path(__file__).parent.parent.parent.parent
    gradlew = project_root / "gradlew"
    if not _check_file(gradlew, "gradlew"):
        ok = False

    if device:
        if env.win_android_adb is None:
            logger.error("WIN_ANDROID_ADB is not configured in .env (required for device checks in WSL)")
            ok = False
        elif not _check_file(env.win_android_adb, "WIN_ANDROID_ADB"):
            ok = False
        else:
            logger.info(f"WIN_ANDROID_ADB={env.win_android_adb}")
            try:
                adb_path = resolve_adb(env.android_home, env.win_android_adb)
                result = subprocess.run([str(adb_path), "devices", "-l"], capture_output=True, text=True)
                logger.info("ADB devices:\n" + result.stdout)
            except Exception as e:
                logger.error(f"Failed to list ADB devices: {e}")
                ok = False

    if ok:
        logger.info("Environment OK")
        return 0
    else:
        logger.error("Environment check failed")
        return 2
