"""ADB cross-platform wrapper."""

import subprocess
from pathlib import Path

from android_py.platform_ import resolve_adb


def run_adb(args: list[str], android_home: Path, win_android_adb: Path | None = None, **kwargs) -> subprocess.CompletedProcess:
    adb_path = resolve_adb(android_home, win_android_adb)
    cmd = [str(adb_path)] + args
    return subprocess.run(cmd, capture_output=True, text=True, **kwargs)


def run_adb_cli(args: list[str], serial: str | None = None) -> int:
    from android_py.env import EnvConfig
    env = EnvConfig()
    if serial:
        args = ["-s", serial] + args
    result = run_adb(args, android_home=env.android_home, win_android_adb=env.win_android_adb, check=False)
    print(result.stdout, end="")
    print(result.stderr, end="", file=__import__("sys").stderr)
    return result.returncode
