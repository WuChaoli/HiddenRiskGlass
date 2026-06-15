---
name: android-release
description: Android 项目标准发布流程：版本号升级、commit、tag、双远程推送、changelog 更新、APK 打包。触发条件：用户说"发布"、"release"、"发版"、"打版本"时使用。
---

# Android Release

Android 项目标准化发布工作流，覆盖从版本号升级到 APK 打包的完整闭环。

## 触发条件

用户说"发布"、"release"、"发版"、"打版本"、"打包发布"等时触发。

## 流程总览

```
确认版本号 → 修改 build.gradle → git commit → git tag → git push → 更新 changelog → 打包 APK
```

## 前置检查

执行发布前先确认：

```bash
# 检查工作区是否干净
git status --porcelain

# 检查当前分支
git branch --show-current

# 检查远程配置
git remote -v
```

- 工作区必须干净（无未提交变更）
- 远程必须包含 `origin`（gitee），`github` 可选

## Step 1: 确认版本号

读取当前 `app/build.gradle` 中的 `versionName` 与 `versionCode`。`versionName` 用于确认目标发布版本；`versionCode` 只依据当前整数值递增。

**推导规则**：
- `X.Y.Z` → `X.Y.Z+1`（如 `2.0.6` → `2.0.6.1`）
- `X.Y.Z.N` → `X.Y.Z.N+1`（如 `2.0.6.1` → `2.0.6.2`）

```bash
# 读取当前版本
grep -E 'versionCode|versionName' app/build.gradle | head -2
```

向用户确认目标版本号后再继续。

**强制规则**：
- `versionCode = 当前 versionCode + 1`
- `versionCode` 与 `versionName` 的格式相互独立
- 禁止通过移除 `versionName` 中的点号或拼接版本段来计算 `versionCode`
- 例：当前为 `versionCode 7` / `versionName "2.0.6.1"`，发布 `2.0.6.2` 时必须写为 `versionCode 8`，而不是 `2062`

## Step 2: 修改版本号

修改 `app/build.gradle`：

```groovy
versionCode <当前值+1>
versionName "<目标版本号>"
```

无论目标 `versionName` 的段数或内容如何变化，`versionCode` 均只在当前数值基础上自增 1。

## Step 3: Git Commit

```bash
git add app/build.gradle
git commit -m "chore: bump version to <目标版本号>

Co-Authored-By: Codex Opus 4.7 <noreply@anthropic.com>"
```

如果还有其他待提交的变更（如功能代码），将其一并纳入 commit，使用 `feat:` 前缀。

## Step 4: Git Tag

```bash
git tag -a <目标版本号> -m "<目标版本号>: <简短描述>"
```

简短描述从 commit message 中提取主要变更点，控制在 20 字以内。

## Step 5: 推送

### 主推送（gitee/origin）

```bash
git push origin HEAD --tags
```

### 辅助推送（GitHub）

```bash
# 检查 github 远程是否存在
git remote get-url github 2>/dev/null

# 存在则推送
git push github HEAD --tags 2>&1
```

**GitHub 推送失败处理**：
- 如果被拒（如历史大文件超过 100MB），提示用户原因，**不阻塞流程**
- 如果远程不存在，跳过

## Step 6: 更新 Changelog

在 `release/changelog.md` 顶部插入新版本条目：

```markdown
### <目标版本号>

#### 新增/变更/修复
1. **<变更标题>**: <简要描述>
```

提示用户确认或补充 changelog 内容，然后强制添加并提交（`release/` 目录在 `.gitignore` 中）：

```bash
git add -f release/changelog.md
git commit -m "docs: update changelog for <目标版本号>"
git push origin HEAD
```

## Step 7: 打包 APK

使用项目自带构建脚本：

```bash
bash scripts/android/build-debug.sh
```

构建成功后复制 APK 到 `release/`：

```bash
mkdir -p release
cp app/build/outputs/apk/standard/debug/app-standard-debug.apk "release/全省版-v<目标版本号>-debug.apk"
```

## 完成报告

发布完成后输出总结表格：

| 步骤 | 状态 |
|------|------|
| 版本号 | <旧> → <新> (versionCode: <旧> → <新>) |
| Commit | <commit hash> |
| Tag | <tag name> |
| Gitee 推送 | 成功 / 失败 |
| GitHub 推送 | 成功 / 跳过 / 失败 (<原因>) |
| Changelog | 已更新 |
| APK | `release/全省版-v<版本号>-debug.apk` |

## 注意事项

- 始终先推 gitee 再推 GitHub，gitee 失败则中止后续步骤
- GitHub 推送失败不阻塞流程，但需在报告中明确标注
- `release/` 目录在 `.gitignore` 中，changelog 需 `-f` 强制添加，APK 不提交
- 提交或构建前复核 `versionCode` 是否等于发布前当前值加 `1`，不得由 `versionName` 映射生成
- 构建前确认本地 JDK + Android SDK 环境已配置（执行 `bash scripts/android/doctor.sh` 检查）
- 如果工作区不干净，先引导用户使用 `git-checkpoint` 清理
