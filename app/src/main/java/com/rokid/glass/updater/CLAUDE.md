# updater/ — 应用版本更新

## 业务概述
负责 App 版本检查、APK 下载、升级提示。通过 OkHttp 访问远端版本接口，支持动态版本查询和 manifest 兜底两种策略，检测到新版本后弹出更新提示页面，提供更新/跳过本次/取消三种操作。

## 文件索引

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `AppUpdateManager.kt` | 更新流程编排：版本检查、下载、安装触发、skip 控制 | `checkForUpdate()`、`skipCurrentSession()` |
| `AppUpdateClient.kt` | 版本接口 HTTP 客户端，支持动态查询 + manifest 兜底 | `checkUpdate(nscode, currentVersionCode)` |
| `AppUpdateInfo.kt` | 更新信息数据类 + 服务端响应解析 | `AppUpdateInfo`、`AppUpdateServerResponse.toUpdateInfoOrNull()` |
| `AppUpdatePromptActivity.kt` | 更新提示弹窗页面，提供更新/跳过/取消操作及下载进度展示 | `AppUpdatePromptActivity`、`moveUpdatePromptSelection()` |

## 核心调用链

```
AppUpdateManager.checkForUpdate()
  -> AppUpdateClient.checkUpdate(nscode, versionCode)
    -> 动态接口查询（带 nscode 时）
    -> manifest 兜底（无 nscode 或动态查询失败时）
  -> 比较 versionCode
  -> AppUpdatePromptActivity 弹出升级提示
    -> 更新：下载 APK -> SHA256 校验 -> FileProvider 安装
    -> 跳过本次：持久化跳过当前版本
    -> 取消：关闭弹窗
```

## 依赖关系

- **依赖：** `network/`（OkHttp 客户端）、`utils/`（工具函数）
- **被依赖：** `hiddenrisk/`（启动时触发版本检查）
