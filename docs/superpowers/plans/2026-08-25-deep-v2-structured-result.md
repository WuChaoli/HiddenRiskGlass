# 自动 `/deep/v2` 结构化结果 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 仅将 `/auto` 面积阈值触发链路迁移到同步 JSON `/ai/deep/v2`，并在眼镜端以冻结的 3:4 图片、关联 bbox、可分页隐患卡片和确认保存流程呈现结构化结果。

**Architecture:** 保留 `/auto` 实时检测和旧 `/ai/deep` SSE 手动链路；新增独立的 V2 配置、协议、客户端、归一化器、展示状态机和结果 Overlay。Activity 只负责编排：实时 `/auto` 不停、V2 单飞、响应代际校验、页面切换以及复用现有上传与 `/sug_checks` 流程。

**Tech Stack:** Kotlin、Android View、自定义 Canvas、OkHttp 4.12、Gson、JUnit 4、MockWebServer、现有 `UnifiedInputSession` 与 `InspectionConfigRepository`。

**Spec:** `docs/superpowers/specs/2026-08-25-deep-v2-structured-result-design.md`

## Global Constraints

- 只改自动触发链路；手动“深度分析”、`HazardRecordActivity`、`DeviceGuideActivity` 继续调用旧 `/ai/deep` SSE。
- `localTriger` 仍完全离线，不得通过新增客户端发送任何 HTTP 请求。
- V2 请求在任意时刻最多一个；请求期间 `/auto` 继续请求并继续画实时框，但不得再次触发 V2。
- 只有匹配当前 Activity 生命周期、自动检测 epoch 和 V2 request id 的终态回调可以更新 UI。
- 当前用户未提交的 `docs/.gitignore` 不属于本变更，任何任务都不得暂存或修改它。
- 每个任务先运行指定测试观察失败，再实施最小代码使其通过；每次提交前检查 `git diff --check` 与 `git status --short`。

## File Structure Map

### New production files

- `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2Protocol.kt`：请求/响应 DTO、`inter` 兼容解析和 JSON 协议。
- `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2Client.kt`：同步 JSON HTTP 调用、取消句柄和回调分发。
- `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2Presentation.kt`：展示、上传和图片快照领域模型。
- `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2ResultNormalizer.kt`：关联、严重度、异常去重和页面顺序归一化。
- `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2PresentationStateMachine.kt`：失焦、bbox、分页、others、保存弹窗状态机。
- `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2HazardTextFormatter.kt`：详情字段格式化和基于测量行的分页规划。
- `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2OverlayGeometry.kt`：服务端 bbox 到 480×640 展示坐标映射及选中放大。
- `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2ResultOverlayView.kt`：结构化结果框、两行标签和选中动画。
- `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2AutoRequestState.kt`：V2 单飞和 request id/epoch/图片快照校验。
- `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2AutoCoordinator.kt`：将面积门禁、图片构建和 V2 单飞组合成可测试编排。
- `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2ResolvedHazardAdapter.kt`：归一化结果到现有 `ResolvedHazardContent` 的适配。
- `app/src/main/res/drawable/bg_deep_v2_hazard_card.xml`：高对比度详情卡背景。
- `app/src/main/res/drawable/bg_deep_v2_save_dialog.xml`：高对比度保存弹窗背景。

### Modified production files

- `app/src/main/java/com/rokid/glass/config/InspectionAppConfig.kt`
- `app/src/main/java/com/rokid/glass/config/InspectionConfigRepository.kt`
- `app/src/main/assets/inspection_config.base.jsonc`
- `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/AutoDeepTriggerDecider.kt`
- `app/src/main/res/layout/activity_ai_inspection.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/dimens.xml`
- `app/build.gradle`

### New tests

- `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ProtocolTest.kt`
- `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ClientTest.kt`
- `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ResultNormalizerTest.kt`
- `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2PresentationStateMachineTest.kt`
- `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2HazardTextFormatterTest.kt`
- `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2OverlayGeometryTest.kt`
- `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2AutoRequestStateTest.kt`
- `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2AutoCoordinatorTest.kt`
- `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ResolvedHazardAdapterTest.kt`

---

## Task 1: Add an Independent `/ai/deep/v2` Configuration

**Files:**

- Modify: `app/src/main/java/com/rokid/glass/config/InspectionAppConfig.kt:90`
- Modify: `app/src/main/java/com/rokid/glass/config/InspectionConfigRepository.kt:226`
- Modify: `app/src/main/assets/inspection_config.base.jsonc:123`
- Test: `app/src/test/java/com/rokid/glass/config/InspectionConfigRepositoryTest.kt`

- [ ] **Step 1: Add failing configuration tests**

Add assertions that the base configuration exposes a separate V2 URL and that an override changes only V2:

```kotlin
@Test
fun loadBaseConfig_exposesIndependentDeepV2Endpoint() {
    val config = InspectionConfigRepository.buildConfig(
        baseJsonc = """
            {
              "network": {
                "aiDeepApi": { "url": "http://example.test/ai/deep" },
                "aiDeepV2Api": { "url": "http://example.test/ai/deep/v2" }
              }
            }
        """.trimIndent(),
        overlayJsonc = null,
    )

    assertEquals("http://example.test/ai/deep", config.network.aiDeepApi.url)
    assertEquals("http://example.test/ai/deep/v2", config.network.aiDeepV2Api.url)
}

@Test
fun mergeNetworkOverride_overridesDeepV2WithoutChangingLegacyDeep() {
    val merged = InspectionConfigRepository.buildConfig(
        baseJsonc = """
            {
              "network": {
                "aiDeepApi": { "url": "http://example.test/ai/deep" },
                "aiDeepV2Api": { "url": "http://example.test/ai/deep/v2" }
              }
            }
        """.trimIndent(),
        overlayJsonc = """
            {
              "network": {
                "aiDeepV2Api": { "url": "http://127.0.0.1:18080/ai/deep/v2" }
              }
            }
        """.trimIndent(),
    )

    assertEquals("http://example.test/ai/deep", merged.network.aiDeepApi.url)
    assertEquals("http://127.0.0.1:18080/ai/deep/v2", merged.network.aiDeepV2Api.url)
}
```

- [ ] **Step 2: Run the targeted test and verify it fails**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.config.InspectionConfigRepositoryTest"
```

Expected: compilation fails because `aiDeepV2Api` does not exist.

- [ ] **Step 3: Add the V2 configuration fields and merge rule**

Add the field beside the legacy endpoint without changing `aiDeepApi`:

```kotlin
data class NetworkConfig(
    val aiDeepApi: AiArApiConfig = AiArApiConfig(
        url = "http://183.147.142.133:10010/ai/deep",
    ),
    val aiDeepV2Api: AiArApiConfig = AiArApiConfig(
        url = "http://183.147.142.133:10010/ai/deep/v2",
        detectTimeoutMs = 45_000L,
    ),
)

data class NetworkConfigOverride(
    val aiAutoApi: AiArApiConfigOverride? = null,
    val aiDeepApi: AiArApiConfigOverride? = null,
    val aiDeepV2Api: AiArApiConfigOverride? = null,
)
```

Merge it explicitly:

```kotlin
aiDeepV2Api = merge(base.aiDeepV2Api, override?.aiDeepV2Api),
```

Add the base JSON entry using the same host and timeout shape as the existing AI endpoints:

```jsonc
"aiDeepV2Api": {
  "url": "http://183.147.142.133:10010/ai/deep/v2",
  "connectTimeoutMs": 15000,
  "readTimeoutMs": 45000,
  "writeTimeoutMs": 30000,
  "detectTimeoutMs": 45000
}
```

- [ ] **Step 4: Re-run the targeted test and verify it passes**

Run the Step 2 command. Expected: all `InspectionConfigRepositoryTest` tests pass.

- [ ] **Step 5: Commit only Task 1 files**

```powershell
git add app/src/main/java/com/rokid/glass/config/InspectionAppConfig.kt app/src/main/java/com/rokid/glass/config/InspectionConfigRepository.kt app/src/main/assets/inspection_config.base.jsonc app/src/test/java/com/rokid/glass/config/InspectionConfigRepositoryTest.kt
git diff --cached --check
git commit -m "配置：新增 deep v2 独立接口"
```

---

## Task 2: Define and Validate the V2 JSON Protocol

**Files:**

- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2Protocol.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ProtocolTest.kt`

- [ ] **Step 1: Write failing request and response protocol tests**

Cover these exact cases:

```kotlin
@Test
fun buildRequest_containsOnlySupportedV2Fields() {
    val json = JsonParser.parseString(
        DeepV2Protocol.buildRequestJson(
            DeepV2Request(
                taskId = "task-001",
                scene = "PLACE-001",
                temp = 0.3,
                image = "base64-image",
            ),
        ),
    ).asJsonObject

    assertEquals(setOf("task_id", "scene", "temp", "image"), json.keySet())
    assertEquals("task-001", json["task_id"].asString)
    assertEquals(0.3, json["temp"].asDouble, 0.0)
}

@Test
fun parseResponse_acceptsNumericAndBooleanInter() {
    val response = DeepV2Protocol.parseResponse(responseWithMixedInter)

    assertEquals(DeepV2Inter.NumberValue(0.0), response.detections[0].inter)
    assertEquals(DeepV2Inter.BooleanValue(false), response.detections[1].inter)
}

@Test
fun parseResponse_preservesHazardsAndCheckItems() {
    val response = DeepV2Protocol.parseResponse(successResponse)

    assertEquals("det_001", response.hazards.single().labelId)
    assertEquals("ZJYJ_JX_XCY_009", response.hazards.single().hazardCode)
    assertEquals(2, response.checkItems.size)
}
```

Also add tests that reject a nonzero `code`, `type != "deep_v2"`, and non-object response JSON with `DeepV2ProtocolException`. A malformed detection bbox or missing detection `label_id` drops only that detection; a locally malformed hazard drops or degrades only that hazard so one bad item does not reject the otherwise valid response.

- [ ] **Step 2: Run the targeted test and verify it fails**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.DeepV2ProtocolTest"
```

Expected: compilation fails because the protocol types are absent.

- [ ] **Step 3: Implement typed protocol models**

Use English Kotlin identifiers and `@SerializedName` only at the wire boundary:

```kotlin
internal data class DeepV2Request(
    val taskId: String,
    val scene: String,
    val temp: Double,
    val image: String,
)

internal sealed interface DeepV2Inter {
    data class BooleanValue(val value: Boolean) : DeepV2Inter
    data class NumberValue(val value: Double) : DeepV2Inter
}

internal data class DeepV2Detection(
    val label: String,
    val bbox: List<Double>,
    val score: Double,
    val inter: DeepV2Inter?,
    val labelId: String,
    val sourceIndex: Int,
)

internal data class DeepV2Hazard(
    val labelId: String,
    val description: String,
    val level: String,
    val basis: String,
    val advice: String,
    val hazardCode: String,
    val sourceIndex: Int,
)

internal data class DeepV2Response(
    val code: Int,
    val message: String,
    val taskId: String,
    val type: String,
    val detections: List<DeepV2Detection>,
    val hazards: List<DeepV2Hazard>,
    val checkItems: List<JsonObject>,
    val timeSeconds: Double?,
)
```

Implement `DeepV2Protocol.buildRequestJson` and `parseResponse` using Gson tree parsing so `inter` can accept both primitives. Validate top-level `code == 0` and `type == "deep_v2"`; parse array entries independently, dropping malformed detections/hazards while retaining `sourceIndex` for deterministic ordering.

- [ ] **Step 4: Re-run the targeted test and verify it passes**

Run the Step 2 command. Expected: all protocol tests pass.

- [ ] **Step 5: Commit Task 2**

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2Protocol.kt app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ProtocolTest.kt
git diff --cached --check
git commit -m "协议：解析 deep v2 结构化响应"
```

---

## Task 3: Implement the Cancellable Synchronous-JSON Client

**Files:**

- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2Client.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ClientTest.kt`
- Modify: `app/build.gradle:150`

- [ ] **Step 1: Add MockWebServer as a test-only dependency**

```kotlin
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

- [ ] **Step 2: Write failing HTTP contract tests**

Test request method, path, media type, payload, successful parse, HTTP failure, protocol failure, and cancellation:

```kotlin
@Test
fun request_postsJsonToConfiguredV2Path() {
    server.enqueue(MockResponse().setResponseCode(200).setBody(successResponse))

    client.request(
        requestId = 41L,
        imageBytes = byteArrayOf(1, 2, 3),
        scene = "PLACE-001",
        callback = recordingCallback,
    )

    assertTrue(callbackLatch.await(2, TimeUnit.SECONDS))
    val request = server.takeRequest()
    assertEquals("POST", request.method)
    assertEquals("/ai/deep/v2", request.path)
    assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
    assertEquals("AQID", JsonParser.parseString(request.body.readUtf8()).asJsonObject["image"].asString)
}

@Test
fun cancel_preventsTerminalCallbackDelivery() {
    server.enqueue(MockResponse().setBodyDelay(1, TimeUnit.SECONDS).setBody(successResponse))

    val handle = client.request(41L, byteArrayOf(1), "PLACE-001", recordingCallback)
    handle.cancel()

    assertFalse(callbackLatch.await(1200, TimeUnit.MILLISECONDS))
}
```

- [ ] **Step 3: Run the targeted test and verify it fails**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.DeepV2ClientTest"
```

Expected: compilation fails because `DeepV2Client` is absent.

- [ ] **Step 4: Implement the client behind a small callback API**

Use the configured timeout values and inject deterministic test seams:

```kotlin
internal class DeepV2Client(
    private val apiConfig: AiArApiConfig,
    private val httpClient: OkHttpClient,
    private val taskIdFactory: () -> String,
    private val base64Encoder: (ByteArray) -> String,
    private val callbackExecutor: Executor,
) {
    interface Callback {
        fun onSuccess(requestId: Long, response: DeepV2Response)
        fun onFailure(requestId: Long, error: DeepV2ClientError)
    }

    interface RequestHandle {
        fun cancel()
    }

    fun request(
        requestId: Long,
        imageBytes: ByteArray,
        scene: String,
        callback: Callback,
    ): RequestHandle
}

internal sealed interface DeepV2ClientError {
    data class Http(val statusCode: Int) : DeepV2ClientError
    data class Network(val cause: IOException) : DeepV2ClientError
    data class Protocol(val cause: DeepV2ProtocolException) : DeepV2ClientError
}
```

Production construction supplies `android.util.Base64.NO_WRAP`, a main-thread executor, a unique task-id factory, and `HttpClientProvider`. The Activity coordinator supplies its own `requestId`, which the client returns unchanged in callbacks. The client uses ordinary `Call.enqueue`; it does not use `EventSource`, `stream`, or `text` fields. A cancelled handle suppresses both success and failure delivery.

- [ ] **Step 5: Re-run the targeted client and protocol tests**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.DeepV2ClientTest" --tests "com.rokid.glass.hiddenrisk.DeepV2ProtocolTest"
```

Expected: both test classes pass.

- [ ] **Step 6: Commit Task 3**

```powershell
git add app/build.gradle app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2Client.kt app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ClientTest.kt
git diff --cached --check
git commit -m "网络：新增 deep v2 同步响应客户端"
```

---

## Task 4: Normalize Detections, Hazards, Ordering, and Upload Data

**Files:**

- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2Presentation.kt`
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2ResultNormalizer.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ResultNormalizerTest.kt`

- [ ] **Step 1: Write failing normalization tests for every business rule**

Create focused fixtures and tests with these exact expectations:

```kotlin
@Test
fun normalize_hidesDetectionWithoutAssociatedHazard() {
    val result = normalizer.normalize(responseWithOneMatchedAndOneUnmatchedDetection)

    assertEquals(listOf("det_001"), result.targets.map { it.labelId })
}

@Test
fun normalize_usesHighestHazardLevelForTwoLineLabel() {
    val result = normalizer.normalize(responseWithGeneralAndMajorHazardsForSameLabel)

    assertEquals("重大隐患", result.targets.single().highestLevel)
    assertEquals(2, result.targets.single().hazards.size)
}

@Test
fun normalize_placesOthersAfterAllBboxTargets() {
    val result = normalizer.normalize(responseWithOthers)

    assertEquals(listOf("det_top", "det_bottom"), result.targets.map { it.labelId })
    assertEquals("others", result.others?.labelId)
}

@Test
fun normalize_duplicateHazardCode_prefersScoreThenBboxArea() {
    val result = normalizer.normalize(responseWithDuplicateHazardCodes)

    assertEquals("det_high_score", result.uploadHazards[0].labelId)
    assertEquals("det_equal_score_larger_bbox", result.uploadHazards[1].labelId)
}

@Test
fun normalize_duplicateLabelId_prefersScoreThenBboxAreaThenSourceOrder() {
    val result = normalizer.normalize(responseWithDuplicateLabelIdDetections)

    assertEquals(0.95, result.targets.single().detectionScore, 0.0)
    assertEquals(40_000f, result.targets.single().bbox.area, 0f)
}

@Test
fun normalize_ordersTargetsTopToBottomThenLeftToRight() {
    val result = normalizer.normalize(responseWithUnorderedDetections)

    assertEquals(listOf("top_left", "top_right", "bottom"), result.targets.map { it.labelId })
}
```

Also assert severity order `重大隐患 > 重点问题 > 一般隐患 > unknown`, distinct nonblank hazard codes are all retained, blank-code hazards remain displayable but are excluded from upload and `/sug_checks`, duplicate-code `others` loses to a bbox-linked hazard, `check_items` do not enter presentation/upload data, and the first upload hazard code follows normalized page order.

- [ ] **Step 2: Run the targeted test and verify it fails**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.DeepV2ResultNormalizerTest"
```

Expected: compilation fails because presentation and normalizer types are absent.

- [ ] **Step 3: Add immutable presentation models**

```kotlin
internal data class DeepV2ImagePayload(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
)

internal data class DeepV2BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val area: Float get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
}

internal data class DeepV2PresentationHazard(
    val labelId: String,
    val label: String,
    val description: String,
    val level: String,
    val basis: String,
    val advice: String,
    val hazardCode: String,
    val sourceIndex: Int,
)

internal data class DeepV2Target(
    val labelId: String,
    val label: String,
    val bbox: DeepV2BoundingBox,
    val detectionScore: Double,
    val detectionIndex: Int,
    val highestLevel: String,
    val hazards: List<DeepV2PresentationHazard>,
)

internal data class DeepV2GlobalHazards(
    val labelId: String = "others",
    val highestLevel: String,
    val hazards: List<DeepV2PresentationHazard>,
)

internal data class DeepV2Presentation(
    val targets: List<DeepV2Target>,
    val others: DeepV2GlobalHazards?,
    val uploadHazards: List<DeepV2PresentationHazard>,
    val suggestionHazardCode: String?,
)
```

Implement `DeepV2Severity.rank`, bbox validity/clamping, duplicate-`label_id` detection selection, label-id association, page ordering, and duplicate-code selection as pure functions. Stable final tie-breaker is source order.

- [ ] **Step 4: Re-run the targeted test and verify it passes**

Run the Step 2 command. Expected: all normalizer tests pass.

- [ ] **Step 5: Commit Task 4**

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2Presentation.kt app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2ResultNormalizer.kt app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ResultNormalizerTest.kt
git diff --cached --check
git commit -m "业务：归一化 deep v2 检测与隐患"
```

---

## Task 5: Build the Focus, Paging, Others, and Save-Dialog State Machine

**Files:**

- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2PresentationStateMachine.kt`
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2HazardTextFormatter.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2PresentationStateMachineTest.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2HazardTextFormatterTest.kt`

- [ ] **Step 1: Write failing navigation tests**

Define page counts `[2, 1, 2]`, where the last target represents `others`, and assert the full cycle:

```kotlin
@Test
fun forward_cyclesDefocusedTargetsPagesOthersAndBackToDefocused() {
    val machine = DeepV2PresentationStateMachine(intArrayOf(2, 1, 2))

    assertEquals(Defocused, machine.state)
    assertEquals(Focused(targetIndex = 0, pageIndex = 0), machine.forward().state)
    assertEquals(Focused(targetIndex = 0, pageIndex = 1), machine.forward().state)
    assertEquals(Focused(targetIndex = 1, pageIndex = 0), machine.forward().state)
    assertEquals(Focused(targetIndex = 2, pageIndex = 0), machine.forward().state)
    assertEquals(Focused(targetIndex = 2, pageIndex = 1), machine.forward().state)
    assertEquals(Defocused, machine.forward().state)
}

@Test
fun backwardEnteringAnyTarget_alwaysStartsAtFirstPage() {
    val machine = DeepV2PresentationStateMachine(intArrayOf(2, 1, 2))

    assertEquals(Focused(targetIndex = 2, pageIndex = 0), machine.backward().state)
    assertEquals(Focused(targetIndex = 1, pageIndex = 0), machine.backward().state)
    assertEquals(Focused(targetIndex = 0, pageIndex = 0), machine.backward().state)
    assertEquals(Defocused, machine.backward().state)
}

@Test
fun confirmWhileFocused_behavesLikeForward() {
    val machine = DeepV2PresentationStateMachine(intArrayOf(1))
    machine.forward()

    assertEquals(Defocused, machine.confirm().state)
}

@Test
fun confirmWhileDefocused_opensDialogWithConfirmSelected() {
    val machine = DeepV2PresentationStateMachine(intArrayOf(1))

    assertEquals(SaveDialog(SaveChoice.CONFIRM), machine.confirm().state)
}
```

Add tests for dialog FRONT/BEHIND toggling, selected confirm producing `SubmitSave`, selected cancel producing `DiscardResult`, voice confirm/cancel directly choosing their action, zero bbox with only `others`, and no targets retaining `Defocused` with effect `None` instead of opening an empty result.

- [ ] **Step 2: Write failing formatter and measured-line pagination tests**

```kotlin
@Test
fun format_includesFourFieldsAndOmitsHazardCode() {
    val text = DeepV2HazardTextFormatter.format(hazard)

    assertTrue(text.contains("隐患描述：描述"))
    assertTrue(text.contains("隐患等级：一般隐患"))
    assertTrue(text.contains("主要依据：依据"))
    assertTrue(text.contains("整改建议：建议"))
    assertFalse(text.contains("ZJYJ_JX_XCY_009"))
}

@Test
fun formatGroup_keepsAllHazardsInSourceOrderWithVisibleSeparator() {
    val text = DeepV2HazardTextFormatter.formatGroup(listOf(firstHazard, secondHazard))

    assertTrue(text.indexOf("隐患 1") < text.indexOf("隐患 2"))
    assertTrue(text.contains(firstHazard.description))
    assertTrue(text.contains(secondHazard.description))
}

@Test
fun pagePlanner_usesMeasuredLineBottomsWithoutDroppingText() {
    val lines = listOf(
        MeasuredTextLine(0, 5, 20),
        MeasuredTextLine(5, 10, 40),
        MeasuredTextLine(10, 15, 60),
    )

    assertEquals(listOf(0..1, 2..2), DeepV2MeasuredPagePlanner.plan(lines, viewportHeightPx = 40))
}
```

- [ ] **Step 3: Run both targeted tests and verify they fail**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.DeepV2PresentationStateMachineTest" --tests "com.rokid.glass.hiddenrisk.DeepV2HazardTextFormatterTest"
```

Expected: compilation fails because state and formatter types are absent.

- [ ] **Step 4: Implement the pure state machine and text helpers**

Use explicit states and effects:

```kotlin
internal sealed interface DeepV2NavigationState {
    data object Defocused : DeepV2NavigationState
    data class Focused(val targetIndex: Int, val pageIndex: Int) : DeepV2NavigationState
    data class SaveDialog(val selected: SaveChoice) : DeepV2NavigationState
    data object Submitting : DeepV2NavigationState
}

internal enum class SaveChoice { CONFIRM, CANCEL }

internal sealed interface DeepV2NavigationEffect {
    data object None : DeepV2NavigationEffect
    data object SubmitSave : DeepV2NavigationEffect
    data object DiscardResult : DeepV2NavigationEffect
}

internal data class DeepV2Transition(
    val state: DeepV2NavigationState,
    val effect: DeepV2NavigationEffect = DeepV2NavigationEffect.None,
)
```

The state machine exposes `forward()`, `backward()`, `confirm()`, `selectPreviousDialogChoice()`, `selectNextDialogChoice()`, `voiceConfirm()`, and `voiceCancel()`, each returning `DeepV2Transition`. Target transitions always set `pageIndex = 0`; only movement within the same target changes page.

`DeepV2HazardTextFormatter.formatGroup` concatenates every hazard associated with the current bbox in normalized source order and inserts a visible item separator; `others` uses the same rule. `DeepV2MeasuredPagePlanner` takes line start/end/bottom values produced later from Android `Layout`, keeping pagination policy independently unit-testable.

- [ ] **Step 5: Re-run the tests and verify they pass**

Run the Step 3 command. Expected: both test classes pass.

- [ ] **Step 6: Commit Task 5**

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2PresentationStateMachine.kt app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2HazardTextFormatter.kt app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2PresentationStateMachineTest.kt app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2HazardTextFormatterTest.kt
git diff --cached --check
git commit -m "交互：新增结构化结果焦点与分页状态机"
```

---

## Task 6: Render the Frozen Image, Hazard Boxes, Labels, and Fixed Bottom Card

**Files:**

- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2OverlayGeometry.kt`
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2ResultOverlayView.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2OverlayGeometryTest.kt`
- Modify: `app/src/main/res/layout/activity_ai_inspection.xml`
- Modify: `app/src/main/res/values/dimens.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/drawable/bg_deep_v2_hazard_card.xml`
- Create: `app/src/main/res/drawable/bg_deep_v2_save_dialog.xml`

- [ ] **Step 1: Write failing geometry tests**

```kotlin
@Test
fun mapBbox_usesFrozenImageDimensionsAndFullScreenDestination() {
    val mapped = DeepV2OverlayGeometry.map(
        bbox = DeepV2BoundingBox(30f, 300f, 1200f, 1430f),
        sourceWidth = 1512,
        sourceHeight = 2016,
        destinationWidth = 480,
        destinationHeight = 640,
    )

    assertEquals(9.52f, mapped.left, 0.02f)
    assertEquals(95.24f, mapped.top, 0.02f)
    assertEquals(380.95f, mapped.right, 0.02f)
    assertEquals(453.97f, mapped.bottom, 0.02f)
}

@Test
fun selectedRect_expandsTenPercentAroundCenter() {
    val selected = DeepV2OverlayGeometry.expandAroundCenter(RectFModel(100f, 100f, 200f, 300f), 1.10f)

    assertEquals(RectFModel(95f, 90f, 205f, 310f), selected)
}
```

Also cover clipping to display bounds and rejecting zero-area/nonfinite boxes.

- [ ] **Step 2: Run the targeted test and verify it fails**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.DeepV2OverlayGeometryTest"
```

Expected: compilation fails because geometry types are absent.

- [ ] **Step 3: Implement geometry and verify tests pass**

Use direct per-axis scaling because the displayed bitmap is the exact 3:4 request image filling the 480×640 result layer:

```kotlin
val scaleX = destinationWidth.toFloat() / sourceWidth
val scaleY = destinationHeight.toFloat() / sourceHeight
return RectFModel(
    left = bbox.left * scaleX,
    top = bbox.top * scaleY,
    right = bbox.right * scaleX,
    bottom = bbox.bottom * scaleY,
).clamp(destinationWidth.toFloat(), destinationHeight.toFloat())
```

Run the Step 2 command. Expected: all geometry tests pass.

- [ ] **Step 4: Add the dedicated result layer to the Activity layout**

Add a sibling page container, not a replacement for the live `/auto` overlay:

```xml
<FrameLayout
    android:id="@+id/layoutDeepV2Result"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:visibility="gone">

    <ImageView
        android:id="@+id/ivDeepV2ResultImage"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:scaleType="fitXY" />

    <com.rokid.glass.hiddenrisk.DeepV2ResultOverlayView
        android:id="@+id/viewDeepV2ResultOverlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical">

        <View
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1" />

        <LinearLayout
            android:id="@+id/layoutDeepV2HazardCard"
            android:layout_width="match_parent"
            android:layout_height="@dimen/deep_v2_hazard_card_height"
            android:layout_marginHorizontal="@dimen/deep_v2_hazard_card_horizontal_margin"
            android:orientation="vertical"
            android:visibility="gone" />

        <com.rokid.glass.component.GlassStatusBar
            android:id="@+id/statusBarDeepV2"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:paddingHorizontal="@dimen/inspection_status_bar_padding_horizontal"
            android:paddingBottom="@dimen/inspection_status_bar_padding_bottom" />
    </LinearLayout>

    <LinearLayout
        android:id="@+id/layoutDeepV2SaveDialog"
        android:layout_width="280dp"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:orientation="vertical"
        android:visibility="gone" />

</FrameLayout>
```

Inside `layoutDeepV2HazardCard`, add `tvDeepV2HazardTitle`, `tvDeepV2HazardText`, and `tvDeepV2PageIndicator`. Inside `layoutDeepV2SaveDialog`, add the confirmation prompt plus `tvDeepV2SaveConfirm` and `tvDeepV2SaveCancel`, reusing `glass_card_outline_selected`/`glass_card_outline` for selection. Use `8dp` horizontal card margin and `132dp` card height. Define `bg_deep_v2_hazard_card.xml` with `#E6000000` and `bg_deep_v2_save_dialog.xml` with `#F21A1A1A`, then use white body text and severity color accents so text never overlaps a same-color background. Do not include the existing right guide or bottom hint in this page.

- [ ] **Step 5: Implement the custom result Overlay**

Expose a render model rather than passing protocol DTOs into the View:

```kotlin
internal data class DeepV2OverlayBox(
    val labelId: String,
    val label: String,
    val highestLevel: String,
    val rect: RectFModel,
)

internal fun setBoxes(boxes: List<DeepV2OverlayBox>)
internal fun setSelectedLabelId(labelId: String?, animate: Boolean)
```

Draw only supplied boxes. Inside each bbox top-left, draw exactly two lines: `label` and `highestLevel`. Never draw score or `label_id`. Unselected stroke is `1dp`; selected stroke animates to `3dp` while the rectangle expands around its center to scale `1.10`. Use a 220 ms animator and preserve the previous/next interpolation so one shrinks while the other expands.

- [ ] **Step 6: Compile resources and production View code**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:compileStandardDebugKotlin
```

Expected: resource binding and Kotlin compilation succeed.

- [ ] **Step 7: Commit Task 6**

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2OverlayGeometry.kt app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2ResultOverlayView.kt app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2OverlayGeometryTest.kt app/src/main/res/layout/activity_ai_inspection.xml app/src/main/res/drawable/bg_deep_v2_hazard_card.xml app/src/main/res/drawable/bg_deep_v2_save_dialog.xml app/src/main/res/values/dimens.xml app/src/main/res/values/strings.xml
git diff --cached --check
git commit -m "界面：新增 deep v2 结构化结果画布"
```

---

## Task 7: Add the V2 Single-Flight Gate and Migrate Only the Automatic Trigger

**Files:**

- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2AutoRequestState.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2AutoRequestStateTest.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt:420`

- [ ] **Step 1: Write failing request-state tests**

```kotlin
@Test
fun begin_allowsOnlyOneActiveRequest() {
    val state = DeepV2AutoRequestState()

    val firstId = state.begin(epoch = 7L)
    val secondId = state.begin(epoch = 7L)

    assertNotNull(firstId)
    assertNull(secondId)
}

@Test
fun acceptTerminal_requiresMatchingRequestAndEpochAndReturnsFrozenImage() {
    val state = DeepV2AutoRequestState()
    val id = requireNotNull(state.begin(epoch = 7L))
    val image = DeepV2ImagePayload(byteArrayOf(1), 1512, 2016)
    assertTrue(state.attachImage(id, 7L, image))

    assertNull(state.acceptTerminal(id, 6L))
    assertNull(state.acceptTerminal(id + 1L, 7L))
    assertEquals(image, state.acceptTerminal(id, 7L))
    assertFalse(state.isActive)
}

@Test
fun failureOrCancel_releasesGateForNextAutoResponse() {
    val state = DeepV2AutoRequestState()
    val first = requireNotNull(state.begin(7L))

    assertTrue(state.fail(first, 7L))
    assertNotNull(state.begin(7L))
}
```

Also test attach-image rejection after cancel and stale terminal callbacks after a newer epoch.

- [ ] **Step 2: Run the targeted test and verify it fails**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.DeepV2AutoRequestStateTest"
```

Expected: compilation fails because request state is absent.

- [ ] **Step 3: Implement the pure single-flight state**

```kotlin
internal class DeepV2AutoRequestState {
    private data class Active(
        val requestId: Long,
        val epoch: Long,
        val image: DeepV2ImagePayload?,
    )

    val isActive: Boolean
    fun begin(epoch: Long): Long?
    fun attachImage(requestId: Long, epoch: Long, image: DeepV2ImagePayload): Boolean
    fun acceptTerminal(requestId: Long, epoch: Long): DeepV2ImagePayload?
    fun fail(requestId: Long, epoch: Long): Boolean
    fun cancel()
}
```

Generate monotonically increasing ids internally and never reuse them.

- [ ] **Step 4: Re-run the state tests and verify they pass**

Run the Step 2 command. Expected: all request-state tests pass.

- [ ] **Step 5: Replace only the old full-frame auto-to-deep branch in Activity**

In `AiInspectionActivity`:

1. Add `DeepV2Client`, `DeepV2AutoRequestState`, current `RequestHandle`, result image/presentation, and Activity lifecycle generation fields.
2. Change `encodeAlignedDeepJpeg` to return `DeepV2ImagePayload` with the actual crop width/height and JPEG bytes.
3. Keep every `/auto` response calling `autoDetectionOverlay.showDetections(...)` even while `deepV2RequestState.isActive`.
4. After `AutoDeepTriggerDecider.shouldTrigger(...)`, call `begin(epoch)`; a `null` id means “continue auto drawing, do not start another V2”.
5. Send the aligned payload and current `placeCode` as `scene` to `DeepV2Client`; do not call `onlineHazardDetectionService.requestDeepAnalysis` from this automatic branch.
6. On valid success, normalize first. If no target and no `others`, release the gate, show a short “未发现隐患” prompt, and let the already-running `/auto` loop continue.
7. On valid nonempty success, stop the auto loop, freeze the exact request image, and enter `PageState.STRUCTURED_RESULT`.
8. On HTTP/protocol/normalization failure, release the gate, log the reason, show a short nonblocking prompt, and keep `/auto` running without falling back to old `/deep`.
9. On pause, destroy, manual exit, or a new auto epoch, cancel the V2 handle and clear request state. Every callback checks Activity generation, epoch, and request id before touching UI.

Delete only the automatic full-frame SSE coupling:

```kotlin
private var fullFrameAutoDeepRequestId: Long? = null
```

and the corresponding special branches in `handleOnlineDetailSuccess`, `handleOnlineDetailChunk`, `handleOnlineDetailFailure`, and `handleFullFrameDeepSuccess`. Keep the ordinary old-SSE callback paths unchanged for manual deep analysis and other Activities.

- [ ] **Step 6: Prove the old and new routes are separated**

Run:

```powershell
rg -n "requestDeepAnalysis|aiDeepV2Api|DeepV2Client|fullFrameAutoDeepRequestId" app/src/main/java/com/rokid/glass/hiddenrisk app/src/main/java/com/rokid/glass/config
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:compileStandardDebugKotlin
```

Expected: `AiInspectionActivity` automatic trigger references `DeepV2Client`; legacy manual paths still reference `requestDeepAnalysis`; `fullFrameAutoDeepRequestId` has no matches; Kotlin compilation succeeds.

- [ ] **Step 7: Commit Task 7**

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2AutoRequestState.kt app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2AutoRequestStateTest.kt app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt
git diff --cached --check
git commit -m "链路：自动触发迁移至 deep v2"
```

---

## Task 8: Integrate Result Navigation, Paging, Animation, and Save Confirmation

**Files:**

- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2ResolvedHazardAdapter.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ResolvedHazardAdapterTest.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt:2786`

- [ ] **Step 1: Write failing save-adapter tests**

```kotlin
@Test
fun adapt_preservesAllDistinctValidHazardsAndUsesFirstValidPageHazardAsPrimary() {
    val content = DeepV2ResolvedHazardAdapter.adapt(presentation, frozenImage)

    assertEquals(3, content.hazards.size)
    assertEquals(presentation.suggestionHazardCode, content.primaryHazard()?.hidNum)
    assertArrayEquals(frozenImage.jpegBytes, content.jpegBytes)
}

@Test
fun adapt_preservesUnknownLevelLabelInsteadOfDroppingIt() {
    val content = DeepV2ResolvedHazardAdapter.adapt(presentationWithUnknownLevel, frozenImage)

    assertEquals("自定义等级", content.primaryHazard()?.hidLevel)
}
```

Also assert description, basis, advice, code and `HazardSource.ONLINE` mapping, plus duplicate hazard codes not reappearing after normalization.

- [ ] **Step 2: Run the targeted test and verify it fails**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.DeepV2ResolvedHazardAdapterTest"
```

Expected: compilation fails because the adapter is absent.

- [ ] **Step 3: Implement the adapter and verify the test passes**

```kotlin
internal object DeepV2ResolvedHazardAdapter {
    fun adapt(
        presentation: DeepV2Presentation,
        image: DeepV2ImagePayload,
    ): ResolvedHazardContent {
        val items = presentation.uploadHazards.map(::toResolvedHazardItem)
        require(items.isNotEmpty())
        val primary = items.first()
        return ResolvedHazardContent(
            source = HazardSource.ONLINE,
            description = primary.description,
            advice = primary.advice,
            uploadAdvice = primary.uploadAdvice,
            hidLevel = primary.hidLevel,
            hidNum = primary.hidNum,
            lawBasis = primary.lawBasis,
            displayTitle = primary.displayTitle,
            jpegBytes = image.jpegBytes,
            hazards = items,
            remoteSaveAllowed = true,
        )
    }
}
```

Use the existing level-code converter when known; otherwise retain the raw level text. Run the Step 2 command. Expected: all adapter tests pass.

- [ ] **Step 4: Add `STRUCTURED_RESULT` page rendering**

Extend `PageState` and `showPage` so this page alone shows:

- the exact frozen V2 request bitmap with `fitXY` on 480×640;
- `DeepV2ResultOverlayView` boxes only for associated hazards;
- the bottom status bar;
- no right-side inspection guide and no bottom hint.

On initial entry, create `DeepV2PresentationStateMachine` in `Defocused`, hide the detail card, and render all eligible boxes as thin/unselected. Decode the JPEG once for display and recycle/clear it when returning to detection or destroying the Activity.
Register `statusBarDeepV2` with the same status-data updates used by `statusBarDetecting` and `statusBarStream`, so only its visibility changes with the page.

- [ ] **Step 5: Bind touch and voice navigation through `UnifiedInputSession`**

Add state-scoped action specs with these mappings:

| Result state | FRONT / “下一个” | BEHIND / “上一个” | CLICK / “继续” / “确认” |
|---|---|---|---|
| Defocused | first target page 1 | last target page 1 | open save dialog |
| Focused | next page/target | previous page/target | same as forward |
| Save dialog | select previous choice | select next choice | execute selected choice |

Voice “确认” in the dialog submits immediately; voice “取消” discards immediately. The dialog starts with `SaveChoice.CONFIRM` selected. No new binding is added for Android BACK or DOUBLE_CLICK.

- [ ] **Step 6: Connect state changes to the selected-box and fixed-card animation**

For a target change:

1. fade the card out;
2. animate the old box to thin/normal and the new box to thick/110% over 220 ms;
3. after the box transition, render page 1 and fade the card in.

For a page change within the same target, keep the box selected and replace only card text/page indicator. Build pages after `TextView.layout` is available using measured line starts/ends/bottoms and `DeepV2MeasuredPagePlanner`. The card remains fixed at the bottom. A bbox card title shows its detection label and highest level; `others` selects no bbox, uses title “全局隐患” plus its highest level, and appears after every bbox target.

- [ ] **Step 7: Reuse the current upload and `/sug_checks` flow**

On dialog confirm:

```kotlin
activeHazardContent = DeepV2ResolvedHazardAdapter.adapt(
    requireNotNull(deepV2Presentation),
    requireNotNull(deepV2ResultImage),
)
localResultStage = LocalResultStage.DESCRIPTION
showPage(PageState.STREAM_RESPONSE)
submitLocalHazardAndShowAdvice()
```

This preserves the existing upload-all-valid-hazards behavior and makes the first normalized valid hazard code the `/sug_checks` code. If `uploadHazards` is empty, do not construct content or issue upload/`sug_checks`; show a short “无可保存隐患” prompt and return to a fresh `/auto` epoch. On cancel, clear the bitmap/presentation/navigation state and call the existing detection reset/start path so `/auto` begins a fresh epoch. Disable confirm while upload submission is in progress to prevent duplicates.

- [ ] **Step 8: Run focused and complete unit tests**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.DeepV2PresentationStateMachineTest" --tests "com.rokid.glass.hiddenrisk.DeepV2HazardTextFormatterTest" --tests "com.rokid.glass.hiddenrisk.DeepV2ResolvedHazardAdapterTest"
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest
```

Expected: focused tests and full standard unit suite pass.

- [ ] **Step 9: Commit Task 8**

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2ResolvedHazardAdapter.kt app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ResolvedHazardAdapterTest.kt app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt
git diff --cached --check
git commit -m "交互：接入 deep v2 浏览与保存流程"
```

---

## Task 9: Add Regression Tests for Automatic-Only Routing and Lifecycle Safety

**Files:**

- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/AutoDeepTriggerDeciderTest.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2AutoCoordinatorTest.kt`
- Create or modify: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2AutoCoordinator.kt`

- [ ] **Step 1: Extract the Activity-independent coordinator seam**

Define a small coordinator used by `AiInspectionActivity` so concurrency can be tested without Activity/Robolectric:

```kotlin
internal class DeepV2AutoCoordinator(
    private val requestState: DeepV2AutoRequestState,
) {
    fun onAutoResponse(
        epoch: Long,
        shouldTrigger: Boolean,
        buildImage: () -> DeepV2ImagePayload?,
        startRequest: (requestId: Long, image: DeepV2ImagePayload) -> Unit,
    ): DeepV2AutoDecision

    fun onSuccess(requestId: Long, epoch: Long): DeepV2ImagePayload?
    fun onFailure(requestId: Long, epoch: Long): Boolean
    fun cancel()
}
```

Activity remains responsible for rendering every `/auto` response before invoking this coordinator.

- [ ] **Step 2: Write failing orchestration regression tests**

```kotlin
@Test
fun repeatedQualifyingAutoResponses_startOnlyOneV2Request() {
    coordinator.onAutoResponse(1L, true, ::image, requests::add)
    coordinator.onAutoResponse(1L, true, ::image, requests::add)

    assertEquals(1, requests.size)
}

@Test
fun failedV2_releasesGateAndNextAutoResponseCanRetry() {
    val first = startQualifyingResponse()
    assertTrue(coordinator.onFailure(first.requestId, 1L))

    startQualifyingResponse()
    assertEquals(2, requests.size)
}

@Test
fun staleSuccessAfterEpochChange_isIgnored() {
    val old = startQualifyingResponse(epoch = 1L)
    coordinator.cancel()
    coordinator.onAutoResponse(2L, true, ::image, requests::add)

    assertNull(coordinator.onSuccess(old.requestId, 1L))
}
```

Retain and extend `AutoDeepTriggerDeciderTest` to prove area equal to or below `1/8` does not trigger and area strictly above `1/8` triggers. Keep the existing `InspectionNetworkAccessPolicyTest` as the proof that `localTriger`/`OFFLINE_LOCAL` cannot send an online automatic request, and ensure Activity reaches the coordinator only after that existing policy gate.

- [ ] **Step 3: Run targeted tests and verify the new coordinator test fails**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.DeepV2AutoCoordinatorTest" --tests "com.rokid.glass.hiddenrisk.AutoDeepTriggerDeciderTest"
```

Expected: coordinator compilation fails before implementation; the existing decider regression remains green.

- [ ] **Step 4: Implement the coordinator and update Activity to use it**

Keep the coordinator free of Android classes. Ensure a null image releases the just-acquired gate and reports `ImageEncodingFailed`; a nonqualifying response reports `ContinueAuto`; and an already active request reports `V2AlreadyActive` without invoking the image builder. Change `AutoDeepTriggerDecider` from `>= minimumArea` to `> minimumArea` so the implemented threshold matches the approved contract.

- [ ] **Step 5: Re-run targeted and full tests**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.DeepV2AutoCoordinatorTest" --tests "com.rokid.glass.hiddenrisk.AutoDeepTriggerDeciderTest"
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.network.InspectionNetworkAccessPolicyTest"
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest
```

Expected: all tests pass.

- [ ] **Step 6: Commit Task 9**

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2AutoCoordinator.kt app/src/main/java/com/rokid/glass/hiddenrisk/AutoDeepTriggerDecider.kt app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2AutoCoordinatorTest.kt app/src/test/java/com/rokid/glass/hiddenrisk/AutoDeepTriggerDeciderTest.kt
git diff --cached --check
git commit -m "测试：覆盖 deep v2 单飞与代际隔离"
```

---

## Task 10: Update Architecture Docs and Perform Full Build/Device Verification

**Files:**

- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/README.md`
- Modify: `docs/CODEMAPS.md`
- Modify: `docs/APIs/README.md`

- [ ] **Step 1: Update the three authoritative documentation views**

Document these facts consistently:

- automatic `/auto` threshold route calls `/ai/deep/v2` and consumes one structured JSON response;
- manual deep analysis and the two existing secondary Activities remain on `/ai/deep` SSE;
- V2 single-flight does not pause `/auto` drawing;
- successful nonempty V2 freezes the request image and enters structured result state;
- empty/failure V2 keeps automatic detection running;
- `localTriger` remains excluded from both online routes.

- [ ] **Step 2: Run repository-level static and unit verification**

```powershell
git diff --check
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest
powershell -ExecutionPolicy Bypass -File scripts/android/build-debug.ps1
powershell -ExecutionPolicy Bypass -File scripts/android/verify-apk.ps1 app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

Expected: no whitespace errors; unit suite passes; standard debug APK builds and verifies.

- [ ] **Step 3: Run route-separation and placeholder scans**

```powershell
rg -n "aiDeepV2Api|/ai/deep/v2|DeepV2Client" app/src/main docs
rg -n "requestDeepAnalysis|/ai/deep" app/src/main/java/com/rokid/glass/hiddenrisk docs
rg -n "TO[D]O|TB[D]|PLACEHOLD[E]R" app/src/main/java/com/rokid/glass/hiddenrisk -g "DeepV2*"
rg -n "TO[D]O|TB[D]|PLACEHOLD[E]R" docs/superpowers/specs/2026-08-25-deep-v2-structured-result-design.md
git status --short
```

Expected: V2 appears only in the new automatic route/config/docs; legacy deep references remain for nonautomatic routes; no new placeholders; `docs/.gitignore` remains the only unrelated user change.

- [ ] **Step 4: Install on the Rokid device**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/doctor.ps1 -Device
powershell -ExecutionPolicy Bypass -File scripts/android/install-debug.ps1 -Serial $env:ROKID_SERIAL
```

Expected: configured Rokid device is authorized and the APK installs successfully.

- [ ] **Step 5: Perform the real-device acceptance matrix**

Verify and record pass/fail for each case:

1. `/auto` area `<= 1/8` never calls V2.
2. First area `> 1/8` calls one V2; later `/auto` responses keep moving the live boxes but do not call another V2.
3. Successful V2 shows the exact 3:4 request image full screen, status bar only, eligible boxes, two-line labels, no score/id.
4. Initial state is defocused; forward/backward cycles boxes, text pages, `others`, and defocused exactly as specified; every newly entered target starts on page 1.
5. Selection animation uses thin→thick and center +10%; card fades around target transitions; fixed bottom card has readable contrast and wraps text.
6. Unmatched detections are hidden; multi-hazard label shows the highest level; `others` has no selected box and appears last.
7. Defocused confirm opens default-confirm dialog; cancel restarts `/auto`; confirm uploads all normalized hazards and enters `/sug_checks` using the first normalized code.
8. V2 timeout/HTTP error/malformed JSON shows a short prompt, starts no legacy deep fallback, and keeps `/auto` running.
9. Leaving/re-entering the page while V2 is pending produces no stale result UI.
10. Manual deep analysis still streams through old `/ai/deep`; `localTriger` makes no online request.

Capture supporting log lines with:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s $env:ROKID_SERIAL logcat -c
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s $env:ROKID_SERIAL logcat -v time | Select-String "AiInspection|DeepV2|/ai/deep|/ai/deep/v2|sug_checks"
```

- [ ] **Step 6: Commit documentation after verification**

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/README.md docs/CODEMAPS.md docs/APIs/README.md
git diff --cached --check
git commit -m "文档：更新自动 deep v2 调用链"
git status --short
```

Expected: the task files are committed; unrelated `docs/.gitignore` remains unstaged.

---

## Completion Criteria

- All ten task commits exist and contain only their declared files.
- `:app:testStandardDebugUnitTest`, debug build, APK verification, and real-device acceptance matrix pass.
- Only automatic area-threshold triggering reaches `/ai/deep/v2`; every legacy manual SSE path remains functional.
- `/auto` stays live during V2 and the single-flight gate blocks duplicate V2 calls.
- Result UI and input behavior match the approved defocused/bbox/page/others/dialog state model.
- Save confirmation reuses the current multi-hazard upload and `/sug_checks` workflow; cancellation cleanly restarts `/auto`.
- No stale callback, temporary bitmap, active Call, or animation survives page exit/destruction.
