## 变更分析

**变更类型**：模块内重构（从 AiInspectionActivity.kt 提取格式化逻辑到新文件 HazardResultFormatter.kt，不涉及跨模块调用）。

## 三层文档同步判定

### L1 — CLAUDE.md
无需更新。未新增/删除模块，模块索引表无需修改。

### L2 — CODEMAPS.md
无需创建/更新。纯模块内重构不触发 L2 更新。

### L3 — hiddenrisk/README.md
需要更新。在"UI/辅助组件"表格中新增一行 HazardResultFormatter.kt 条目，并更新分类计数。

## 验证清单
- [x] CLAUDE.md 228 行，≤ 250 行约束满足
- [x] CODEMAPS.md 不存在且无需创建
- [x] L1 模块索引表路径一致
- [x] 无跨模块 import 关系变更
- [x] 新文件路径在 hiddenrisk 模块内

## 总结
一次最简同步——仅需在 L3 新增一行条目，L1 和 L2 完全不受影响。
