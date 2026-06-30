---
name: codegraph-usage
description: "代码库智能查询能力。触发条件：搜索代码符号、查找定义、分析调用链、查询代码库结构、codegraph、cg。"
---

# codegraph-usage Skill

在已索引的代码库中，通过 CodeGraph CLI 或 MCP 工具快速搜索符号、分析调用关系、获取代码上下文。

## When to Use This Skill

- 需要搜索代码库中的函数/类/变量定义
- 需要查找某个函数被哪些代码调用（调用链上游）
- 需要查找某个函数调用了哪些代码（调用链下游）
- 需要分析修改某个符号会影响哪些代码（影响半径）
- 需要为 AI 构建与任务相关的代码上下文
- 需要在 CI 中找出受变更影响的测试文件
- 用户提到 codegraph、cg、codegraph-cli、MCP codegraph

## Not For / Boundaries

- **不适用于未索引项目**：必须先运行 `codegraph init` + `codegraph index`
- **不替代 LSP 精确跳转**：LSP（如 Serena）更适合单个符号的精确跳转和重命名
- **不替代全文搜索**：简单文本搜索用 `grep`/`ripgrep` 更快
- **不自动索引**：不会自动创建索引，需手动触发
- **不修改代码**：CodeGraph 是只读查询工具

## Prerequisites

- 项目已执行 `codegraph init` 和 `codegraph index`（或 `codegraph sync`）
- 索引文件位于 `.codegraph/codegraph.db`

## Quick Reference

### CLI 常用命令

```bash
# 搜索符号（最常用）
codegraph query "UserService" --limit 20
codegraph query "Camera" --kind class --limit 10

# 获取代码上下文（适合喂给 AI）
codegraph context "camera initialization flow"

# 查看文件结构
codegraph files --max-depth 2
codegraph files --filter "*.kt"

# 查找受变更影响的测试（CI 场景）
git diff --name-only | codegraph affected --stdin --quiet

# 索引管理
codegraph status          # 查看索引状态
codegraph sync            # 增量同步变更
codegraph index --force   # 强制全量重建索引
```

### MCP Tools 速查

| 工具 | 用途 | 典型参数 |
|------|------|---------|
| `codegraph_search` | 按名称搜索符号 | `query`, `kind`, `limit` |
| `codegraph_context` | 构建任务相关上下文 | `task`, `maxNodes`, `includeCode` |
| `codegraph_callers` | 查找调用方 | `nodeId`, `limit` |
| `codegraph_callees` | 查找被调用方 | `nodeId`, `limit` |
| `codegraph_impact` | 影响分析 | `nodeId`, `depth` |
| `codegraph_node` | 获取符号详情 | `nodeId`, `includeCode` |
| `codegraph_files` | 索引文件结构 | `path`, `maxDepth` |
| `codegraph_status` | 索引健康检查 | — |

## Examples

### Example 1: 搜索函数定义

**场景**：用户说"找到 camera 相关的类"
**步骤**：
1. 确认项目已索引：`codegraph status`
2. 执行搜索：`codegraph query "Camera" --limit 30`
3. 如需过滤类型：`codegraph query "Camera" --kind class --limit 10`
4. 获取某符号详情：`codegraph node <nodeId> --include-code`

### Example 2: 分析修改影响

**场景**：用户要修改 `QuickCameraManager.initialize()`，想知道影响范围
**步骤**：
1. 先搜索到该符号的 nodeId：`codegraph query "QuickCameraManager.initialize"`
2. 执行影响分析：`codegraph impact <nodeId> --depth 2`
3. 查看调用链上游：`codegraph callers <nodeId>`

### Example 3: CI 中运行受影响的测试

**场景**：提交前只跑受变更影响的测试
**步骤**：
1. 获取变更文件：`git diff --name-only HEAD`
2. 管道给 codegraph：`git diff --name-only HEAD | codegraph affected --stdin --quiet`
3. 运行测试：`npx vitest run $(codegraph affected --stdin --quiet < <(git diff --name-only))`

## Installation & Setup

详见 [`references/install-setup.md`](references/install-setup.md)。

## References

- [`references/cli-commands.md`](references/cli-commands.md) — CLI 完整命令参考
- [`references/mcp-tools.md`](references/mcp-tools.md) — MCP 8 个工具详细说明
- [`references/install-setup.md`](references/install-setup.md) — 安装与配置指南
- [`references/troubleshooting.md`](references/troubleshooting.md) — 故障排查与修复
- [`assets/mcp-config-snippets/`](assets/mcp-config-snippets/) — Codex / Cursor / Codex 配置模板

## Maintenance

- Sources: https://github.com/colbymchenry/codegraph
- Last updated: 2026-05-21
- Known limits: 仅支持已索引项目；不替代 LSP 精确语义操作
