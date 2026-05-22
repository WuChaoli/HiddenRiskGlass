# CLI 命令完整参考

## 安装与初始化

| 命令 | 说明 | 常用选项 |
|------|------|---------|
| `codegraph` | 交互式安装器 | — |
| `codegraph install` | 显式运行安装器 | `--target`, `--location`, `--yes`, `--print-config` |
| `codegraph init [path]` | 项目初始化 | `-i, --index` 同时构建索引 |
| `codegraph uninit [path]` | 移除项目 | `--force` 跳过确认 |

## 索引管理

| 命令 | 说明 | 常用选项 |
|------|------|---------|
| `codegraph index [path]` | 完整索引 | `--force` 重新索引，`--quiet` 减少输出 |
| `codegraph sync [path]` | 增量同步 | — |
| `codegraph status [path]` | 查看索引统计 | — |

## 查询命令

| 命令 | 说明 | 常用选项 |
|------|------|---------|
| `codegraph query <search>` | 搜索符号 | `--kind <kind>`, `--limit <n>`, `--json` |
| `codegraph files [path]` | 文件结构 | `--format <fmt>`, `--filter <glob>`, `--max-depth <n>`, `--json` |
| `codegraph context <task>` | 构建 AI 上下文 | `--format <fmt>`, `--max-nodes <n>` |

### query 支持的 kind 过滤

- `class`, `function`, `method`, `variable`, `interface`, `enum`, `import`, `file`

## 分析命令

| 命令 | 说明 | 常用选项 |
|------|------|---------|
| `codegraph affected [files...]` | 查找受影响测试 | `--stdin`, `-d <depth>`, `-f <glob>`, `-j`, `-q` |

### affected 常用模式

```bash
# 管道输入（最常用）
git diff --name-only | codegraph affected --stdin --quiet

# 直接传入文件列表
codegraph affected src/api.ts src/db.ts --depth 3

# JSON 输出（脚本解析）
git diff --name-only | codegraph affected --stdin --json
```

## MCP 服务器

| 命令 | 说明 |
|------|------|
| `codegraph serve --mcp` | 启动 MCP 服务器（stdio 模式）|

## 全局选项

| 选项 | 说明 |
|------|------|
| `-V, --version` | 输出版本 |
| `-h, --help` | 显示帮助 |
