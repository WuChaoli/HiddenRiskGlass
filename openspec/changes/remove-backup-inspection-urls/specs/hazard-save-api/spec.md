## ADDED Requirements

### Requirement: 隐患保存接口仅通过主 URL 提交

系统 SHALL 仅使用由二维码解析得到的 `apiBaseUrl` 构造保存请求 URL（路径 `/smartGlasses/pushHidDanger`），不再发送到备用端点。

#### Scenario: 正常保存隐患
- **WHEN** 用户确认保存隐患且主 URL 可连通
- **THEN** 系统通过 `InspectionRetryExecutor` 向主 URL 发送 POST 请求，请求体包含 `authCode`、`objectId`、`userId`、`image`(base64)、`hidDanger` 列表，响应 `code` 为 0 或 200 时回调 `onSuccess()`

#### Scenario: 主 URL 不可达
- **WHEN** 主 URL 连接失败且重试次数耗尽（最多 4 次）
- **THEN** 系统回调 `onFailure("本地隐患保存失败，请重试")`，不再尝试备用端点

#### Scenario: 图片或隐患数据为空
- **WHEN** `jpegBytes` 为空或 `hidDanger` 列表为空
- **THEN** 系统立即回调 `onFailure()`，不发起任何网络请求

### Requirement: 配置层不再包含备份 URL

`SaveResultApiConfig` SHALL 仅保留 `primarySaveResultUrl`、`connectTimeoutMs`、`readTimeoutMs`、`writeTimeoutMs` 字段，不再包含 `backupBaseUrl` 和 `backupSaveResultUrl`。

#### Scenario: 加载巡检配置
- **WHEN** 系统加载 `inspection_config.base.jsonc`
- **THEN** `SaveResultApiConfig` 不解析任何备份 URL 相关字段，`HttpUtils` 不暴露 `BACKUP_BASE_URL` 或 `BACKUP_SAVE_RESULT_URL`

### Requirement: Session 层不再追踪备份提交进度

`InspectionWorkflowSession` SHALL 不再维护 `DualSubmitProgress` 数据类及其 `finishSubmitProgress`、`phoneSyncProgress` 属性，不再提供 `markFinishSubmitBackupDone()`、`markPhoneSyncBackupDone()` 方法。

#### Scenario: 结束提交进度追踪
- **WHEN** 结束请求主端点完成
- **THEN** 仅更新简单的布尔标志表示完成，不再区分 primary/backup
