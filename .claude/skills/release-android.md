---
name: release-android
description: "Android 发版工作流：更新版本号、编写 changelog、编译 APK、打 tag、推送到远程。输入新版本号后自动执行全流程。"
---

# Android 发版

自动化发版全流程：版本号更新 → changelog → 提交 → tag → 编译 → 推送。

## 流程

### Step 1: 确定版本号

向用户确认新版本号（如 `2.0.6`），自动计算 `versionCode`（取 minor patch 拼接，如 `2.0.6` → `206`，或手动指定）。

### Step 2: 更新 `app/build.gradle`

修改 `defaultConfig` 中的：
- `versionCode` → 计算值
- `versionName` → 新版本号

### Step 3: 生成 changelog

1. 找到上一个 tag（`git describe --tags --abbrev=0`），列出其间所有 commit
2. 按类别分组：新增、优化、修复、重构
3. 在 `release/changelog.md` 顶部插入新版本条目，格式对齐现有 changelog：
   ```markdown
   ### 2.0.X

   #### 新增
   1. ...

   #### 优化
   1. ...

   #### 修复
   1. ...
   ```

### Step 4: 提交版本变更

```bash
git add app/build.gradle release/changelog.md
git commit -m "chore: bump version to X.X.X"
```

### Step 5: 创建 tag

```bash
git tag -a vX.X.X -m "vX.X.X: <一句话摘要>"
```

### Step 6: 编译 APK

```bash
./gradlew clean assembleStandardDebug
```

### Step 7: 复制 APK 到 `release/`

```bash
cp app/build/outputs/apk/standard/debug/app-standard-debug.apk "release/全省版-vX.X.X.apk"
```

### Step 8: 推送

同时推送到两个远程（`origin` = GitHub, `gitee` = Gitee）：

```bash
git push origin 全省版
git push gitee 全省版
git push origin vX.X.X
git push gitee vX.X.X
```

## 版本号规则

- `versionCode` = 整数，递增。建议取 `versionName` 去掉点号拼接（`2.0.6` → `206`）
- `versionName` = `"X.Y.Z"` 格式的字符串
- tag 命名：`vX.Y.Z`

## 注意事项

- `release/` 目录在 `.gitignore` 中，changelog 需 `git add -f`
- 确认两个远程（origin、gitee）均已配置
- 编译使用 `assembleStandardDebug`，非 release 签名
