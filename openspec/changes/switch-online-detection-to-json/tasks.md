## 1. 配置层变更

- [ ] 1.1 修改 `InspectionAppConfig.kt` 中 `AiArApiConfig.url` 默认端口：`5000` → `10010`

## 2. 响应模型定义

- [ ] 2.1 在 `AiArSseService.kt` 内新增 `IdentifyResponse` data class
- [ ] 2.2 在 `AiArSseService.kt` 内新增 `InferenceResultItem` data class

## 3. ctype=3 检测路径重构

- [ ] 3.1 重构 `requestHazardDetection`：用 OkHttp `enqueue()` 替代 SSE `openStream()`
- [ ] 3.2 实现 JSON 响应解析与 hasHazard 判定（`content == true && inference_result 非空`）
- [ ] 3.3 `inference_result` 序列化为 JSON 字符串作为 rawText 传递给 callback
- [ ] 3.4 `RequestHandle.bind()` 改为接收 `Call` 对象替代 `EventSource`
- [ ] 3.5 移除旧的 `parseHasHazard` 字符串匹配方法
- [ ] 3.6 移除 ctype=3 路径对 `AiArEventAggregator` 的依赖
- [ ] 3.7 处理请求取消（`Call.cancel()`）

## 4. 清理与验证

- [ ] 4.1 清理不再需要的 import（确保不影响 ctype=0/2 路径）
- [ ] 4.2 编译通过验证：`./gradlew assembleDebug`
