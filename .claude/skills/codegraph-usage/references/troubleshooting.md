# 故障排查与修复

## 索引问题

### 症状：`codegraph status` 显示无索引或索引为空

**解决**：
```bash
codegraph index --force    # 强制全量重建索引
codegraph status           # 确认索引恢复
```

### 症状：文件修改后查询结果未更新

**解决**：
```bash
codegraph sync             # 手动增量同步
```

或启用自动监听（在代码中）：
```typescript
cg.watch();   // 文件变更自动同步（2秒防抖）
```

### 症状：索引过程中卡住

**解决**：
```bash
codegraph unlock           # 移除可能存在的 stale lock 文件
```

## MCP 连接问题

### 症状：AI Agent 无法调用 CodeGraph MCP 工具

**排查步骤**：

1. 确认 MCP 服务器正在运行：
   ```bash
   codegraph serve --mcp
   ```

2. 检查代理配置文件路径是否正确：
   - Claude Code: `~/.claude.json`
   - Cursor: `~/.cursor/mcp.json`
   - Codex: `~/.codex/config.json`

3. 确认 `codegraph` 命令在 PATH 中：
   ```bash
   which codegraph
   ```

4. 检查 Claude Code 权限配置是否包含 `mcp__codegraph__*` 条目

### 症状：`codegraph query` 返回 "unknown command"

**原因**：使用了 CodeGraph CLI 中不存在的命令名（如 `search`、`explore`）。

**解决**：使用正确的命令名：
- ❌ `codegraph search` → ✅ `codegraph query`
- ❌ `codegraph explore` → ✅ `codegraph context`

## 查询结果问题

### 症状：搜索结果太少或无关

**解决**：
- 扩大 limit：`codegraph query "keyword" --limit 50`
- 去掉 kind 过滤，或尝试不同的 kind
- 确认项目已正确索引：`codegraph status`

### 症状：`codegraph context` 返回无关代码

**解决**：
- 使用更具体的任务描述
- 调整 `maxNodes` 参数
- 结合 `codegraph_search` 先定位关键符号，再用 `codegraph_node` 获取详情

## 性能问题

### 症状：索引大型项目耗时过长

**解决**：
- 在 `.codegraph/config.json` 中排除不需要的目录
- 增大 `maxFileSize` 以跳过超大文件（或减小以加快索引）
- 使用 `codegraph sync` 而非 `codegraph index --force` 进行日常更新

## 常用诊断命令

```bash
codegraph --version        # 检查版本
codegraph status           # 检查索引健康度
codegraph unlock           # 清理 stale lock
codegraph files            # 确认文件是否被索引
```
