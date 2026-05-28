# 变更分析与文档同步建议

## 变更分类
- 模块内新增：hiddenrisk 新增 VoiceControlManager.kt → L3
- 跨模块依赖变更：input.VoiceController → hiddenrisk.VoiceControlManager → L1+L2
- 架构边界变更：hiddenrisk 从"被依赖: 无"变为被 input 依赖

## 同步决策

### L1 — CLAUDE.md（当前 228 行）
- 统一输入层描述追加语音控制管理位置
- 包结构表 hiddenrisk 职责追加"语音控制"
- 模块代码地图覆盖范围追加"语音控制"
- 预计最终 ~235 行，在 250 行约束内

### L2 — CODEMAPS.md
当前不存在，不建议在本次小范围变更中初始化。

### L3 — hiddenrisk/README.md
- 文件索引新增 VoiceControlManager 条目
- "被依赖"从"无"更新为"input/语音控制管理"

### L3 — input/README.md
- VoiceController 条目更新职责描述（委托关系）
- 依赖关系新增 hiddenrisk

## 核心结论
本次变更打破了 hiddenrisk 的单向顶层边界，L1 和 L3 均需同步更新。
