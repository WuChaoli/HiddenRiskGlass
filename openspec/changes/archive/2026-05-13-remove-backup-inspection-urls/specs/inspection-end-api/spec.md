## ADDED Requirements

### Requirement: 结束巡检接口仅通过主 URL 提交

系统 SHALL 仅使用由二维码解析得到的 `apiBaseUrl` 构造结束请求 URL（路径 `/smartGlasses/pushHidDangerEnd`），不再发送到备用端点。

#### Scenario: 正常结束巡检
- **WHEN** 用户确认结束巡检且主 URL 可连通
- **THEN** 系统通过 `InspectionRetryExecutor` 向主 URL 发送 POST 请求，请求体包含 `authCode`、`objectId`、`userId`、`ifEnd="1"`，响应 `code` 为 0 或 200 时回调 `onSuccess()`

#### Scenario: 主 URL 不可达
- **WHEN** 主 URL 连接失败且重试次数耗尽（最多 4 次）
- **THEN** 系统回调 `onError("结束巡检失败，请重试")`，不再尝试备用端点

#### Scenario: 后台 Service 结束提交
- **WHEN** 结束请求通过 `InspectionBackgroundUploadService` 出队执行
- **THEN** 行为与直接调用一致，仅通过主 URL 提交
