## Why

当前隐患保存（save）和结束巡检（end）两个接口均实现了主备双端点并行提交机制，每次请求同时命中主 URL（来自二维码负载）和备用 URL（硬编码的 `backupBaseUrl`）。备用端点仅写入数据库，与主端点语义重复，且增加了网络开销、代码复杂度和维护成本。移除备用 URL 可简化提交链路，降低失败排查难度。

## What Changes

- 移除 `LocalHazardPushService` 中备份端点提交逻辑，仅保留主 URL 单端点提交
- 移除 `InspectionFinishService` 中备份端点提交逻辑，仅保留主 URL 单端点提交
- 删除 `DualEndpointSubmitCoordinator` 类（不再需要聚合主备结果）
- 移除 `InspectionWorkflowSession` 中的 `DualSubmitProgress`、`markFinishSubmitBackupDone()`、`markPhoneSyncBackupDone()` 等备份进度追踪字段
- 移除 `SaveResultApiConfig` 中的 `backupBaseUrl` 和 `backupSaveResultUrl` 字段
- 移除 `HttpUtils` 中的 `BACKUP_BASE_URL`、`BACKUP_SAVE_RESULT_URL` 常量
- 更新相关单元测试：删除备份端点测试用例，简化协调器测试

## Capabilities

### New Capabilities

（无新增能力）

### Modified Capabilities

- `hazard-save-api`: 保存接口从主备双端点改为仅主 URL 单端点提交
- `inspection-end-api`: 结束接口从主备双端点改为仅主 URL 单端点提交

## Impact

- `app/.../hiddenrisk/LocalHazardPushService.kt` — 移除备份端点提交分支
- `app/.../hiddenrisk/InspectionFinishService.kt` — 移除备份端点提交分支
- `app/.../hiddenrisk/DualEndpointSubmitCoordinator.kt` — 删除或替换为单端点包装
- `app/.../hiddenrisk/InspectionRetryExecutor.kt` — 不变（保留重试逻辑）
- `app/.../hiddenrisk/InspectionBackgroundUploadService.kt` — 不变（通过 Service 调用，不受影响）
- `app/.../hiddenrisk/InspectionBackgroundUploadQueue.kt` — 不变
- `app/.../workflow/InspectionWorkflowSession.kt` — 移除 `DualSubmitProgress` 及相关方法
- `app/.../config/InspectionAppConfig.kt` — 移除 `backupBaseUrl`、`backupSaveResultUrl`
- `app/.../config/InspectionConfigRepository.kt` — 移除备份 URL merge 逻辑
- `app/.../utils/HttpUtils.kt` — 移除 `BACKUP_BASE_URL`、`BACKUP_SAVE_RESULT_URL`
- 测试文件：`DualEndpointSubmitCoordinatorTest.kt`、`LocalHazardPushApiProtocolTest.kt`、`InspectionFinishApiProtocolTest.kt`
