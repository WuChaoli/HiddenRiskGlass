# MCP Tools 详细说明

CodeGraph 提供 8 个 MCP 工具，供 AI Agent 通过 MCP 协议调用。

## 工具列表

### `codegraph_search`

按名称跨代码库搜索符号。

**参数**：
- `query` (string, 必需): 搜索关键词
- `kind` (string, 可选): 符号类型过滤 (`class`, `function`, `method`, `variable`, `interface`, `enum`, `import`, `file`)
- `limit` (number, 可选): 最大返回结果数，默认 20

**返回**：符号列表（含 nodeId、名称、类型、文件路径、行号、相关度分数）

**示例**：
```json
{
  "query": "CameraManager",
  "kind": "class",
  "limit": 10
}
```

### `codegraph_context`

为特定任务构建相关代码上下文。

**参数**：
- `task` (string, 必需): 任务描述
- `maxNodes` (number, 可选): 最大返回节点数，默认 20
- `includeCode` (boolean, 可选): 是否包含源码，默认 true
- `format` (string, 可选): 输出格式 (`markdown`, `json`)，默认 `markdown`

**返回**：相关符号 + 代码片段的 Markdown/JSON

**示例**：
```json
{
  "task": "fix memory leak in camera preview",
  "maxNodes": 15,
  "includeCode": true
}
```

### `codegraph_callers`

查找调用某个函数/方法的所有位置。

**参数**：
- `nodeId` (string/number, 必需): 目标符号的 nodeId
- `limit` (number, 可选): 最大返回结果数

**返回**：调用者列表（含文件路径、行号、调用代码片段）

### `codegraph_callees`

查找某个函数/方法内部调用的所有符号。

**参数**：
- `nodeId` (string/number, 必需): 目标符号的 nodeId
- `limit` (number, 可选): 最大返回结果数

**返回**：被调用者列表

### `codegraph_impact`

分析修改某个符号会影响哪些代码（影响半径）。

**参数**：
- `nodeId` (string/number, 必需): 目标符号的 nodeId
- `depth` (number, 可选): 依赖遍历深度，默认 5

**返回**：受影响符号列表（含影响类型：直接调用、间接调用、继承等）

### `codegraph_node`

获取特定符号的详细信息。

**参数**：
- `nodeId` (string/number, 必需): 目标符号的 nodeId
- `includeCode` (boolean, 可选): 是否包含源码，默认 false

**返回**：符号元数据 + 可选源码

### `codegraph_files`

获取索引化的文件结构（比文件系统扫描更快）。

**参数**：
- `path` (string, 可选): 起始路径，默认项目根目录
- `maxDepth` (number, 可选): 最大深度
- `filter` (string, 可选): glob 过滤模式

**返回**：文件树结构

### `codegraph_status`

检查索引健康度和统计信息。

**参数**：无

**返回**：索引状态、文件数、符号数、最后更新时间等

## 使用策略

| 任务 | 首选工具 | 原因 |
|------|----------|------|
| 找符号定义 | `codegraph_search` | FTS5 全文搜索，速度快 |
| 获取代码上下文喂给 AI | `codegraph_context` | 自动选择最相关节点 |
| 找调用链上游 | `codegraph_callers` | 预计算调用图 |
| 找调用链下游 | `codegraph_callees` | 预计算调用图 |
| 评估修改影响 | `codegraph_impact` | 多层依赖遍历 |
| 查看文件结构 | `codegraph_files` | 比 `ls`/`find` 更快（读索引而非磁盘）|
