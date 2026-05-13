## ADDED Requirements

### Requirement: 隐患识别链路 ctype 值对齐

文档中物品识别 ctype 值 SHALL 更新为 `3`（原 `1`），与代码 `AiArSseService.CTYPE_IDENTIFY_ITEM_HAZARD = 3` 一致。
场景识别 lane ctype=2 保留给检查指引的描述 SHALL 保持不变。

#### Scenario: 文档 ctype 值正确
- **WHEN** 开发者查阅 `docs/公共能力/隐患识别链路.md` 和 `docs/功能模块/隐患识别.md`
- **THEN** 所有引用物品识别 ctype 的位置显示为 `3`，而非 `1`

### Requirement: 在线检测间隔与超时对齐

文档中 `onlineDetectIntervalMs` SHALL 更新为 `500ms`（原 `1000ms`）。
文档中 `detectTimeoutMs` SHALL 更新为 `1500ms`（原 `3000ms`）。

#### Scenario: 文档节拍值正确
- **WHEN** 开发者查阅 docs 中的在线检测节拍描述
- **THEN** `onlineDetectIntervalMs` 显示为 `500ms`，`detectTimeoutMs` 显示为 `1500ms`

### Requirement: 在线检测并发池描述

文档 SHALL 描述 `OnlineHazardDetectionService` 当前使用 `activeDetections` Map 管理最多 `onlineDetectConcurrencyLimit`（默认 5）个并发请求，替换旧的 single-flight 描述。
文档 SHALL 说明超时按每个独立请求调度，不再共用单个 `detectionTimeoutRunnable`。

#### Scenario: 并发模型描述正确
- **WHEN** 开发者查阅在线检测链路文档
- **THEN** 文档描述为并发池模型（上限 5），而非"仅允许单飞"

### Requirement: AutoInferenceLoopDecider 签名变更

文档中 `decideOnlineLoopAdvance` 签名 SHALL 移除 `requestInFlight` 参数，与当前代码一致。

#### Scenario: 函数签名正确
- **WHEN** 开发者查阅 `AutoInferenceLoopDecider` 相关描述
- **THEN** `requestInFlight` 参数不再出现在文档描述中

### Requirement: 日志锚点更新

文档中的日志锚点 SHALL 反映当前代码实际输出：
- `openStream requestStart ctype=3`（原 `ctype=1`）
- `activePoolSize=` 相关日志（新增）
- `submitDetection accepted/droppedBusy` 日志包含 `activePoolSize` 和 `concurrencyLimit`

#### Scenario: 日志锚点正确
- **WHEN** 开发者查阅链路文档中"关键日志锚点"章节
- **THEN** 日志示例与当前代码输出一致
