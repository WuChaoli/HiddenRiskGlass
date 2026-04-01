# 双仓库同步说明

## 仓库地址

- **GitHub**: 主仓库 (master 分支)
- **Gitee**: 镜像仓库 (master 分支) - https://gitee.com/hangzhou-fengjing/hiddenriskglass.git

## 自动同步机制

已配置 GitHub Actions 自动同步：

```
开发者 push → GitHub (master) → GitHub Actions → 自动同步到 Gitee (master)
```

### 工作流程文件

- 位置: `.github/workflows/sync-to-gitee.yml`
- 触发条件: push 到 master 分支
- 同步方式: 使用 `yesolutions/mirror-action` 镜像到 Gitee

## 首次使用配置

### 1. 在 GitHub 仓库添加 Secrets

进入 GitHub 仓库 → Settings → Secrets and variables → Actions → New repository secret

添加以下 Secrets:

| Secret 名称 | 值 | 说明 |
|------------|-----|------|
| `GITEE_PAT` | `16493620ed7009fe50c9f6f5df9f31ff` | Gitee 私人令牌 |
| `GITEE_USERNAME` | `hangzhou-fengjing` | Gitee 用户名/组织名 |

### 2. 首次全量推送

配置 Secrets 后，执行以下命令完成首次推送：

```bash
# 添加 Gitee remote
git remote add gitee https://gitee.com/hangzhou-fengjing/hiddenriskglass.git

# 首次推送到 Gitee
git push gitee master

# 验证
git remote -v
```

### 3. 验证同步

推送代码到 GitHub 后：
1. 查看 GitHub Actions 是否成功运行
2. 检查 Gitee 仓库是否收到同步的代码

## 日常使用

开发者只需正常操作 GitHub 仓库：

```bash
# 正常开发流程
git add .
git commit -m "提交信息"
git push origin master

# Gitee 会自动同步，无需额外操作
```

## 故障排查

### 同步失败

1. 检查 GitHub Actions 日志
2. 确认 GITEE_PAT 未过期
3. 确认 Gitee 仓库可访问

### 手动同步

如果自动同步失败，可手动执行：

```bash
git push gitee master
```

### 查看同步状态

```bash
# 查看 remote 配置
git remote -v

# 查看 Gitee 最新提交
git fetch gitee
git log gitee/master --oneline -5
```

## 注意事项

- Gitee 令牌属于敏感信息，不要提交到代码仓库
- 同步可能有 1-2 分钟延迟
- 如果 Gitee 仓库已有内容，首次推送可能需要强制推送（谨慎操作）
