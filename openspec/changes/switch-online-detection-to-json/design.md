## Context

当前 `AiArSseService` 的 `identifyItemHazard`（）使用 OkHttp SSE `EventSource` 发起 POST 到 `/ai/auto`，接收多行 SSE event，通过 `AiArEventAggregator` 聚合 `content` 字段，最后由 `parseHasHazard` 匹配 "是"/"否" 判定。

后端已升级：10010 → 10010，响应从 SSE 流变为一次性 JSON body，包含 `content`（boolean）和 `inference_result`（检测框数组）。

## Goals / Non-Goals

**Goals:**
-  检测路径从 SSE 切换为普通 HTTP POST，适配新 JSON 响应
- 正确解析 JSON：`content == true && inference_result 非空` 时判定 hasHazard
- 下游 `OnlineHazardDetectionService` / `AiInspectionActivity` 回调链零修改

**Non-Goals:**
- 不修改  深度分析的 SSE 流式路径
- 不修改  巡检指引的 SSE 路径
- 不修改 500ms 轮询间隔逻辑
- 不将 inference_result 中的 bbox/label 用于 UI 渲染（仅保存传递）

## Decisions

### 1. 最小范围重构：仅改  路径

在 `requestHazardDetection` 内部用 OkHttp `enqueue()` 替代 `openStream(EventSource)`。`openStream()` 方法保留给 /2 使用。

**理由**：`OnlineHazardDetectionService` 通过 `RequestGateway` 接口 + `DetectCallback` 接口双层隔离，Activity 层完全无感。改动控制在单个文件内的一个方法。

### 2. 保留 RequestHandle 取消机制

沿用现有 `RequestHandle` 类管理请求生命周期（cancel），`enqueue()` 返回的 `Call` 对象可调用 `cancel()` 对应 SSE 的 `eventSource.cancel()`。

### 3. 响应解析内聚在 AiArSseService

新增 `IdentifyResponse` / `InferenceResultItem` 两个 data class 定义在 service 文件内。`inference_result` 序列化为 JSON 字符串作为 `rawText` 传递给 callback，保持接口兼容。

### 4. 超时与并发不变

超时（`detectTimeoutMs`）和并发限制（`detectConcurrencyLimit`）仍在 `OnlineHazardDetectionService` 层控制，AiArSseService 不重复管理。

## Risks / Trade-offs

- **[Risk]** 新接口返回 HTTP 非 200 → onFailure 回调触发，轮询继续 → **Mitigation**: 保持现有 remoteFailureCount 累加 + 本地 fallback 机制
- **[Risk]** JSON 解析失败（结构不匹配）→ onFailure → **Mitigation**: `runCatching` 包裹 Gson 解析，失败时回调 onFailure
- **[Trade-off]** `inference_result` 当前仅序列化保存，未结构化拆解 bbox → 如果后续需要 UI 画框，需要二次解析 → 依赖 JSON 结构稳定后扩展

## Open Questions

-  深度分析是否后续也需改为 JSON？当前暂定不改
