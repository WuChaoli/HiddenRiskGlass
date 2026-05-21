# HiddenRiskGlassServer

This tool runs HiddenRiskGlassServer, a small FastAPI server for LAN or intranet APK update testing. It supports admin login, APK upload, a default release, per-`nscode` release rules, update check logging, and compatibility endpoints used by the Android client.

## Install Dependencies

Use Python 3.11+ from the repository root:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r .\servers\HiddenRiskGlassServer\requirements.txt
```

## Start Locally

Set an admin password before starting the server:

```powershell
$env:ADMIN_PASSWORD = "change-me"
$env:SESSION_SECRET = "replace-with-a-long-random-secret"
.\servers\HiddenRiskGlassServer\serve.ps1 -HostName 127.0.0.1 -Port 8080
```

For development reload:

```powershell
.\servers\HiddenRiskGlassServer\serve.ps1 -HostName 127.0.0.1 -Port 8080 -Reload
```

The Python entrypoint also works from either the repository root or `servers/HiddenRiskGlassServer`:

```powershell
python .\servers\HiddenRiskGlassServer\server.py --host 127.0.0.1 --port 8080
cd .\servers\HiddenRiskGlassServer
python .\server.py --host 127.0.0.1 --port 8080 --reload
```

Open:

```text
http://127.0.0.1:8080/login
```

## Environment Variables

- `ADMIN_PASSWORD`: required. Password for the admin UI.
- `SESSION_SECRET`: recommended. Secret used to sign the admin session cookie. If omitted, a random secret is generated on startup, which invalidates sessions after restart.
- `SESSION_COOKIE_SECURE`: optional. Set to `1`, `true`, or `yes` when serving only over HTTPS.
- `APK_UPDATE_DATA_DIR`: optional. Directory for `apk_update_server.sqlite3` and uploaded release files. Defaults to `servers/HiddenRiskGlassServer`.

## Update Check API

Android clients should call:

```text
GET /api/v1/updates/check?nscode=<nscode>&currentVersionCode=<versionCode>
```

Example:

```powershell
Invoke-RestMethod "http://127.0.0.1:8080/api/v1/updates/check?nscode=NSCODE-001&currentVersionCode=2"
```

When an update is available, the response contains:

```json
{
  "updateAvailable": true,
  "versionCode": 3,
  "versionName": "2.0.6",
  "apkUrl": "http://127.0.0.1:8080/releases/1/app.apk",
  "sha256": "...",
  "sizeBytes": 123,
  "releaseNotes": "notes",
  "mandatory": false
}
```

When the current version is already new enough:

```json
{
  "updateAvailable": false
}
```

`currentVersionCode` must be a positive integer. `nscode` can be empty, but per-device rollout rules only match non-empty values.

## Compatibility Endpoints

These endpoints keep older local-update flows working:

- `GET /releases/latest/update.json`: returns the current default release manifest, or `404` when no default release exists.
- `GET /releases/latest/app.apk`: downloads the current default release APK, or `404` when no default release exists.
- `GET /releases/{release_id}/app.apk`: downloads a specific uploaded release APK.

## Admin Capabilities

The admin UI is available at `/admin` after login. It can:

- Upload APK releases with `versionCode`, `versionName`, release notes, and mandatory-update flag.
- Mark an uploaded release as the default release.
- Create `nscode` rules that route a specific device code to a specific release.
- Delete `nscode` rules.
- View recent update-check events.

Unauthenticated admin requests redirect to `/login`.

## JSON Configuration

You can customize technical parameters via `servers/HiddenRiskGlassServer/config.json`:

```json
{
  "server_name": "HiddenRiskGlassServer",
  "auth": {
    "verification_code_length": 6,
    "verification_code_expires_minutes": 15,
    "verification_code_send_cooldown_seconds": 60,
    "password_min_length": 8
  },
  "upload": {
    "chunk_size_bytes": 1048576
  }
}
```

Environment variables take precedence over JSON values. For example, `SERVER_NAME` overrides `server_name`.

## Deployment Notes

- Always set a strong `ADMIN_PASSWORD` and stable `SESSION_SECRET`.
- Set `APK_UPDATE_DATA_DIR` to a persistent directory outside the source tree in production.
- Put the service behind HTTPS or a trusted intranet reverse proxy when exposed beyond local LAN testing.
- Use `SESSION_COOKIE_SECURE=1` when HTTPS is enabled.
- Back up `apk_update_server.sqlite3` and the `releases/` directory together; the database stores metadata and file paths.
- Do not commit generated `apk_update_server.sqlite3`, uploaded `.apk` files, `.upload` temp files, or `__pycache__`.
