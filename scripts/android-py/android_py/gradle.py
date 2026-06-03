"""Gradle wrapper with localhost proxy cleanup."""

import os
import subprocess
from pathlib import Path

from android_py.platform_ import resolve_gradle


def run_gradle(args: list[str], project_root: Path, **kwargs) -> subprocess.CompletedProcess:
    gradle_cmd = resolve_gradle()
    # Use absolute path so subprocess can find the wrapper on all platforms
    cmd = [str(project_root / gradle_cmd[0])] + gradle_cmd[1:] + args
    env = os.environ.copy()
    # Remove localhost proxy variables to avoid Java TLS handshake failures
    for key in ["HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy"]:
        if key in env and "127.0.0.1" in env[key]:
            del env[key]
    return subprocess.run(cmd, cwd=project_root, env=env, capture_output=True, text=True, **kwargs)


def run_gradle_cli(args: list[str]) -> int:
    from android_py.env import EnvConfig
    env = EnvConfig()
    project_root = Path(__file__).parent.parent.parent.parent
    result = run_gradle(args, project_root=project_root)
    print(result.stdout, end="")
    print(result.stderr, end="", file=__import__("sys").stderr)
    return result.returncode
