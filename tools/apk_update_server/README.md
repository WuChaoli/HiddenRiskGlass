# Local APK Update Server

This tool publishes one latest APK for LAN update testing.

## Start browser UI

```powershell
.\tools\apk_update_server\serve.ps1 -Port 8080
```

Open the page on the development machine:

```text
http://127.0.0.1:8080/
```

Use the page to upload an APK and manually fill:

- `versionCode`: must be greater than the installed app versionCode. Android uses this to decide whether an update exists.
- `versionName`: display name shown on the glasses, for example `2.0.6`.
- `releaseNotes`: optional notes shown on the glasses update page.
- `mandatory`: optional forced update flag.

The page writes:

```text
tools/apk_update_server/releases/latest/app.apk
tools/apk_update_server/releases/latest/update.json
```

The glasses should access:

```text
http://<your-lan-ip>:8080/releases/latest/update.json
```

## Command-line fallback

```powershell
python .\tools\apk_update_server\generate_manifest.py `
  --apk .\app\build\outputs\apk\standard\debug\app-standard-debug.apk `
  --version-code 3 `
  --version-name 2.0.6 `
  --base-url http://192.168.x.x:8080 `
  --release-notes "测试局域网 APK 更新"
```

## Generated files

Generated APK and JSON files under `tools/apk_update_server/releases/` are ignored by git.
