# HazardResultFormatter.kt 新增后的文档同步分析

## 变更概要

在 hiddenrisk 模块新增工具类 HazardResultFormatter.kt，从 AiInspectionActivity.kt 中抽取格式化逻辑，不涉及跨模块调用。抽取内容包括 ensureAdviceDisplayPrefix()、buildAdviceDisplayText() 等方法以及 ADVICE_DISPLAY_PREFIX 常量。

## 需要更新的文档

### 1. app/src/main/java/com/rokid/glass/hiddenrisk/README.md -- 必须更新

**原因**: 该 README 是 hiddenrisk 模块的代码地图，对所有文件按类别建表索引。新增文件必须纳入索引。

**具体修改**:

修改 A：在 "UI/辅助组件" 文件索引表中追加一行：

```
| HazardResultFormatter.kt | 隐患结果格式化工具，建议文案前缀拼接、展示文本构建 | ensureAdviceDisplayPrefix(), buildAdviceDisplayText() |
```

修改 B：将分类标题 `UI/辅助组件（9 个）` 改为 `UI/辅助组件（10 个）`。

修改 C（可选）：如果 HazardResultFormatter 在调用链中被显式调用，可考虑在相关调用链描述中提及。但鉴于其定位是纯工具类，非强制。

### 2. CLAUDE.md -- 无需更新

**原因**: 第 165 行记录为 `~50 文件`，当前实际 52 个，新增后 53 个。近似值仍然准确。

### 3. docs/公共能力/ 系列文档 -- 已删除，无需更新

**原因**: 该目录下所有文档在 git 中已标记删除（D），不再属于当前文档体系。

## 分析依据

### 代码侧证据

- AiInspectionActivity.kt (4598行)：包含 ensureAdviceDisplayPrefix() (L2869)、buildAdviceDisplayText() (L4042) 等格式化方法
- OnlineHazardAdviceFormatter.kt：已有的格式化工具类，作为新增 HazardResultFormatter 的同类型参考
- ResolvedHazardContent.kt：已有的数据/格式化类，位于 "UI/辅助组件" 分类中，HazardResultFormatter 应归入同类

### 文档侧证据

- hiddenrisk/README.md "UI/辅助组件" 当前归类了 9 个文件，包括 ResolvedHazardContent 等同类型工具
- hiddenrisk/README.md "被依赖" 字段写明：“无 — 本模块是业务顶层，其他模块不依赖此包”，因此不涉及跨模块文档同步
- CLAUDE.md 模块代码地图中 hiddenrisk 行描述为 `~50 文件`，是近似值
- docs/公共能力/ 目录下所有文件在 git 状态中标记为 D（已删除），不纳入当前文档体系

## 结论

本次变更属于模块内纯重构（抽取工具类），跨模块依赖为零。唯一需要同步的文档是 hiddenrisk 模块级 README，在 "UI/辅助组件" 表中追加一行并更新文件计数。其他文档无需变更。
