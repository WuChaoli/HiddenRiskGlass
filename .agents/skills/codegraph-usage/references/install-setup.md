# 安装与配置指南

## 快速安装（推荐）

```bash
npx @colbymchenry/codegraph
```

安装器会自动：
- 检测已安装的 AI 代理（Claude Code、Cursor、Codex CLI、opencode）
- 询问配置全局还是项目本地
- 将 `codegraph` 添加到 PATH（可选）
- 写入各代理的 MCP 服务器配置
- 为 Claude Code 设置自动允许权限
- 初始化当前项目（仅限本地安装）

## 非交互式安装（CI/脚本）

```bash
# 自动检测代理，全局安装
codegraph install --yes

# 指定目标代理
codegraph install --target=claude,cursor --yes

# 项目本地安装
codegraph install --target=auto --location=local

# 仅打印配置片段，不写入文件
codegraph install --print-config claude
```

## 手动安装

### 1. 安装 CLI

```bash
npm install -g @colbymchenry/codegraph
```

### 2. 项目初始化

```bash
cd your-project
codegraph init -i    # -i = 同时构建索引
```

### 3. 配置 MCP 服务器

#### Claude Code (`~/.claude.json`)

```json
{
  "mcpServers": {
    "codegraph": {
      "type": "stdio",
      "command": "codegraph",
      "args": ["serve", "--mcp"]
    }
  }
}
```

#### Cursor (`~/.cursor/mcp.json`)

```json
{
  "mcpServers": {
    "codegraph": {
      "command": "codegraph",
      "args": ["serve", "--mcp"]
    }
  }
}
```

#### Codex CLI (`~/.codex/config.json`)

```json
{
  "mcpServers": {
    "codegraph": {
      "type": "stdio",
      "command": "codegraph",
      "args": ["serve", "--mcp"]
    }
  }
}
```

### 4. 配置自动权限（Claude Code）

在 `~/.claude/settings.json` 中添加：

```json
{
  "permissions": {
    "allow": [
      "mcp__codegraph__codegraph_search",
      "mcp__codegraph__codegraph_context",
      "mcp__codegraph__codegraph_callers",
      "mcp__codegraph__codegraph_callees",
      "mcp__codegraph__codegraph_impact",
      "mcp__codegraph__codegraph_node",
      "mcp__codegraph__codegraph_files",
      "mcp__codegraph__codegraph_status"
    ]
  }
}
```

## 项目配置 (`.codegraph/config.json`)

```json
{
  "version": 1,
  "languages": ["typescript", "javascript", "kotlin", "java"],
  "exclude": ["node_modules/**", "dist/**", "build/**", "*.min.js", ".git/**"],
  "frameworks": [],
  "maxFileSize": 1048576,
  "extractDocstrings": true,
  "trackCallSites": true
}
```

## 验证安装

```bash
codegraph --version      # 应输出版本号
codegraph status         # 应显示索引统计
```
