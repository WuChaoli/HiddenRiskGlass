## Context

当前代码中 `LocalHazardPushService.pushLocalHazard()` 和 `InspectionFinishService.finishInspection()` 均通过 `DualEndpointSubmitCoordinator` 并行提交主备两个端点，两个端点都返回后才判定整体成功或失败。备用端点 `backupBaseUrl` 硬编码为 `http://183.147.142.133:7443`，路径格式为 `/hxy/apis/hazardCheckRecord/saveHazard`（save）和 `/hxy/apis/hazardCheckRecord/hazardIsEnd`（end）。备用端点仅写入数据库，与主端点功能重复。

## Goals / Non-Goals

**Goals:**
- 移除备份端点提交逻辑，仅通过主 URL（来自二维码解析的 `apiBaseUrl`）提交 save 和 end 请求
- 删除不再需要的 `DualEndpointSubmitCoordinator` 类
- 清理配置层、Session 层中备份相关的字段和常量
- 更新单元测试以覆盖变更后的单端点行为

**Non-Goals:**
- 不修改 `InspectionRetryExecutor` 的重试策略和退避时间
- 不修改请求体 / 响应体的数据模型
- 不修改 `InspectionBackgroundUploadQueue` 的任务数据结构

## Decisions

### 1. 直接使用 InspectionRetryExecutor 替代 DualEndpointSubmitCoordinator

**决策**: 在 `pushLocalHazard()` 和 `finishInspection()` 中移除 `DualEndpointSubmitCoordinator`，直接调用 `submitSingleEndpoint()` 方法，将回调直接绑定到单端点的成功/失败结果上。

**理由**: 不再有双端点聚合需求，`DualEndpointSubmitCoordinator` 成为不必要的抽象层。`submitSingleEndpoint()` 已封装了 `InspectionRetryExecutor` 的重试逻辑，可直接复用。

**替代方案**: 保留 `DualEndpointSubmitCoordinator` 但只传单个 label。否决理由：保留无用抽象违反简洁原则。

### 2. 移除 SaveResultApiConfig 中备份相关字段

**决策**: 从 `SaveResultApiConfig` 中删除 `backupBaseUrl` 和 `backupSaveResultUrl`，同步删除 `SaveResultApiConfigOverride` 中对应字段和 `InspectionConfigRepository` 中的 merge 逻辑。

**理由**: 备份 URL 仅用于被删除的备份端点，删除后无使用方。

### 3. 简化 InspectionWorkflowSession 的双提交进度追踪

**决策**: 删除 `DualSubmitProgress` 数据类及其 `finishSubmitProgress`、`phoneSyncProgress` 属性，删除 `markFinishSubmitBackupDone()`、`markPhoneSyncBackupDone()` 方法。`markFinishSubmitPrimaryDone()` 和 `markPhoneSyncPrimaryDone()` 简化为布尔标志。

**理由**: `DualSubmitProgress` 仅用于追踪主备双端点的完成状态，移除备份后不再需要。

## Risks / Trade-offs

- **网络容错降低**: 移除备份端点后，若主 URL 不可达，请求将完全失败（有重试但无备链路）。→ 缓解：重试机制（4 次 / 1s, 2s, 3s 退避）仍然保留，且主 URL 每次来自二维码解析，可动态变更。
- **Session 状态清理**: `InspectionWorkflowSession` 中 `DualSubmitProgress` 可能被其他调用方引用。→ 缓解：通过 grep 确认引用范围，仅影响 save/end 链路。

## Open Questions

（无）
