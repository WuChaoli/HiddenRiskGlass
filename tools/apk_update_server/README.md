# Local APK Update Server

This tool publishes one latest APK for LAN update testing.

## Generate update files

```powershell
python .\tools\apk_update_server\generate_manifest.py `
  --apk .\app\build\outputs\apk\standard\debug\app-standard-debug.apk `
  --version-code 2 `
  --version-name 2.0.4 `
  --base-url http://192.168.x.x:8080 `
  --release-notes "测试局域网 APK 更新"
```

## Start server

```powershell
.\tools\apk_update_server\serve.ps1 -Port 8080
```

The glasses should access:

```text
http://192.168.x.x:8080/releases/latest/update.json
```
