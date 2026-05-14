## Why

当前在线隐患识别的  检测路径使用 JSON 接口，后端逐行返回 "是"/"否" 文案聚合判定。后端已升级为新的 JSON 接口（端口 10010），返回结构化 JSON 包含 `content`（boolean）和 `inference_result`（检测框数组）。需要切换检测链路以适配新接口，同时保留 /2 的 SSE 路径不变。

## What Changes

- **URL 变更**：`http://183.147.142.133:10010/ai/auto` → `http://183.147.142.133:10010/ai/auto`
- **网络协议变更**： 检测路径从 OkHttp SSE（`EventSource`）切换为普通 HTTP POST（`enqueue`）
- **判定逻辑变更**：从聚合 SSE 文本匹配 "是"/"否" 改为解析 JSON，`content == true && inference_result 非空` → `hasHazard = true`
- **响应模型新增**：新增 `IdentifyResponse` / `InferenceResultItem` 数据类解析新接口 JSON
- **保留路径**： 深度分析、 巡检指引继续保持 SSE

## Capabilities

### New Capabilities
- `online-detection-json-response`：在线隐患检测 JSON 响应解析与判定能力

### Modified Capabilities
<!-- 此次变更不涉及已有 spec 的需求级变化，仅实现细节调整 -->

## Impact

- `InspectionAppConfig.kt:83`：1 行 URL 修改
- `AiArSseService.kt`：~60 行变更（新增响应模型 + 重构  请求路径）
- 500ms 轮询逻辑不变
- `OnlineHazardDetectionService` / `AiInspectionActivity` 回调链不变（接口隔离）
