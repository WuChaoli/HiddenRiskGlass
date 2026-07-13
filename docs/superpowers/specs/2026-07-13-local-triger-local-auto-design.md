# local-triger 本地触发替代 /ai/auto 设计

## 背景

当前隐患识别页和设备指引页都依赖 `/ai/auto` 做前置触发判断：

1. 隐患识别页：`/ai/auto` 判断当前画面是否存在隐患；命中后继续请求 `/ai/deep` 获取详情。
2. 设备指引页：`/ai/auto` 判断当前画面是否存在设备；命中后继续请求 `/ai/device` 获取设备指引详情。

需要新增一个对外称为 `local-triger` 的变体，在该变体中重新启用本地 NCNN 小模型，用本地推理替代 `/ai/auto` 的触发判断。后续详情接口、前端展示、语音播报、输入交互、页面跳转等逻辑保持不变。

Gradle product flavor 名使用合法标识符 `localTriger`，包名继续使用 `com.rokid.glesse`。

## 成功标准

1. 新增 `localTriger` product flavor，`assembleLocalTrigerDebug` 可构建。
2. `localTriger` 加载 `inspection_config.localTriger.jsonc`，包名不变。
3. `standard` 与 `dataBackup` 仍默认调用 HTTP `/ai/auto`，行为不变。
4. `localTriger` 中隐患识别页不再调用 `/ai/auto`，而是用本地 NCNN 小模型做触发判断。
5. `localTriger` 中设备指引页不再调用 `/ai/auto`，而是用本地 NCNN 小模型做触发判断。
6. 本地命中后，隐患识别页继续复用现有 `/ai/deep` 链路。
7. 本地命中后，设备指引页继续复用现有 `/ai/device` 链路。
8. 当前企业信息缺少有效 `placeCode` 时，保持现有 `/ai/auto` 语义：跳过检测，不调用 HTTP，也不触发本地模型。
9. 本地触发路径不把图片转成 base64，避免不必要的 encode/decode 压力。

## 非目标

1. 不修改 `/ai/deep`、`/ai/device`、`/ai/general`、`/ai/general_deep`、`/ai/gm`、`/ai/sug_checks` 的行为。
2. 不改变隐患识别页和设备指引页的展示、语音、统一输入、弹窗、跳转逻辑。
3. 不改变 `standard` 与 `dataBackup` 的默认运行行为。
4. 不更换 `applicationId`。
5. 不在本次设计中替换 NCNN 模型资产；本次只恢复并接入当前 assets 中的小模型触发能力。

## 配置设计

新增配置项 `aiInspection.autoDetectProvider`：

| 值 | 含义 |
| --- | --- |
| `HTTP` | 默认值，沿用现有 `/ai/auto` 触发判断 |
| `LOCAL_TRIGGER` | 使用本地 NCNN 小模型替代 `/ai/auto` 触发判断 |

新增文件 `app/src/main/assets/inspection_config.localTriger.jsonc`，只覆盖与本地触发相关的字段：

```jsonc
{
  "aiInspection": {
    "autoDetectProvider": "LOCAL_TRIGGER"
  }
}
```

`InspectionConfigRepository` 继续按现有规则读取：

1. `inspection_config.base.jsonc`
2. `inspection_config.<BuildConfig.FLAVOR>.jsonc`

因此 `localTriger` 会自动读取 `inspection_config.localTriger.jsonc`。

## 架构设计

新增一个本地触发服务，命名为 `LocalTriggerDetectionService`。它的目标不是新增页面能力，而是把本地 NCNN 推理包装成一个与 `/ai/auto` 等价的检测 provider。

### Provider 边界

调整 `OnlineHazardDetectionService.RequestGateway` 的职责：

1. 上层仍提交 `DetectionRequest`。
2. Gateway 自己决定是否需要 base64。
3. Gateway 对上层统一回调 `AiArSseService.DetectCallback`。

两个实现：

1. `SseRequestGateway`
   - 默认实现。
   - 将 `DetectionRequest.jpegBytes` 编码为 base64。
   - 调用现有 `AiArSseService.identifyItemHazard()`。

2. `LocalTriggerRequestGateway`
   - 仅 `autoDetectProvider == LOCAL_TRIGGER` 时启用。
   - 不生成 base64。
   - 直接把 `DetectionRequest.jpegBytes` 交给 `LocalTriggerDetectionService`。
   - 输出与 `/ai/auto` 的检测回调保持一致。

`LocalTriggerRequestGateway` 只替换 item detection，不替换 detail：

1. 隐患识别页命中后继续调用 `requestDeepAnalysis()`，实际接口仍是 `/ai/deep`。
2. 设备指引页命中后继续调用 `fetchInspectionGuide()`，实际接口仍是 `/ai/device`。
3. 场景检测与其他在线接口不纳入本次替换。

### 本地服务职责

`LocalTriggerDetectionService` 负责：

1. 检查 `placeCode` gate。
2. 确保 `HiddenRiskNcnn` 实例存在。
3. 确保本地 NCNN 模型已加载。
4. 将 JPEG bytes 解码为 Bitmap。
5. 调用 `HiddenRiskNcnn.submitBitmap()` 执行推理。
6. 读取 `NativeInferenceStats`。
7. 将 detection 结果转换为 `/ai/auto` 等价输出：`hasHazard`、`fullText`、`labels`。

本次实现使用 `jpegBytes -> Bitmap -> submitBitmap()`，原因是现有在线检测请求已经稳定携带 `jpegBytes`，这样可以最小化页面和帧选择逻辑改动。本次不扩展 `DetectionRequest` 携带 NV21 或 square frame。

## placeCode Gate

当前 `/ai/auto` 有一个重要语义：如果没有收到有效 `placeCode`，就不触发 `/ai/auto` 调用。

该语义在本地 provider 中必须保留：

1. 如果 `InspectionWorkflowSession.enterpriseInfo?.placeCode` 为空或空白，直接返回 `hasHazard=false`。
2. 不加载模型。
3. 不执行本地推理。
4. 不计为检测失败。
5. 不触发 `/ai/deep` 或 `/ai/device`。

这个判断放在 provider 入口，保证 HTTP 与 LOCAL 两条检测 provider 的行为一致。

## 隐患识别页数据流

`standard` / `dataBackup`：

1. 页面选择在线检测帧。
2. `OnlineHazardDetectionService.submitDetection()`。
3. `SseRequestGateway` 编码 base64。
4. 调用 `/ai/auto`。
5. 命中后调用 `/ai/deep`。
6. 沿用现有流式展示、语音和输入逻辑。

`localTriger`：

1. 页面选择在线检测帧，仍构造 `DetectionRequest`。
2. `OnlineHazardDetectionService.submitDetection()`。
3. `LocalTriggerRequestGateway` 直接读取 `DetectionRequest.jpegBytes`。
4. `LocalTriggerDetectionService` 调用本地 NCNN 小模型。
5. 未命中时返回 `hasHazard=false`，继续下一轮检测。
6. 命中时返回 `hasHazard=true` 和 labels。
7. 页面沿用现有在线命中分支，继续调用 `/ai/deep`。
8. 展示、语音、输入逻辑不变。

## 设备指引页数据流

设备指引页当前直接调用 `AiArSseService.identifyItemHazard()`。为实现平替，需要让该页面也走同一类 provider，而不是在页面中复制本地推理逻辑。

`standard` / `dataBackup`：

1. 页面选帧并构造检测请求。
2. HTTP provider 调用 `/ai/auto`。
3. 命中后展示提示态。
4. 自动或手动进入详情。
5. 详情仍调用 `/ai/device`。

`localTriger`：

1. 页面选帧并构造检测请求。
2. Local provider 直接使用 JPEG bytes 做本地触发。
3. 未命中时继续下一轮检测。
4. 命中后展示提示态。
5. 自动或手动进入详情。
6. 详情仍调用 `/ai/device`。

设备指引页不应新增独立展示或语音分支。

## 输出契约

本地 provider 输出需要与现有 `DetectCallback` 对齐：

未命中：

```kotlin
callback.onSuccess(handle, hasHazard = false, fullText = "", labels = emptyList())
```

命中：

```kotlin
callback.onSuccess(
    handle,
    hasHazard = true,
    fullText = localSummaryText,
    labels = detectedLabels,
)
```

`labels` 用于复用当前 cooldown 与日志逻辑。`fullText` 只作为兼容文本，不作为最终详情展示；最终详情仍由 `/ai/deep` 或 `/ai/device` 产生。

## 错误处理

1. 模型加载失败：作为一次检测失败回调，进入现有 retry/下一帧机制。
2. JPEG 解码失败：作为一次检测失败回调。
3. native 推理失败：作为一次检测失败回调。
4. cancel 后不再投递成功或失败回调。
5. 如果 cancel 发生在 native 推理途中，不强行中断 native 调用，只保证结果不再回到页面。
6. 后续 `/ai/deep` 或 `/ai/device` 失败时，沿用现有详情失败处理。

## 测试计划

### 单元测试

1. 配置测试：`localTriger` 解析为 `autoDetectProvider=LOCAL_TRIGGER`，`standard` 默认为 `HTTP`。
2. provider 选择测试：`LOCAL_TRIGGER` 使用 local gateway，`HTTP` 使用 SSE gateway。
3. base64 测试：HTTP gateway 会对 JPEG bytes 做 base64；Local gateway 不做 base64。
4. placeCode 测试：缺少 `placeCode` 时 Local gateway 返回 `hasHazard=false`，且不调用模型服务。
5. local 输出契约测试：命中返回 `hasHazard=true` 和 labels；未命中返回 `false` 和空 labels。
6. cancel 测试：cancel 后 local gateway 不投递回调。

### 构建验证

1. `./gradlew :app:testLocalTrigerDebugUnitTest`
2. `./gradlew :app:assembleLocalTrigerDebug`
3. `./gradlew :app:testStandardDebugUnitTest`
4. `./gradlew :app:assembleStandardDebug`

### 真机验证

1. 安装 `localTrigerDebug`。
2. 企业扫码获取有效 `placeCode` 后进入隐患识别页。
3. 观察日志确认不再请求 `/ai/auto`。
4. 本地小模型命中后，确认继续请求 `/ai/deep`。
5. 进入设备指引页，确认不再请求 `/ai/auto`。
6. 本地小模型命中后，确认继续请求 `/ai/device`。
7. 构造缺少 `placeCode` 的会话，确认不请求 `/ai/auto`，也不触发本地模型。
