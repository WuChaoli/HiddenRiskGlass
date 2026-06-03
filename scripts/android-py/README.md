# Android Build Scripts (Python)

Python-based cross-platform CLI harness for Android build, install, and debug operations.
Replaces `scripts/android/` shell scripts with unified Python commands that work on both WSL and Windows.

## Setup

```bash
cd scripts/android-py
pip install -r requirements.txt
```

Ensure `.env` is configured in project root (same as shell scripts).

## Usage

```bash
# Environment check
python -m android_py doctor --device

# Build debug APK
python -m android_py build --debug

# Install to device
python -m android_py install --debug -s <serial>

# Logcat with filter
python -m android_py logcat -s <serial> --tag "HiddenRisk|AiInspection"

# Screenshot
python -m android_py screenshot -s <serial> shots/debug.png

# Verify APK
python -m android_py verify-apk app/build/outputs/apk/standard/debug/app-standard-debug.apk

# Package release
python -m android_py package
```

## Testing

```bash
pytest tests/ -v
```
