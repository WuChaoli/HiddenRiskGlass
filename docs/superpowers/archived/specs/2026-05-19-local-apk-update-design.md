# 局域网 APK 热更新设计

## 目标

为当前 Rokid Glass Android 应用增加第一版 APK 更新机制。开发机在局域网内提供最新 APK 和版本清单；安卓端自动检测新版本，提示用户确认，下载 APK，校验完整性，并拉起系统安装器完成安装。

第一版目标是跑通内测闭环，不建设完整发布平台，不做静默安装，不做灰度。

## 范围

包含：

- 本地开发机轻量 HTTP 更新服务器。
- 单版本 `update.json` 清单。
- 安卓端启动自动检查和菜单手动检查。
- 用户确认后下载并安装。
- APK `sha256` 校验。
- 跳过本次更新记录。
- 网络失败、下载失败、校验失败、安装权限缺失的基础处理。

不包含：

- 公网发布、账号鉴权、HTTPS 强制要求。
- 多渠道、多设备型号、多版本历史管理。
- 后台上传管理页面。
- MDM、root、系统签名或厂商接口静默安装。
- 完整 UI 视觉设计。后续 UI 设计可单独讨论。

## 服务器端设计

服务器代码放在 `tools/apk_update_server/`。第一版使用静态文件服务即可。

目录结构：

```text
tools/apk_update_server/
├── README.md
├── generate_manifest.py
├── serve.ps1
└── releases/
    └── latest/
        ├── update.json
        └── app.apk
```

`serve.ps1` 在开发机启动 HTTP 服务，默认监听 `0.0.0.0:8080`。眼镜端通过开发机局域网 IP 访问，例如：

```text
http://192.168.x.x:8080/releases/latest/update.json
```

`generate_manifest.py` 接收 APK 路径、版本号、下载 URL、发布说明等参数，复制 APK 到 `releases/latest/app.apk`，计算 `sha256` 和文件大小，生成 `update.json`。

清单格式：

```json
{
  "versionCode": 2,
  "versionName": "2.0.4",
  "apkUrl": "http://192.168.x.x:8080/releases/latest/app.apk",
  "sha256": "hex-encoded-sha256",
  "sizeBytes": 12345678,
  "releaseNotes": "修复隐患识别问题",
  "mandatory": false
}
```

版本比较只使用 `versionCode`。服务器清单的 `versionCode` 大于本地安装包 `versionCode` 时，认为存在新版本。

## 安卓端设计

新增包：

```text
app/src/main/java/com/rokid/glass/updater/
├── AppUpdateClient.kt
├── AppUpdateInfo.kt
├── AppUpdateManager.kt
└── AppUpdatePromptActivity.kt
```

职责：

- `AppUpdateInfo`：映射 `update.json` 字段。
- `AppUpdateClient`：使用现有 OkHttp + Gson 拉取并解析清单。
- `AppUpdateManager`：负责版本比较、下载 APK、计算 `sha256`、缓存跳过记录、拉起安装器。
- `AppUpdatePromptActivity`：提供眼镜端可用的最小提示界面，展示版本号和发布说明，提供“立即安装”和“跳过本次”。

Manifest 增加：

- `android.permission.REQUEST_INSTALL_PACKAGES`
- `FileProvider`，用于把下载后的 APK 以 `content://` URI 提供给系统安装器。
- `AppUpdatePromptActivity` 注册。

Provider 路径限定到应用缓存目录中的更新 APK，不开放外部任意路径。

## 接入点

启动自动检查：

- 在 `InspectionLoadingActivity` 启动后异步触发一次检查。
- 检查过程不阻塞巡检初始化和页面跳转。
- 没有更新或检查失败时不打断用户。
- 有更新时打开 `AppUpdatePromptActivity`。

菜单手动检查：

- 在 `AiInspectionMenuActivity` 增加手动检查入口。
- 第一版可以先用简单菜单项或语音/隐藏入口，避免过早展开 UI 设计。
- 手动检查失败时需要给出明确提示，例如“检查更新失败”。

跳过本次：

- 用户跳过后记录被跳过的远端 `versionCode`。
- 同一应用版本运行期间不重复弹出该远端版本。
- 后续远端 `versionCode` 变大时重新提示。

## 安装流程

1. 安卓端请求 `update.json`。
2. 比较远端 `versionCode` 和本地 `versionCode`。
3. 若远端版本更高且未被跳过，提示用户。
4. 用户确认后下载 APK 到应用缓存目录。
5. 下载完成后计算 `sha256`。
6. 校验通过后用 `FileProvider` URI 拉起系统安装器。
7. 若缺少未知来源安装权限，跳转到系统授权页，提示用户授权后重新安装。

安装结果不由应用强行判断。下一次启动时，应用通过本地 `versionCode` 是否提升来确认是否已更新。

## 失败处理

- 服务器不可达：自动检查静默失败，手动检查显示失败提示。
- 清单解析失败：记录日志，手动检查显示清单异常。
- 远端版本不高：手动检查显示已是最新版本。
- 下载失败：删除未完成文件，提示可重试。
- `sha256` 不匹配：删除 APK，提示安装包校验失败。
- 安装权限缺失：跳转未知来源安装授权页。
- 系统安装器不可用：提示无法打开安装器，并记录日志。

## 配置

第一版使用本地配置常量保存更新清单 URL，例如：

```text
http://192.168.x.x:8080/releases/latest/update.json
```

该 URL 后续可迁移到 flavor 配置、assets 配置或远程配置。第一版不增加复杂配置系统。

## 验证标准

- 开发机能启动局域网 HTTP 服务。
- 浏览器或 `curl` 能访问 `update.json` 和 APK。
- 眼镜端能访问开发机 IP。
- 当前应用 `versionCode=1`，服务器发布 `versionCode=2` 时能提示更新。
- 用户确认后能下载 APK，并通过 `sha256` 校验。
- 校验通过后能拉起系统安装器。
- 用户跳过后同一远端版本不重复提示。
- 服务器不可达时不影响巡检主链。
- 手动检查在失败、已是最新、新版本可用三种状态下都有明确反馈。

## 后续扩展

后续可在第一版闭环稳定后再讨论：

- 眼镜端更新提示 UI 精细化。
- 多渠道或按设备型号下发。
- 企业内网固定服务器。
- HTTPS 和签名清单。
- 发布历史、回滚和后台上传页面。
- 系统级静默安装能力。
