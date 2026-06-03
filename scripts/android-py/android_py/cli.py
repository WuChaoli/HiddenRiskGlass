"""CLI argument parser with subcommands."""

import argparse
from pathlib import Path


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="android_py",
        description="Android build harness — cross-platform CLI",
    )
    parser.add_argument("--verbose", "-v", action="store_true", help="Enable debug logging")
    parser.add_argument("--quiet", "-q", action="store_true", help="Suppress non-error output")
    parser.add_argument("--serial", "-s", default=None, help="Device serial number")

    subparsers = parser.add_subparsers(dest="command", required=True)

    # doctor
    doctor_parser = subparsers.add_parser("doctor", help="Check build environment")
    doctor_parser.add_argument("--device", action="store_true", help="Also check device connectivity")

    # build
    build_parser = subparsers.add_parser("build", help="Build APK")
    build_parser.add_argument("--debug", action="store_true", help="Build debug variant")
    build_parser.add_argument("--release", action="store_true", help="Build release variant")

    # install
    install_parser = subparsers.add_parser("install", help="Install APK to device")
    install_parser.add_argument("--debug", action="store_true", help="Install debug APK")

    # gradle
    gradle_parser = subparsers.add_parser("gradle", help="Run arbitrary Gradle task")
    gradle_parser.add_argument("args", nargs=argparse.REMAINDER, help="Gradle arguments")

    # adb
    adb_parser = subparsers.add_parser("adb", help="Run ADB command")
    adb_parser.add_argument("args", nargs=argparse.REMAINDER, help="ADB arguments")

    # logcat
    logcat_parser = subparsers.add_parser("logcat", help="Capture device logs")
    logcat_parser.add_argument("--clear", action="store_true", help="Clear log buffer first")
    logcat_parser.add_argument("--tag", default=None, help="Filter by tag regex")

    # screenshot
    screenshot_parser = subparsers.add_parser("screenshot", help="Capture device screenshot")
    screenshot_parser.add_argument("path", help="Output file path")

    # start
    start_parser = subparsers.add_parser("start", help="Start an Activity")
    start_parser.add_argument("class_name", help="Full Activity class name")
    start_parser.add_argument("extras", nargs="*", help="Intent extras (key=value)")

    # verify-apk
    verify_parser = subparsers.add_parser("verify-apk", help="Verify APK signature and info")
    verify_parser.add_argument("apk", help="Path to APK file")

    # package
    package_parser = subparsers.add_parser("package", help="Build and package release APK")
    package_parser.add_argument("--replace-current", action="store_true", help="Allow replacing current version")

    # get-qrcode
    qrcode_parser = subparsers.add_parser("get-qrcode", help="Retrieve QR code")
    qrcode_parser.add_argument("--account", default=None, help="Account name (or set HZFJ_ACCOUNT_NAME env)")
    qrcode_parser.add_argument("--code", default="21A", help="Organization code (default: 21A)")

    return parser


def main(args: list[str] | None = None) -> int:
    parser = build_parser()
    parsed = parser.parse_args(args)

    import logging
    if parsed.verbose:
        logging.basicConfig(level=logging.DEBUG)
    elif parsed.quiet:
        logging.basicConfig(level=logging.WARNING)
    else:
        logging.basicConfig(level=logging.INFO)

    # Dispatch to command handlers
    if parsed.command == "doctor":
        from android_py.doctor import run_doctor
        return run_doctor(device=parsed.device)
    elif parsed.command == "build":
        from android_py.build import run_build
        return run_build(debug=parsed.debug, release=parsed.release)
    elif parsed.command == "install":
        from android_py.install import run_install
        return run_install(debug=parsed.debug, serial=parsed.serial)
    elif parsed.command == "gradle":
        from android_py.gradle import run_gradle_cli
        return run_gradle_cli(args=parsed.args or [])
    elif parsed.command == "adb":
        from android_py.adb import run_adb_cli
        return run_adb_cli(args=parsed.args or [], serial=parsed.serial)
    elif parsed.command == "logcat":
        from android_py.logcat import run_logcat
        return run_logcat(clear=parsed.clear, tag=parsed.tag, serial=parsed.serial)
    elif parsed.command == "screenshot":
        from android_py.screenshot import run_screenshot
        return run_screenshot(path=parsed.path, serial=parsed.serial)
    elif parsed.command == "start":
        from android_py.start import run_start
        return run_start(class_name=parsed.class_name, extras=parsed.extras or [], serial=parsed.serial)
    elif parsed.command == "verify-apk":
        from android_py.apk import run_verify_apk
        return run_verify_apk(apk_path=Path(parsed.apk))
    elif parsed.command == "package":
        from android_py.package_ import run_package
        return run_package(replace_current=parsed.replace_current)
    elif parsed.command == "get-qrcode":
        from android_py.qrcode import run_get_qrcode
        account = parsed.account or __import__("os").environ.get("HZFJ_ACCOUNT_NAME", "")
        code = parsed.code or __import__("os").environ.get("HZFJ_CODE", "21A")
        return run_get_qrcode(account=account, code=code)

    return 1
