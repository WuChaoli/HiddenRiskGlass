#!/usr/bin/env python3
import argparse
import hashlib
import json
import shutil
from pathlib import Path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate local APK update manifest.")
    parser.add_argument("--apk", required=True, help="Path to the APK to publish.")
    parser.add_argument("--version-code", required=True, type=int, help="Published APK versionCode.")
    parser.add_argument("--version-name", required=True, help="Published APK versionName.")
    parser.add_argument("--base-url", required=True, help="Server base URL, for example http://192.168.1.10:8080.")
    parser.add_argument("--release-notes", default="", help="Release notes shown on the glasses.")
    parser.add_argument("--mandatory", action="store_true", help="Mark the update as mandatory in the manifest.")
    args = parser.parse_args()

    source_apk = Path(args.apk).expanduser().resolve()
    if not source_apk.is_file():
        raise FileNotFoundError(f"APK not found: {source_apk}")

    root = Path(__file__).resolve().parent
    latest_dir = root / "releases" / "latest"
    latest_dir.mkdir(parents=True, exist_ok=True)

    target_apk = latest_dir / "app.apk"
    shutil.copy2(source_apk, target_apk)

    base_url = args.base_url.rstrip("/")
    manifest = {
        "versionCode": args.version_code,
        "versionName": args.version_name,
        "apkUrl": f"{base_url}/releases/latest/app.apk",
        "sha256": sha256_file(target_apk),
        "sizeBytes": target_apk.stat().st_size,
        "releaseNotes": args.release_notes,
        "mandatory": bool(args.mandatory),
    }

    manifest_path = latest_dir / "update.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Wrote {manifest_path}")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
