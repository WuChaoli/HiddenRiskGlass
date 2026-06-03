"""APK metadata extraction using Android build tools."""

import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

from android_py.env import EnvConfig


@dataclass
class ApkInfo:
    package_name: str
    version_code: str
    version_name: str
    certificate_sha256: str | None = None


def _build_tool_path(env: EnvConfig, tool_name: str) -> Path:
    return env.android_home / "build-tools" / env.android_build_tools_version / tool_name


def apk_badging_line(apk_path: Path, env: EnvConfig) -> str:
    aapt = _build_tool_path(env, "aapt")
    result = subprocess.run(
        [str(aapt), "dump", "badging", str(apk_path)],
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout.splitlines()[0]


def apk_info(apk_path: Path, env: EnvConfig) -> ApkInfo:
    badging = apk_badging_line(apk_path, env)
    package_match = re.search(r"package: name='([^']+)'", badging)
    version_code_match = re.search(r"versionCode='([^']+)'", badging)
    version_name_match = re.search(r"versionName='([^']+)'", badging)

    return ApkInfo(
        package_name=package_match.group(1) if package_match else "",
        version_code=version_code_match.group(1) if version_code_match else "",
        version_name=version_name_match.group(1) if version_name_match else "",
    )


def apk_certificate_sha256(apk_path: Path, env: EnvConfig) -> str | None:
    apksigner = _build_tool_path(env, "apksigner")
    result = subprocess.run(
        [str(apksigner), "verify", "--print-certs", str(apk_path)],
        capture_output=True,
        text=True,
        check=True,
    )
    for line in result.stdout.splitlines():
        if line.startswith("Signer #1 certificate SHA-256 digest: "):
            return line.replace("Signer #1 certificate SHA-256 digest: ", "").strip()
    return None
