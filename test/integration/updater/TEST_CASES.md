# TEST_CASES: integration/updater

> 本模块集成测试索引。每条用例对应 `evidence/` 下的一个日期文件夹。

## 用例清单

| 编号 | 用例名称 | 目标 | 状态 | 最新证据 |
|-----|---------|------|------|---------|
| INTEG-UPDATER-001 | APK 热更新全链路 | 验证局域网 APK 热更新的服务器、检查、提示、下载安装流程 | ✅ 已通过 | `evidence/2026-05-19_apk_update_device_smoke/` |

## 用例详情

### INTEG-UPDATER-001: APK 热更新全链路

- **触发条件**: 启动 App，触发自动版本检查或手动检查更新
- **预期结果**: 命中新版本 → 提示页 → 下载 → 系统安装器拉起
- **验证方式**: 真机 + 局域网更新服务器 + adb logcat/activity/window 状态确认
- **关联代码**: `AppUpdateChecker`, `AppUpdatePromptActivity`, `AppUpdateDownloadService`
- **回归风险**: 高（涉及文件下载、FileProvider、系统安装器交互）
