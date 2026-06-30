# 局域网 APK 热更新实施计划

> **给 agentic workers 的要求：** 实施本计划时必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，按任务逐项执行。步骤使用 checkbox（`- [ ]`）追踪状态。

**目标：** 实现第一版局域网 APK 更新闭环：本地静态更新服务器、安卓端版本检查、用户确认、APK 下载、哈希校验和系统安装器拉起。

**架构：** 本地服务器作为仓库工具放在 `tools/apk_update_server/`，负责发布 `update.json` 和 `app.apk`。安卓端新增独立 `com.rokid.glass.updater` 包，并在 `InspectionLoadingActivity` 接入启动自动检查，在 `AiInspectionMenuActivity` 接入手动检查。安装流程走 Android 系统安装器，通过 `FileProvider` 暴露应用缓存目录里的 APK。

**技术栈：** Python 3、PowerShell、Android Kotlin、OkHttp、Gson、Android `FileProvider`、Gradle `:app:assembleStandardDebug`。

---

## 文件结构

- 新增 `tools/apk_update_server/README.md`：说明本地服务器使用方式和发布流程。
- 新增 `tools/apk_update_server/generate_manifest.py`：复制 APK、计算 SHA-256、生成 `update.json`。
- 新增 `tools/apk_update_server/serve.ps1`：在 `0.0.0.0:8080` 启动 Python 静态 HTTP 服务。
- 新增 `tools/apk_update_server/releases/latest/.gitkeep`：保留发布目录，但不追踪大 APK。
- 修改 `.gitignore`：忽略 `tools/apk_update_server/releases/` 下生成的 APK 和 JSON。
- 新增 `app/src/main/java/com/rokid/glass/updater/AppUpdateInfo.kt`：更新清单数据模型。
- 新增 `app/src/main/java/com/rokid/glass/updater/AppUpdateClient.kt`：使用 OkHttp + Gson 拉取和解析清单。
- 新增 `app/src/main/java/com/rokid/glass/updater/AppUpdateManager.kt`：版本比较、跳过缓存、下载、SHA-256 校验、拉起安装器。
- 新增 `app/src/main/java/com/rokid/glass/updater/AppUpdatePromptActivity.kt`：眼镜端最小更新提示页和安装状态。
- 新增 `app/src/main/res/layout/activity_app_update_prompt.xml`：更新提示页布局。
- 新增 `app/src/main/res/xml/app_update_file_paths.xml`：`FileProvider` 缓存路径。
- 修改 `app/src/main/AndroidManifest.xml`：增加安装权限、provider 和提示页 activity。
- 修改 `app/src/main/res/values/strings.xml`：增加更新相关文案。
- 修改 `app/src/main/res/layout/activity_ai_inspection_menu.xml`：增加第四个“检查更新”菜单卡片。
- 修改 `app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt`：接入第四个菜单项、语音触发和手动检查。
- 修改 `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt`：加载页初始化后触发非阻塞自动检查。

## 任务 1：本地更新服务器工具

**文件：**
- 新增：`tools/apk_update_server/README.md`
- 新增：`tools/apk_update_server/generate_manifest.py`
- 新增：`tools/apk_update_server/serve.ps1`
- 新增：`tools/apk_update_server/releases/latest/.gitkeep`
- 修改：`.gitignore`

- [ ] **步骤 1：增加生成物忽略规则**

在 `.gitignore` 追加：

```gitignore
# Local APK update server generated artifacts
tools/apk_update_server/releases/**/*.apk
tools/apk_update_server/releases/**/*.json
```

- [ ] **步骤 2：创建发布目录占位文件**

创建空文件：

```text
tools/apk_update_server/releases/latest/.gitkeep
```

- [ ] **步骤 3：创建清单生成脚本**

创建 `tools/apk_update_server/generate_manifest.py`：

```python
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
```

- [ ] **步骤 4：创建 PowerShell 服务脚本**

创建 `tools/apk_update_server/serve.ps1`：

```powershell
param(
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $Root

Write-Host "Serving APK update files from $Root"
Write-Host "URL: http://0.0.0.0:$Port/"
python -m http.server $Port --bind 0.0.0.0
```

- [ ] **步骤 5：创建使用说明**

创建 `tools/apk_update_server/README.md`：

````markdown
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
````

- [ ] **步骤 6：验证清单生成脚本帮助信息**

运行：

```powershell
python .\tools\apk_update_server\generate_manifest.py --help
```

预期：命令输出参数说明，包含 `--apk`、`--version-code`、`--version-name`、`--base-url`。

- [ ] **步骤 7：提交服务器工具**

运行：

```powershell
git add .gitignore tools/apk_update_server
git commit -m "feat: add local apk update server tooling"
```

## 任务 2：安卓更新核心能力

**文件：**
- 新增：`app/src/main/java/com/rokid/glass/updater/AppUpdateInfo.kt`
- 新增：`app/src/main/java/com/rokid/glass/updater/AppUpdateClient.kt`
- 新增：`app/src/main/java/com/rokid/glass/updater/AppUpdateManager.kt`

- [ ] **步骤 1：创建更新清单模型**

创建 `app/src/main/java/com/rokid/glass/updater/AppUpdateInfo.kt`：

```kotlin
package com.rokid.glass.updater

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val releaseNotes: String,
    val mandatory: Boolean = false,
)

data class AppUpdateCheckResult(
    val info: AppUpdateInfo?,
    val currentVersionCode: Int,
) {
    val hasUpdate: Boolean = info != null && info.versionCode > currentVersionCode
}
```

- [ ] **步骤 2：创建清单请求客户端**

创建 `app/src/main/java/com/rokid/glass/updater/AppUpdateClient.kt`：

```kotlin
package com.rokid.glass.updater

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class AppUpdateClient(
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
    private val httpClient: OkHttpClient = defaultHttpClient,
    private val gson: Gson = Gson(),
) {
    @Throws(IOException::class)
    fun fetchLatest(): AppUpdateInfo {
        val request = Request.Builder()
            .url(manifestUrl)
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Update manifest request failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Update manifest body is empty")
            return gson.fromJson(body, AppUpdateInfo::class.java)
        }
    }

    companion object {
        const val DEFAULT_MANIFEST_URL = "http://192.168.x.x:8080/releases/latest/update.json"

        private val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }
}
```

- [ ] **步骤 3：创建更新管理器**

创建 `app/src/main/java/com/rokid/glass/updater/AppUpdateManager.kt`：

```kotlin
package com.rokid.glass.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

class AppUpdateManager(
    private val context: Context,
    private val client: AppUpdateClient = AppUpdateClient(),
    private val httpClient: OkHttpClient = defaultHttpClient,
) {
    fun checkForUpdate(ignoreSkipped: Boolean = false): AppUpdateCheckResult {
        val currentVersion = getCurrentVersionCode()
        val latest = client.fetchLatest()
        val effectiveInfo = if (
            latest.versionCode > currentVersion &&
            (ignoreSkipped || latest.mandatory || !isVersionSkipped(latest.versionCode))
        ) {
            latest
        } else {
            null
        }
        return AppUpdateCheckResult(effectiveInfo, currentVersion)
    }

    fun skipVersion(versionCode: Int) {
        prefs.edit().putInt(KEY_SKIPPED_VERSION_CODE, versionCode).apply()
    }

    fun downloadAndInstall(info: AppUpdateInfo): File {
        val apkFile = downloadApk(info)
        val actualSha = sha256(apkFile)
        if (!actualSha.equals(info.sha256, ignoreCase = true)) {
            apkFile.delete()
            throw IOException("APK sha256 mismatch expected=${info.sha256} actual=$actualSha")
        }
        launchInstaller(apkFile)
        return apkFile
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    private fun downloadApk(info: AppUpdateInfo): File {
        val request = Request.Builder().url(info.apkUrl).get().build()
        val targetDir = File(context.cacheDir, UPDATE_CACHE_DIR).apply { mkdirs() }
        val targetFile = File(targetDir, UPDATE_APK_NAME)
        val tempFile = File(targetDir, "$UPDATE_APK_NAME.download")
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("APK download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("APK download body is empty")
            tempFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output)
                }
            }
        }
        if (info.sizeBytes > 0 && tempFile.length() != info.sizeBytes) {
            tempFile.delete()
            throw IOException("APK size mismatch expected=${info.sizeBytes} actual=${tempFile.length()}")
        }
        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            throw IOException("Failed to move APK into update cache")
        }
        return targetFile
    }

    private fun launchInstaller(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.appupdate.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val resolved = intent.resolveActivity(context.packageManager)
        if (resolved == null) {
            throw IOException("No package installer can handle APK install intent")
        }
        context.startActivity(intent)
    }

    private fun getCurrentVersionCode(): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    private fun isVersionSkipped(versionCode: Int): Boolean {
        return prefs.getInt(KEY_SKIPPED_VERSION_CODE, -1) == versionCode
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            String.format(Locale.US, "%02x", byte)
        }
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val PREFS_NAME = "app_update"
        private const val KEY_SKIPPED_VERSION_CODE = "skipped_version_code"
        private const val UPDATE_CACHE_DIR = "app_updates"
        private const val UPDATE_APK_NAME = "latest.apk"

        private val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }
}
```

- [ ] **步骤 4：编译核心文件**

运行：

```powershell
.\gradlew :app:compileStandardDebugKotlin
```

预期：Kotlin 编译通过。

- [ ] **步骤 5：提交更新核心能力**

运行：

```powershell
git add app/src/main/java/com/rokid/glass/updater
git commit -m "feat: add apk update core"
```

## 任务 3：Manifest、Provider 和提示页 UI

**文件：**
- 新增：`app/src/main/java/com/rokid/glass/updater/AppUpdatePromptActivity.kt`
- 新增：`app/src/main/res/layout/activity_app_update_prompt.xml`
- 新增：`app/src/main/res/xml/app_update_file_paths.xml`
- 修改：`app/src/main/AndroidManifest.xml`
- 修改：`app/src/main/res/values/strings.xml`

- [ ] **步骤 1：增加更新提示文案**

在 `app/src/main/res/values/strings.xml` 的 `</resources>` 前增加：

```xml
    <string name="app_update_title">发现新版本</string>
    <string name="app_update_version">新版本：%1$s</string>
    <string name="app_update_notes_empty">本次更新未填写说明</string>
    <string name="app_update_install_now">立即安装</string>
    <string name="app_update_skip">跳过本次</string>
    <string name="app_update_downloading">正在下载安装包...</string>
    <string name="app_update_download_failed">下载安装包失败，请重试</string>
    <string name="app_update_verify_failed">安装包校验失败，请重新下载</string>
    <string name="app_update_permission_required">请允许安装未知应用后重试</string>
    <string name="app_update_installer_failed">无法打开系统安装器</string>
    <string name="app_update_touch_hint">单击安装，双击跳过</string>
```

- [ ] **步骤 2：创建 provider 路径配置**

创建 `app/src/main/res/xml/app_update_file_paths.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths xmlns:android="http://schemas.android.com/apk/res/android">
    <cache-path
        name="app_updates"
        path="app_updates/" />
</paths>
```

- [ ] **步骤 3：创建更新提示页布局**

创建 `app/src/main/res/layout/activity_app_update_prompt.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/black"
    android:paddingHorizontal="24dp"
    android:paddingVertical="32dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:gravity="center"
        android:orientation="vertical">

        <TextView
            android:id="@+id/tvUpdateTitle"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="@string/app_update_title"
            android:textColor="@color/green"
            android:textSize="24sp"
            android:textStyle="bold" />

        <TextView
            android:id="@+id/tvUpdateVersion"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:textColor="@color/white"
            android:textSize="16sp" />

        <TextView
            android:id="@+id/tvUpdateNotes"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:gravity="center"
            android:maxLines="4"
            android:textColor="@color/white"
            android:textSize="14sp" />

        <TextView
            android:id="@+id/tvUpdateStatus"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="20dp"
            android:text="@string/app_update_touch_hint"
            android:textColor="@color/green"
            android:textSize="14sp" />
    </LinearLayout>
</FrameLayout>
```

- [ ] **步骤 4：创建更新提示 Activity**

创建 `app/src/main/java/com/rokid/glass/updater/AppUpdatePromptActivity.kt`：

```kotlin
package com.rokid.glass.updater

import android.os.Bundle
import android.widget.TextView
import com.google.gson.Gson
import com.rokid.glass.hiddenrisk.BaseGlassActivity
import com.rokid.glass.input.UnifiedInputSession
import com.rokid.glesse.R
import java.io.IOException
import java.util.concurrent.Executors

class AppUpdatePromptActivity : BaseGlassActivity() {
    private lateinit var tvUpdateVersion: TextView
    private lateinit var tvUpdateNotes: TextView
    private lateinit var tvUpdateStatus: TextView

    private val inputSession by lazy { UnifiedInputSession(this, TAG) }
    private val worker = Executors.newSingleThreadExecutor()
    private val updateManager by lazy { AppUpdateManager(applicationContext) }
    private lateinit var updateInfo: AppUpdateInfo
    private var installing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_update_prompt)
        tvUpdateVersion = findViewById(R.id.tvUpdateVersion)
        tvUpdateNotes = findViewById(R.id.tvUpdateNotes)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)

        val json = intent.getStringExtra(EXTRA_UPDATE_INFO)
        if (json.isNullOrBlank()) {
            finish()
            return
        }
        updateInfo = Gson().fromJson(json, AppUpdateInfo::class.java)
        tvUpdateVersion.text = getString(R.string.app_update_version, updateInfo.versionName)
        tvUpdateNotes.text = updateInfo.releaseNotes.ifBlank { getString(R.string.app_update_notes_empty) }
        refreshInputActions()
    }

    override fun onResume() {
        super.onResume()
        inputSession.attach()
        refreshInputActions()
    }

    override fun onPause() {
        inputSession.detach()
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        inputSession.release()
        super.onDestroy()
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        return inputSession.dispatchTouch(keyEvent) || super.onGlassKeyEvent(keyEvent)
    }

    private fun refreshInputActions() {
        inputSession.updateActions(
            listOf(
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Confirm,
                    label = getString(R.string.app_update_install_now),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.app_update_install_now), "li ji an zhuang"),
                    ),
                    enabled = { !installing },
                ) {
                    installUpdate()
                },
                UnifiedInputSession.InputActionSpec(
                    id = UnifiedInputSession.InputActionId.Return,
                    label = getString(R.string.app_update_skip),
                    triggers = listOf(
                        UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK),
                        UnifiedInputSession.InputTrigger.Voice(getString(R.string.app_update_skip), "tiao guo ben ci"),
                    ),
                    enabled = { !installing },
                ) {
                    updateManager.skipVersion(updateInfo.versionCode)
                    finish()
                },
            ),
        )
    }

    private fun installUpdate() {
        if (installing) return
        installing = true
        tvUpdateStatus.setText(R.string.app_update_downloading)
        refreshInputActions()
        worker.execute {
            try {
                if (!updateManager.canRequestPackageInstalls()) {
                    runOnUiThread {
                        installing = false
                        tvUpdateStatus.setText(R.string.app_update_permission_required)
                        updateManager.openInstallPermissionSettings()
                        refreshInputActions()
                    }
                    return@execute
                }
                updateManager.downloadAndInstall(updateInfo)
            } catch (error: IOException) {
                runOnUiThread {
                    installing = false
                    tvUpdateStatus.text = when {
                        error.message?.contains("sha256", ignoreCase = true) == true ->
                            getString(R.string.app_update_verify_failed)
                        error.message?.contains("installer", ignoreCase = true) == true ->
                            getString(R.string.app_update_installer_failed)
                        else -> getString(R.string.app_update_download_failed)
                    }
                    refreshInputActions()
                }
            }
        }
    }

    companion object {
        const val EXTRA_UPDATE_INFO = "update_info"
        private const val TAG = "AppUpdatePrompt"
    }
}
```

- [ ] **步骤 5：注册 Manifest 条目**

在 `app/src/main/AndroidManifest.xml` 的权限区增加：

```xml
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

在 `<application>` 内增加：

```xml
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.appupdate.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/app_update_file_paths" />
        </provider>

        <activity
            android:name="com.rokid.glass.updater.AppUpdatePromptActivity"
            android:exported="false"
            android:theme="@style/Theme.Glessedemo" />
```

- [ ] **步骤 6：编译提示页**

运行：

```powershell
.\gradlew :app:compileStandardDebugKotlin
```

预期：更新提示页和 provider 引用编译通过。

- [ ] **步骤 7：提交提示页**

运行：

```powershell
git add app/src/main/AndroidManifest.xml app/src/main/java/com/rokid/glass/updater app/src/main/res/layout/activity_app_update_prompt.xml app/src/main/res/xml/app_update_file_paths.xml app/src/main/res/values/strings.xml
git commit -m "feat: add apk update prompt"
```

## 任务 4：AI 菜单手动检查入口

**文件：**
- 修改：`app/src/main/res/layout/activity_ai_inspection_menu.xml`
- 修改：`app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt`
- 修改：`app/src/main/res/values/strings.xml`

- [ ] **步骤 1：增加菜单文案**

在 `strings.xml` 增加：

```xml
    <string name="ai_entry_menu_update">检查更新</string>
    <string name="ai_entry_menu_update_checking">正在检查更新...</string>
    <string name="ai_entry_menu_update_latest">当前已是最新版本</string>
    <string name="ai_entry_menu_update_failed">检查更新失败</string>
```

更新 `ai_entry_menu_voice_hint`：

```xml
    <string name="ai_entry_menu_voice_hint">语音指令：实时分析 | 设备指引 | 隐患拍照 | 检查更新</string>
```

- [ ] **步骤 2：增加第四个菜单卡片**

在 `activity_ai_inspection_menu.xml` 的横向卡片 `LinearLayout` 内增加：

```xml
            <FrameLayout
                android:id="@+id/itemUpdateCheck"
                android:layout_width="0dp"
                android:layout_height="140dp"
                android:layout_marginHorizontal="4dp"
                android:layout_weight="1"
                android:background="@drawable/glass_menu_card">

                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"
                    android:gravity="center"
                    android:orientation="vertical">

                    <TextView
                        android:layout_width="52dp"
                        android:layout_height="52dp"
                        android:gravity="center"
                        android:text="↻"
                        android:textColor="@color/green"
                        android:textSize="34sp" />

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="12dp"
                        android:text="@string/ai_entry_menu_update"
                        android:textColor="@color/green"
                        android:textSize="16sp" />
                </LinearLayout>
            </FrameLayout>
```

- [ ] **步骤 3：接入 Kotlin 字段和菜单列表**

在 `AiInspectionMenuActivity.kt` 增加 imports：

```kotlin
import com.google.gson.Gson
import com.rokid.glass.updater.AppUpdateManager
import com.rokid.glass.updater.AppUpdatePromptActivity
import java.io.IOException
import java.util.concurrent.Executors
```

增加字段：

```kotlin
    private lateinit var itemUpdateCheck: FrameLayout
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private val updateManager by lazy { AppUpdateManager(applicationContext) }
    private var checkingUpdate = false
```

初始化 view：

```kotlin
        itemUpdateCheck = findViewById(R.id.itemUpdateCheck)
```

更新菜单项列表：

```kotlin
        items = listOf(itemHazardAnalysis, itemDeviceGuide, itemHazardRecord, itemUpdateCheck)
```

- [ ] **步骤 4：增加手动检查动作**

在 `buildInputActions()` 增加：

```kotlin
            UnifiedInputSession.InputActionSpec(
                id = UnifiedInputSession.InputActionId("ai_menu_update"),
                label = "检查更新",
                triggers = listOf(UnifiedInputSession.InputTrigger.Voice("检查更新", "jian cha geng xin")),
                enabled = { !checkingUpdate },
            ) {
                checkUpdateManually()
            },
```

更新 `onItemConfirmed`：

```kotlin
            3 -> checkUpdateManually()
```

- [ ] **步骤 5：实现手动检查逻辑**

增加方法：

```kotlin
    private fun checkUpdateManually() {
        if (checkingUpdate) return
        checkingUpdate = true
        tvBottomHint.setText(R.string.ai_entry_menu_update_checking)
        inputSession.updateActions(buildInputActions())
        updateExecutor.execute {
            try {
                val result = updateManager.checkForUpdate(ignoreSkipped = true)
                runOnUiThread {
                    checkingUpdate = false
                    inputSession.updateActions(buildInputActions())
                    if (result.hasUpdate && result.info != null) {
                        startActivity(
                            Intent(this, AppUpdatePromptActivity::class.java).apply {
                                putExtra(AppUpdatePromptActivity.EXTRA_UPDATE_INFO, Gson().toJson(result.info))
                            },
                        )
                    } else {
                        tvBottomHint.setText(R.string.ai_entry_menu_update_latest)
                    }
                }
            } catch (error: IOException) {
                runOnUiThread {
                    checkingUpdate = false
                    tvBottomHint.setText(R.string.ai_entry_menu_update_failed)
                    inputSession.updateActions(buildInputActions())
                }
            }
        }
    }
```

在 `onDestroy()` 的 `super.onDestroy()` 前增加：

```kotlin
        updateExecutor.shutdownNow()
```

- [ ] **步骤 6：编译菜单接入**

运行：

```powershell
.\gradlew :app:compileStandardDebugKotlin
```

预期：Kotlin 编译通过。

- [ ] **步骤 7：提交菜单接入**

运行：

```powershell
git add app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt app/src/main/res/layout/activity_ai_inspection_menu.xml app/src/main/res/values/strings.xml
git commit -m "feat: add manual apk update check"
```

## 任务 5：加载页自动检查

**文件：**
- 修改：`app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt`

- [ ] **步骤 1：增加 imports**

增加：

```kotlin
import com.google.gson.Gson
import com.rokid.glass.updater.AppUpdateManager
import com.rokid.glass.updater.AppUpdatePromptActivity
import java.io.IOException
```

- [ ] **步骤 2：增加更新检查字段**

在现有 executor 字段附近增加：

```kotlin
    private val updateCheckExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val updateManager by lazy { AppUpdateManager(applicationContext) }
    private var autoUpdateCheckStarted = false
```

- [ ] **步骤 3：在视图初始化后触发自动检查**

在 `onCreate` 的 `initViews()` 后增加：

```kotlin
        startAutoUpdateCheck()
```

不要放到 Wi-Fi 路由的 early return 之前；如果应用需要先进入 Wi-Fi 配网页，不让更新检查抢占该流程。

- [ ] **步骤 4：实现自动检查逻辑**

增加方法：

```kotlin
    private fun startAutoUpdateCheck() {
        if (autoUpdateCheckStarted || debugSnapshotMode) return
        autoUpdateCheckStarted = true
        updateCheckExecutor.execute {
            try {
                val result = updateManager.checkForUpdate(ignoreSkipped = false)
                if (!result.hasUpdate || result.info == null) return@execute
                uiHandler.post {
                    if (activityDestroyed) return@post
                    startActivity(
                        Intent(this, AppUpdatePromptActivity::class.java).apply {
                            putExtra(AppUpdatePromptActivity.EXTRA_UPDATE_INFO, Gson().toJson(result.info))
                        },
                    )
                }
            } catch (error: IOException) {
                Log.i(TAG, "auto update check skipped: ${error.message}")
            }
        }
    }
```

- [ ] **步骤 5：释放更新检查线程**

在 `onDestroy()` 的 `modelLoadExecutor.shutdownNow()` 后增加：

```kotlin
        updateCheckExecutor.shutdownNow()
```

- [ ] **步骤 6：编译加载页接入**

运行：

```powershell
.\gradlew :app:compileStandardDebugKotlin
```

预期：Kotlin 编译通过。

- [ ] **步骤 7：提交自动检查接入**

运行：

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt
git commit -m "feat: check apk update on startup"
```

## 任务 6：构建与本地服务器验证

**文件：**
- 使用生成的构建产物：`app/build/outputs/apk/standard/debug/`
- 使用生成的更新产物：`tools/apk_update_server/releases/latest/`

- [ ] **步骤 1：构建 debug APK**

运行：

```powershell
.\gradlew :app:assembleStandardDebug
```

预期：输出 `BUILD SUCCESSFUL`，并生成 `app\build\outputs\apk\standard\debug\app-standard-debug.apk`。

- [ ] **步骤 2：生成测试版本清单**

把 `192.168.x.x` 替换成眼镜能访问到的 Windows 局域网 IP：

```powershell
python .\tools\apk_update_server\generate_manifest.py `
  --apk .\app\build\outputs\apk\standard\debug\app-standard-debug.apk `
  --version-code 2 `
  --version-name 2.0.4 `
  --base-url http://192.168.x.x:8080 `
  --release-notes "测试局域网 APK 更新"
```

预期：生成 `tools\apk_update_server\releases\latest\update.json`，且包含 `versionCode: 2`。

- [ ] **步骤 3：启动服务器**

在独立 PowerShell 窗口运行：

```powershell
.\tools\apk_update_server\serve.ps1 -Port 8080
```

预期：终端输出 `Serving APK update files`，并保持运行。

- [ ] **步骤 4：从开发机验证清单访问**

运行：

```powershell
Invoke-RestMethod http://127.0.0.1:8080/releases/latest/update.json
```

预期：PowerShell 输出对象，包含 `versionCode`、`versionName`、`apkUrl`、`sha256`、`sizeBytes`、`releaseNotes`、`mandatory`。

- [ ] **步骤 5：确认生成文件已被忽略**

运行：

```powershell
git status --short tools/apk_update_server
```

预期：生成的 `app.apk` 和 `update.json` 不出现在未跟踪文件中。

## 任务 7：真机冒烟验证

**文件：**
- 不预期修改源码。

- [ ] **步骤 1：确认 adb 设备**

运行：

```powershell
adb devices
```

预期：存在一个状态为 `device` 的设备。

- [ ] **步骤 2：安装当前 debug APK**

运行：

```powershell
adb install -r .\app\build\outputs\apk\standard\debug\app-standard-debug.apk
```

预期：输出 `Success`。

- [ ] **步骤 3：启动应用**

运行：

```powershell
adb shell monkey -p com.rokid.glesse 1
```

预期：眼镜端应用启动。

- [ ] **步骤 4：确认前台 Activity**

运行：

```powershell
adb shell dumpsys activity activities | rg "mResumedActivity|topResumedActivity"
```

预期：输出包含 `com.rokid.glesse`。

- [ ] **步骤 5：采集更新日志**

运行：

```powershell
adb logcat -c
adb shell monkey -p com.rokid.glesse 1
Start-Sleep -Seconds 8
adb logcat -d | rg "AppUpdate|AppUpdatePrompt|auto update|Update manifest"
```

预期：服务器 URL 可访问且远端 `versionCode` 更高时，日志显示更新检查行为，并且眼镜端出现提示页。如果 URL 仍是 `192.168.x.x`，日志应显示自动检查失败或跳过，但应用不能崩溃。

- [ ] **步骤 6：手动检查**

进入 `AiInspectionMenuActivity`，选择 `检查更新` 并确认。

预期：
- 服务器可访问且远端 `versionCode` 更高：打开 `AppUpdatePromptActivity`。
- 服务器不可访问：底部提示显示 `检查更新失败`。
- 服务器可访问但远端 `versionCode` 不高：底部提示显示 `当前已是最新版本`。

- [ ] **步骤 7：最终工作区检查**

运行：

```powershell
git status --short
```

预期：只剩与本任务无关的既有文件。生成的 APK 和 update JSON 被忽略。

## 自检

- 规格覆盖：任务 1 和任务 6 覆盖服务器工具；任务 2 和任务 3 覆盖安卓端核心更新流程；任务 4 和任务 5 覆盖菜单入口和启动入口；任务 6 和任务 7 覆盖构建、本地服务和真机验证。
- 范围控制：计划不包含 HTTPS、鉴权、多渠道、灰度、上传后台或静默安装。
- 类型一致性：`AppUpdateInfo`、`AppUpdateCheckResult`、`AppUpdateClient`、`AppUpdateManager`、`AppUpdatePromptActivity.EXTRA_UPDATE_INFO` 都先定义再使用。
- 风险说明：设备验证前必须把 `AppUpdateClient.DEFAULT_MANIFEST_URL` 替换成真实局域网 IP。第一版使用常量配置，这是已确认设计的一部分。
