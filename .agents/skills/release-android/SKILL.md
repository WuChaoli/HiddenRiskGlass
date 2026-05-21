---
name: release-android
description: "Use when bumping the app version, creating a release tag, building an APK for distribution, or pushing a new version to remotes on this HiddenRiskGlass project."
---

# Android 发版

自动化发版全流程：版本号 → changelog → 提交 → tag → 编译 → 推送。

## When to Use

- 发版新版本（如 `2.0.6`）
- 编译正式 APK 到 `release/` 目录
- 需要同时推送到 GitHub + Gitee

**不适用：** 日常 debug 构建（直接用 `./gradlew assembleStandardDebug`）、非 `全省版` 分支发版。

## 流程

### Step 1: 确定版本号

向用户确认新版本号，自动计算 `versionCode`（`2.0.6` → `206`）。

### Step 2: 更新 `app/build.gradle`

修改 `versionCode` 和 `versionName`。

### Step 3: 生成 changelog

`git log` 自上一个 tag 以来的 commit，按 新增/优化/修复/重构 分组，插入 `release/changelog.md` 顶部。

### Step 4: 提交 + tag

```bash
git add app/build.gradle release/changelog.md
git commit -m "chore: bump version to X.X.X"
git tag -a vX.X.X -m "vX.X.X: <摘要>"
```

### Step 5: 编译 + 复制

```bash
./gradlew clean assembleStandardDebug
cp app/build/outputs/apk/standard/debug/app-standard-debug.apk "release/全省版-vX.X.X.apk"
```

### Step 6: 推送

```bash
git push origin 全省版 && git push gitee 全省版
git push origin vX.X.X && git push gitee vX.X.X
```

## 版本号规则

- `versionCode` = 整数，versionName 去点号（`2.0.6` → `206`）
- `versionName` = `"X.Y.Z"`
- tag = `vX.Y.Z`

## Common Mistakes

- `release/` 在 `.gitignore` 中，changelog 需 `git add -f release/changelog.md`
- 忘记推送 tag：`git push origin vX.X.X && git push gitee vX.X.X`
- 编译前未 clean：可能导致增量编译缓存问题
