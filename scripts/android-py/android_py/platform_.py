"""Platform detection — WSL vs Windows vs native Linux."""

import platform as _platform
import sys
from pathlib import Path


def is_wsl() -> bool:
    return "microsoft" in _platform.release().lower()


def is_windows() -> bool:
    return sys.platform == "win32"


def resolve_adb(android_home: Path, win_android_adb: Path | None = None) -> Path:
    if is_wsl():
        if win_android_adb is None:
            raise ValueError("win_android_adb is required in WSL")
        return win_android_adb
    elif is_windows():
        return android_home / "platform-tools" / "adb.exe"
    else:
        return android_home / "platform-tools" / "adb"


def resolve_gradle() -> list[str]:
    if is_windows():
        return ["gradlew.bat"]
    return ["./gradlew"]
