---
name: release-android
description: "Compatibility entry for Android distribution tasks in HiddenRiskGlass. Use the province-release workflow for the 全省版 branch."
---

# Android 发版兼容入口

全省版 Android 发版只有一个流程真相源：读取并遵循同目录下 `../province-release/SKILL.md`。

关键约束：

- 新分发版本必须递增 `app/build.gradle` 中的 `versionCode`，并按用户要求更新 `versionName`。
- 构建、签名和验包统一执行 `bash scripts/android/package-release.sh`；不得再以 debug variant 伪装发布 APK。
- `.env` 未完整提供 release 签名配置时，脚本生成的 debug 签名包只可作为本地/演示产物。
- APK 默认留在被忽略的 `release/` 目录，不进入 Git 历史。
