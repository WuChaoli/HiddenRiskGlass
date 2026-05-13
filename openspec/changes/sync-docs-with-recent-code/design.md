## Context

近 8 次提交对隐患识别链路参数、企业信息字段和配置变体做了多处调整，当前 `docs/` 内容停留在旧态，与代码实际行为不一致。由于文档是本项目"跨文档真相源"的核心，需要逐一对齐。

涉及变更范围：
- `ctype=1` → `ctype=3`（物品识别接口版本升级）
- `onlineDetectIntervalMs`: 1000ms → 500ms
- `detectTimeoutMs`: 3000ms → 1500ms
- 新增 `onlineDetectConcurrencyLimit: 5`，检测模型从 single-flight 改为受限并发池
- `AutoInferenceLoopDecider.decideOnlineLoopAdvance()` 删除 `requestInFlight` 参数
- `OnlineHazardDetectionService` 内部从单 `ActiveDetection` 改为 `activeDetections` Map
- `EnterpriseInfo` 新增 `placeCode`、`lastInspectionDate` 字段
- 删除 `localHazardDetect.jsonc`、`demoOnlineonly.jsonc` 变体配置

## Goals / Non-Goals

**Goals:**
- 隐患识别链路文档与代码实际行为一致
- 隐患识别功能模块文档与代码实际行为一致
- 任务关联文档补充新增企业信息字段
- 开发检查清单过时项更新

**Non-Goals:**
- 不修改代码行为
- 不发生文档体系重构
- 不新增原本不存在的文档文件

## Decisions

| 决策 | 选择 | 依据 |
|------|------|------|
| 直接更新现有 md 正文 | 不改动 docs/ 文件结构 | 保持现有文档体系，最小修改原则 |
| `OnlineHazardDetectionService` 旧 single-flight 描述改为并发池描述 | 替换"仅允许单飞"段落 | 代码已改为 `activeDetections` Map，支持最多 5 并发 |
| 配置变体删除只在变更日志记录 | 不在正文保留已删除配置引用 | 保持文档内容与当前代码一致 |
| 开发检查清单逐条审查 | 匹配最新代码 | 确保验证检查不产生误导 |

## Risks / Trade-offs

- [低] ctype=3 变更可能再次发生，届时需要再次同步。→ 文档顶部标注 ctype 为"当前值+配置来源"，降低未来更新成本。
- [低] 并发池描述若后续调整 limit 值，需同步更新。→ 标注为"当前配置默认值"，指向 `inspection_config.base.jsonc`。
