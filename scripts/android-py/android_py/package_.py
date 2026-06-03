"""Release packaging — build, sign, verify."""

import logging
import subprocess
from pathlib import Path

from android_py.apk import apk_info, apk_certificate_sha256
from android_py.build import run_build
from android_py.env import EnvConfig

logger = logging.getLogger(__name__)


def run_package(replace_current: bool = False) -> int:
    env = EnvConfig()

    # Check release config
    if not all([env.release_keystore_path, env.release_key_alias, env.release_key_password]):
        logger.warning("Release signing config incomplete, falling back to debug-signed package")
        return _package_debug_signed(env)

    return _package_release(env, replace_current)


def _package_release(env: EnvConfig, replace_current: bool) -> int:
    result = run_build(release=True)
    if result != 0:
        return result

    project_root = Path(__file__).parent.parent.parent.parent
    unsigned_apk = project_root / "app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk"
    release_dir = project_root / "release"
    release_dir.mkdir(exist_ok=True)

    info = apk_info(unsigned_apk, env)
    out_apk = release_dir / f"全省版-v{info.version_name}.apk"

    # Sign
    apksigner = env.android_home / "build-tools" / env.android_build_tools_version / "apksigner"
    sign_cmd = [
        str(apksigner), "sign",
        "--ks", str(env.release_keystore_path),
        "--ks-key-alias", env.release_key_alias,
        "--ks-pass", f"pass:{env.release_key_password}",
        "--out", str(out_apk),
        str(unsigned_apk),
    ]
    result = subprocess.run(sign_cmd, capture_output=True, text=True)
    if result.returncode != 0:
        logger.error(f"Signing failed: {result.stderr}")
        return 5

    # Verify
    cert = apk_certificate_sha256(out_apk, env)
    if env.release_cert_sha256 and cert != env.release_cert_sha256:
        logger.error(f"Certificate mismatch! Expected {env.release_cert_sha256}, got {cert}")
        return 5

    logger.info(f"Package success: {out_apk}")
    return 0


def _package_debug_signed(env: EnvConfig) -> int:
    result = run_build(release=True)
    if result != 0:
        return result

    project_root = Path(__file__).parent.parent.parent.parent
    unsigned_apk = project_root / "app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk"
    local_dir = project_root / "release" / "local"
    local_dir.mkdir(parents=True, exist_ok=True)

    info = apk_info(unsigned_apk, env)
    out_apk = local_dir / f"全省版-v{info.version_name}-debug-signed.apk"

    # Debug sign
    apksigner = env.android_home / "build-tools" / env.android_build_tools_version / "apksigner"
    sign_cmd = [
        str(apksigner), "sign",
        "--ks", str(project_root / "debug.keystore"),
        "--ks-pass", "pass:android",
        "--key-pass", "pass:android",
        "--out", str(out_apk),
        str(unsigned_apk),
    ]
    result = subprocess.run(sign_cmd, capture_output=True, text=True)
    if result.returncode != 0:
        logger.error(f"Debug signing failed: {result.stderr}")
        return 5

    logger.info(f"Debug-signed package: {out_apk}")
    return 0
