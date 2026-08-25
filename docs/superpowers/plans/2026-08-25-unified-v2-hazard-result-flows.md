# Unified V2 Hazard Result Flows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将手动深度分析、隐患拍照、环境检测和无 `placeCode` 的工贸分析迁移到对应 V2 接口，并复用当前冻结底图、bbox、hazard 分页和确认保存 UI，同时保持各入口原有后续业务。

**Architecture:** 扩展现有 `DeepV2Client` 和协议模型，使三个 V2 endpoint 共用一个结构化客户端；新增纯 Kotlin 的 endpoint 路由、来源/保存策略和结果会话模型。`AiInspectionActivity` 编排自动、手动、环境入口，`HazardRecordActivity` 保留独立相机与返回流程，但两者复用现有 V2 展示模型、导航状态机和 Overlay。

**Tech Stack:** Kotlin、Android View/XML、OkHttp、Gson、JUnit4、MockWebServer、Rokid Glass 480×640 / 240 dpi

**Spec:** `docs/superpowers/specs/2026-08-25-unified-v2-hazard-result-flows-design.md`

## Global Constraints

- `localTriger` 保持完全离线，不得调用任一 V2 endpoint 或 `/ai/sug_checks`。
- 所有 endpoint URL 必须来自 `InspectionConfigRepository`，禁止拼接或硬编码业务路由。
- 请求 JPEG 必须与结果页冻结底图为同一字节内容；bbox 仅相对该图片投影。
- `/general_deep/v2` 和 `/gm/v2` 不生成虚拟 bbox，翻页不执行 bbox 动画。
- 每条 hazard 固定一页；达到文字框最大高度后截断，不拆分到第二页。
- 自动、手动、环境执行上传和 `/ai/sug_checks`；隐患拍照只上传。
- 空编号 hazard 可展示但不可上传，也不可作为 `/ai/sug_checks` 参数。
- V2 失败不得回退旧 `/deep`、`/general_deep` 或 `/gm` SSE。
- 保留旧 endpoint 配置字段，但本计划目标入口完成后不得调用旧端点。
- 不记录 Base64 图片、授权令牌或完整敏感响应。

---

### Task 1: Add V2 endpoint configuration and deterministic routing

**Files:**
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/StructuredHazardV2Endpoint.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/StructuredHazardV2EndpointTest.kt`
- Modify: `app/src/main/java/com/rokid/glass/config/InspectionAppConfig.kt`
- Modify: `app/src/main/java/com/rokid/glass/config/InspectionConfigRepository.kt`
- Modify: `app/src/main/assets/inspection_config.base.jsonc`
- Modify: `app/src/test/java/com/rokid/glass/config/InspectionConfigRepositoryTest.kt`

**Interfaces:**
- Produces: `enum class StructuredHazardV2Endpoint { DEEP_V2, GENERAL_DEEP_V2, GM_V2 }`
- Produces: `StructuredHazardV2Endpoint.expectedResponseType: String`
- Produces: `StructuredHazardV2Endpoint.supportsScene: Boolean`
- Produces: `StructuredHazardV2EndpointRouter.forItem(placeCode: String?): StructuredHazardV2Route`
- Produces: `StructuredHazardV2EndpointRouter.forScene(placeCode: String?): StructuredHazardV2Route?`
- Produces: `StructuredHazardV2Route(endpoint, scene)`

- [ ] **Step 1: Write failing endpoint routing tests**

```kotlin
@Test fun itemWithSceneUsesDeepV2() {
    assertEquals(
        StructuredHazardV2Route(StructuredHazardV2Endpoint.DEEP_V2, "SCENE-1"),
        StructuredHazardV2EndpointRouter.forItem(" SCENE-1 "),
    )
}

@Test fun itemWithoutSceneUsesGmV2() {
    assertEquals(
        StructuredHazardV2Route(StructuredHazardV2Endpoint.GM_V2, null),
        StructuredHazardV2EndpointRouter.forItem(" "),
    )
}

@Test fun sceneWithoutPlaceCodeIsSkipped() {
    assertNull(StructuredHazardV2EndpointRouter.forScene(null))
}
```

- [ ] **Step 2: Add failing configuration merge assertions**

Assert base URLs are exactly `http://183.147.142.133:10010/ai/general_deep/v2` and
`http://183.147.142.133:10012/ai/gm/v2`, and verify an override changes only the selected V2 URL.

- [ ] **Step 3: Run focused tests and verify failure**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.StructuredHazardV2EndpointTest" --tests "com.rokid.glass.config.InspectionConfigRepositoryTest"
```

Expected: FAIL because the endpoint types and two configuration fields do not exist.

- [ ] **Step 4: Implement the endpoint types and router**

```kotlin
internal enum class StructuredHazardV2Endpoint(
    val expectedResponseType: String,
    val supportsScene: Boolean,
) {
    DEEP_V2("deep_v2", true),
    GENERAL_DEEP_V2("general_deep_v2", true),
    GM_V2("gm_v2", false),
}

internal data class StructuredHazardV2Route(
    val endpoint: StructuredHazardV2Endpoint,
    val scene: String?,
)
```

Implement `forItem` and `forScene` with trimmed nonblank `placeCode` and no URL knowledge.

- [ ] **Step 5: Add `aiGeneralDeepV2Api` and `aiGmV2Api` to base, override and merge models**

Use the existing `AiArApiConfig` timeout shape. Give both V2 endpoints the same 15s connect, 45s read,
30s write and 45s call timeout currently used by `aiDeepV2Api` unless the checked-in configuration already
contains an explicitly approved override.

- [ ] **Step 6: Run focused tests**

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/StructuredHazardV2Endpoint.kt app/src/test/java/com/rokid/glass/hiddenrisk/StructuredHazardV2EndpointTest.kt app/src/main/java/com/rokid/glass/config/InspectionAppConfig.kt app/src/main/java/com/rokid/glass/config/InspectionConfigRepository.kt app/src/main/assets/inspection_config.base.jsonc app/src/test/java/com/rokid/glass/config/InspectionConfigRepositoryTest.kt
git commit -m "配置：新增结构化隐患V2端点路由"
```

### Task 2: Generalize the V2 protocol and client

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2Protocol.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2Client.kt`
- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ProtocolTest.kt`
- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ClientTest.kt`

**Interfaces:**
- Consumes: `StructuredHazardV2Endpoint`, `StructuredHazardV2Route`
- Produces: `DeepV2Request.scene: String?`
- Produces: `DeepV2Response.checkItems: List<JsonElement>`
- Produces: `DeepV2Protocol.parseResponse(body, expectedType): DeepV2Response`
- Produces: `DeepV2Client.request(requestId, route, imageBytes, callback): RequestHandle`

- [ ] **Step 1: Write failing protocol tests for all response types and nullable scene**

```kotlin
@Test fun gmRequestOmitsScene() {
    val json = JsonParser.parseString(
        DeepV2Protocol.buildRequestJson(DeepV2Request("task", null, 0.3, "image")),
    ).asJsonObject
    assertFalse(json.has("scene"))
}

@Test fun generalDeepTypeIsAcceptedOnlyWhenExpected() {
    val parsed = DeepV2Protocol.parseResponse(validBody(type = "general_deep_v2"), "general_deep_v2")
    assertEquals("general_deep_v2", parsed.type)
    assertFailsWith<DeepV2ProtocolException> {
        DeepV2Protocol.parseResponse(validBody(type = "general_deep_v2"), "deep_v2")
    }
}
```

Also cover `gm_v2`, empty detections, and both string-valued and object-valued `check_items`. Change the shared
model to `List<JsonElement>` so every JSON array element is retained without coercion; current consumers only store
the field and therefore require no object-only API.

- [ ] **Step 2: Write failing MockWebServer route tests**

Issue one request per endpoint and assert exact paths, response type validation, `scene` present for deep/general,
and absent for gm. Assert `cancel()` suppresses callbacks for every endpoint.

- [ ] **Step 3: Run focused tests and verify failure**

Expected: FAIL on nullable scene, expected response type and endpoint-specific URL selection.

- [ ] **Step 4: Generalize request serialization and response validation**

Change `scene` to nullable and add it only when nonblank. Change `checkItems` to `List<JsonElement>` and copy every
array element without filtering by JSON kind. Pass the selected endpoint's
`expectedResponseType` into `parseResponse`; keep single-entry `parseResponse(body)` only if existing tests or
callers require a compatibility wrapper for `deep_v2`.

- [ ] **Step 5: Generalize `DeepV2Client` configuration selection**

Keep one client class. Resolve `AiArApiConfig` by endpoint from `InspectionConfigRepository` and build the
request using the selected route. Preserve request ID callbacks, cancellation, timeout configuration and main-thread delivery.

- [ ] **Step 6: Run focused tests**

Expected: PASS for protocol and client suites.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2Protocol.kt app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2Client.kt app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ProtocolTest.kt app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ClientTest.kt
git commit -m "链路：统一三类V2隐患分析协议"
```

### Task 3: Add shared result source, save policy and session

**Files:**
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/StructuredHazardResultSession.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/StructuredHazardResultSessionTest.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2ResolvedHazardAdapter.kt`
- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ResolvedHazardAdapterTest.kt`

**Interfaces:**
- Produces: `enum class StructuredHazardSource { AUTO_ITEM, MANUAL, SCENE, HAZARD_RECORD }`
- Produces: `data class StructuredHazardSavePolicy(upload: Boolean, requestSuggestionChecks: Boolean)`
- Produces: `StructuredHazardSource.savePolicy`
- Produces: `StructuredHazardResultSession(source, imagePayload, presentation, requestId, epoch)`
- Produces: `StructuredHazardResultSession.pageCounts(): IntArray`
- Produces: `StructuredHazardResultSession.toResolvedHazardContent(): ResolvedHazardContent`

- [ ] **Step 1: Write failing save-policy tests**

Assert `AUTO_ITEM`, `MANUAL` and `SCENE` are `(true, true)`, while `HAZARD_RECORD` is `(true, false)`.
Assert endpoint choice does not alter policy: a manual result routed through GM still requests suggestions.

- [ ] **Step 2: Write failing session tests for targets and `others`**

Build presentations with one bbox target, multiple hazards and `others`; assert page counts, all valid upload hazards,
first valid suggestion code and frozen image identity/content. Add an empty-detection `others`-only case.

- [ ] **Step 3: Run focused tests and verify failure**

Expected: FAIL because the shared session and policies do not exist.

- [ ] **Step 4: Implement immutable source, policy and session models**

The session owns copied JPEG bytes to prevent later camera buffer mutation. `pageCounts()` follows display target order
and emits one target for `others`; do not create a synthetic `DeepV2Target` for `others`.

- [ ] **Step 5: Centralize conversion to `ResolvedHazardContent`**

Reuse `DeepV2ResolvedHazardAdapter`; remove Activity-only assumptions so both Activities obtain identical upload order,
display title, hazard codes, levels, advice and law basis.

- [ ] **Step 6: Run focused tests and commit**

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/StructuredHazardResultSession.kt app/src/test/java/com/rokid/glass/hiddenrisk/StructuredHazardResultSessionTest.kt app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2ResolvedHazardAdapter.kt app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ResolvedHazardAdapterTest.kt
git commit -m "架构：抽取结构化隐患结果会话"
```

### Task 4: Make navigation and overlay explicitly support no-bbox pages

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2PresentationStateMachine.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2ResultOverlayView.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/HazardDetailOverlayView.kt`
- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2PresentationStateMachineTest.kt`
- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ResultInteractionPolicyTest.kt`
- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/HazardDetailDisplayModelTest.kt`

**Interfaces:**
- Consumes: `StructuredHazardResultSession.pageCounts()`
- Produces: focused page rendering where `labelId == null` means no bbox
- Produces: transition metadata sufficient to animate only when the selected bbox changes

- [ ] **Step 1: Add failing no-bbox navigation tests**

Create an `others`-only three-page machine and assert forward/backward changes `pageIndex` without producing a bbox
target transition. Add a mixed target + `others` case and assert only crossing between different real bbox targets requests animation.

- [ ] **Step 2: Add failing display-model tests**

Assert one-page results hide page count, multi-page results show `current/total`, and maximum-height overflow is ellipsized
inside the current hazard rather than creating another navigation page.

- [ ] **Step 3: Run focused tests and verify failure**

Expected: FAIL where current state/render code assumes every focused target owns a bbox.

- [ ] **Step 4: Implement explicit nullable bbox rendering**

Clear `DeepV2ResultOverlayView` when the selected hazard has no detection. Continue rendering
`HazardDetailOverlayView` over the frozen image. Compare previous and next real `labelId`; animate only when they differ
and both states have real bbox geometry.

- [ ] **Step 5: Verify established bbox styling remains unchanged**

Keep 3dp corner stroke, 14dp corner length, 8dp radius, 1dp connector, transparent bbox interior, current selected scale,
label + level placement, and black translucent hazard background.

- [ ] **Step 6: Run focused tests and commit**

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2PresentationStateMachine.kt app/src/main/java/com/rokid/glass/hiddenrisk/DeepV2ResultOverlayView.kt app/src/main/java/com/rokid/glass/hiddenrisk/HazardDetailOverlayView.kt app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2PresentationStateMachineTest.kt app/src/test/java/com/rokid/glass/hiddenrisk/DeepV2ResultInteractionPolicyTest.kt app/src/test/java/com/rokid/glass/hiddenrisk/HazardDetailDisplayModelTest.kt
git commit -m "界面：支持无检测框的隐患结果翻页"
```

### Task 5: Migrate manual and scene flows in `AiInspectionActivity`

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionService.kt`
- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionServiceTest.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/StructuredHazardRequestPolicyTest.kt`

**Interfaces:**
- Consumes: `DeepV2Client.request(requestId, route, imageBytes, callback)`
- Consumes: `StructuredHazardSource.MANUAL`, `StructuredHazardSource.SCENE`
- Produces: one Activity entry method `requestStructuredHazardResult(source, imagePayload, route, epoch)`

- [ ] **Step 1: Write failing request-policy tests**

Assert manual uses `forItem(placeCode)`, scene uses `forScene(placeCode)`, scene without place is skipped, and
`InspectionFeatureFlags.isOfflineLocalMode()` blocks every network request before image encoding.

- [ ] **Step 2: Update service tests to reject legacy detail dispatch for target lanes**

Keep `/auto` and `/general` detection responsibilities in `OnlineHazardDetectionService`, but assert Activity-owned V2
detail presentation no longer calls `requestDeepAnalysis()` or `requestGeneralDeepAnalysis()` after a target detection result.

- [ ] **Step 3: Run focused tests and verify failure**

Expected: FAIL because manual and scene callbacks still enter old SSE methods.

- [ ] **Step 4: Extract one aligned-image request path in the Activity**

Reuse `encodeAlignedDeepImage`. The produced JPEG must be passed unchanged to the client and stored in the session.
Manual start pauses automatic pipelines; scene result shares the existing structured-result presentation gate.

- [ ] **Step 5: Route manual and scene success through existing V2 normalization and presentation**

Create `StructuredHazardResultSession` with the correct source. On empty displayable hazards, play/announce no hazard and
return to detecting. On failure, show a short nonblocking prompt, clear the request handle and start a fresh auto epoch.
Do not call the old SSE parser or stream response UI.

- [ ] **Step 6: Preserve save + `/ai/sug_checks` behavior**

Confirmation converts the session with `toResolvedHazardContent()` and calls the existing
`submitLocalHazardAndShowAdvice()`. Upload all valid hazards and use the first valid code for suggestions.

- [ ] **Step 7: Run Activity-adjacent unit tests and commit**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.StructuredHazardRequestPolicyTest" --tests "com.rokid.glass.hiddenrisk.OnlineHazardDetectionServiceTest" --tests "com.rokid.glass.hiddenrisk.DeepV2PresentationStateMachineTest"
git add app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt app/src/main/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionService.kt app/src/test/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionServiceTest.kt app/src/test/java/com/rokid/glass/hiddenrisk/StructuredHazardRequestPolicyTest.kt
git commit -m "链路：迁移手动与环境隐患分析到V2"
```

### Task 6: Migrate `HazardRecordActivity` to the shared V2 result UI

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/HazardRecordActivity.kt`
- Modify: `app/src/main/res/layout/activity_hazard_record.xml`
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/HazardRecordV2Coordinator.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/HazardRecordV2CoordinatorTest.kt`

**Interfaces:**
- Consumes: `StructuredHazardV2EndpointRouter.forItem(placeCode)`
- Consumes: `StructuredHazardSource.HAZARD_RECORD`
- Produces: `HazardRecordV2Coordinator.begin(requestId): Generation`
- Produces: `HazardRecordV2Coordinator.accept(requestId): Boolean`
- Produces: the same result Overlay IDs and model setters used by the inspection result page

- [ ] **Step 1: Write failing coordinator generation tests**

Assert a second capture invalidates the first, cancellation invalidates the active generation, and a callback after
pause/destroy is rejected.

- [ ] **Step 2: Write failing save-policy assertion for the record source**

Assert confirmation builds all valid upload items but returns `requestSuggestionChecks == false` for both DEEP and GM routes.

- [ ] **Step 3: Run focused tests and verify failure**

Expected: FAIL because the coordinator and shared result state do not exist in this Activity.

- [ ] **Step 4: Add the shared result layers to `activity_hazard_record.xml`**

Use the same stacking order as `activity_ai_inspection.xml`: frozen image, bbox overlay, hazard detail overlay and save dialog.
Do not copy custom drawing implementations into the Activity.

- [ ] **Step 5: Replace SSE analysis with endpoint-routed V2 analysis**

Encode the captured frame through the same 3:4 aligned helper used by automatic/manual requests. Route by `placeCode`,
store the exact uploaded JPEG, normalize the response and create a `HAZARD_RECORD` session.

- [ ] **Step 6: Replace scroll-text input with shared navigation**

Front/back gestures navigate hazards; confirm opens the save dialog; cancel returns to record idle. Empty detections clear
the bbox layer and never start bbox animation.

- [ ] **Step 7: Keep record save behavior unchanged**

Reuse `LocalHazardUploadItemBuilder` and `LocalHazardPushService`. On success return to the record idle page and show the
existing success toast. On failure keep the result session visible and allow confirm again. Never call `/ai/sug_checks`.

- [ ] **Step 8: Run focused tests and commit**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.HazardRecordV2CoordinatorTest" --tests "com.rokid.glass.hiddenrisk.StructuredHazardResultSessionTest" --tests "com.rokid.glass.hiddenrisk.LocalHazardUploadItemBuilderTest"
git add app/src/main/java/com/rokid/glass/hiddenrisk/HazardRecordActivity.kt app/src/main/res/layout/activity_hazard_record.xml app/src/main/java/com/rokid/glass/hiddenrisk/HazardRecordV2Coordinator.kt app/src/test/java/com/rokid/glass/hiddenrisk/HazardRecordV2CoordinatorTest.kt
git commit -m "链路：迁移隐患拍照到结构化V2结果"
```

### Task 7: Enforce lifecycle, offline and duplicate-submit boundaries

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/HazardRecordActivity.kt`
- Modify: `app/src/main/java/com/rokid/glass/network/InspectionNetworkAccessPolicy.kt`
- Modify: `app/src/test/java/com/rokid/glass/network/InspectionNetworkAccessPolicyTest.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/StructuredHazardSubmissionPolicyTest.kt`

**Interfaces:**
- Consumes: request handles and generations from Tasks 2, 5 and 6
- Produces: `StructuredHazardSubmissionPolicy.canSubmit(isSubmitting, uploadHazards): Boolean`

- [ ] **Step 1: Write failing offline URL tests**

Assert `OFFLINE_LOCAL` blocks `/ai/deep/v2`, `/ai/general_deep/v2`, `/ai/gm/v2` and `/ai/sug_checks`, including configured
host variations rather than only literal production URLs.

- [ ] **Step 2: Write failing duplicate and empty-submit tests**

Assert submitting state rejects repeated confirm, empty upload hazards show no-save behavior, and a failed upload releases
the gate for retry without reconstructing the session.

- [ ] **Step 3: Run focused tests and verify failure**

Expected: FAIL for newly introduced V2 URLs or submission policy.

- [ ] **Step 4: Implement cancellation and gate cleanup at every lifecycle exit**

Cancel the correct V2 handle in `onPause`, `onDestroy`, cancel-result, new capture and auto epoch reset. Clear only state
owned by that request; do not cancel an unrelated upload already allowed to continue in the background.

- [ ] **Step 5: Run focused tests and commit**

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt app/src/main/java/com/rokid/glass/hiddenrisk/HazardRecordActivity.kt app/src/main/java/com/rokid/glass/network/InspectionNetworkAccessPolicy.kt app/src/test/java/com/rokid/glass/network/InspectionNetworkAccessPolicyTest.kt app/src/test/java/com/rokid/glass/hiddenrisk/StructuredHazardSubmissionPolicyTest.kt
git commit -m "可靠性：约束V2请求生命周期与重复提交"
```

### Task 8: Remove target legacy call paths and synchronize documentation

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiArSseService.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/README.md`
- Modify: `docs/CODEMAPS.md`
- Modify: `docs/APIs/README.md`
- Test: existing standard unit-test suite

**Interfaces:**
- Consumes: completed V2 routes from Tasks 1–7
- Produces: no target business caller of `requestDeepAnalysis()` or `requestGeneralDeepAnalysis()`

- [ ] **Step 1: Audit remaining legacy callers**

Run:

```powershell
rg -n "requestDeepAnalysis\(|requestGeneralDeepAnalysis\(|/ai/deep\"|/ai/general_deep\"|/ai/gm\"" app/src/main/java app/src/main/assets
```

Expected: old configuration declarations may remain, but manual analysis, hazard record and environment detail must not
call old SSE methods.

- [ ] **Step 2: Remove methods only when there are zero non-test callers**

If `AiArSseService.requestDeepAnalysis` or `requestGeneralDeepAnalysis` still serves an out-of-scope business caller, retain
the method and document that caller. Otherwise remove the dead method, constructor config and obsolete tests together.

- [ ] **Step 3: Update code maps and API documentation**

Document all three V2 paths, route table, empty-bbox behavior, source-specific save policy and the preserved offline boundary.
Replace statements claiming manual/record/environment remain on old SSE.

- [ ] **Step 4: Run the complete standard unit suite**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest
```

Expected: BUILD SUCCESSFUL with all standard unit tests passing.

- [ ] **Step 5: Build and verify the debug APK**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/build-debug.ps1
powershell -ExecutionPolicy Bypass -File scripts/android/verify-apk.ps1 app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

Expected: standard debug APK exists, is valid, and contains package `com.rokid.glesse`.

- [ ] **Step 6: Commit documentation and cleanup**

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/AiArSseService.kt app/src/main/java/com/rokid/glass/hiddenrisk/README.md docs/CODEMAPS.md docs/APIs/README.md
git commit -m "文档：同步统一V2隐患分析链路"
```

### Task 9: Install and validate all routes on the Rokid Glass

**Files:**
- Verify only: `app/build/outputs/apk/standard/debug/app-standard-debug.apk`
- Verify only: device logs and visible UI

**Interfaces:**
- Consumes: completed standard debug APK
- Produces: route-by-route device evidence

- [ ] **Step 1: Confirm the exact device and install without clearing data**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/doctor.ps1 -Device
powershell -ExecutionPolicy Bypass -File scripts/android/install-debug.ps1 -Serial 1901092544019017
```

Expected: serial `1901092544019017` is online and `adb install -r` succeeds. Do not uninstall or clear app data.

- [ ] **Step 2: Validate automatic item V2**

Trigger `/auto` area threshold. Verify frozen 3:4 image, real bbox, label + level, selected scale without thicker stroke,
hazard pages, save dialog, upload and `/ai/sug_checks`.

- [ ] **Step 3: Validate manual V2 with `placeCode`**

Trigger manual deep analysis. Verify `/ai/deep/v2`, auto detection pause/resume, shared result UI, upload and `/ai/sug_checks`.

- [ ] **Step 4: Validate environment V2**

Trigger a `/ai/general` hazard. Verify `/ai/general_deep/v2`, frozen background, no bbox, no bbox animation, hazard-only paging,
upload and `/ai/sug_checks`.

- [ ] **Step 5: Validate hazard record with `placeCode`**

Capture a hazard. Verify `/ai/deep/v2`, shared result UI, upload-only behavior, and return to record idle after success.

- [ ] **Step 6: Validate both GM routes without `placeCode`**

Verify manual analysis calls `/ai/gm/v2` and performs upload + `/ai/sug_checks`; verify hazard record calls `/ai/gm/v2`
and performs upload only. Both must show no bbox and no bbox animation.

- [ ] **Step 7: Validate failure and lifecycle cases**

Exercise cancel, empty hazards, network failure, repeated confirm, Activity background/foreground and late response. Verify no
old SSE fallback and no stale result restoration.

- [ ] **Step 8: Capture final evidence**

Collect filtered logs without sensitive payloads:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s 1901092544019017 logcat -d -v time | Select-String "DeepV2|general_deep_v2|gm_v2|sug_checks|StructuredHazard|HazardRecord"
```

Record which routes were confirmed and explicitly mark any route that could not be triggered as unverified rather than passed.

### Task 10: Final regression and branch checkpoint

**Files:**
- Verify: complete working tree and commit history

- [ ] **Step 1: Re-run final verification from a clean build state**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest
powershell -ExecutionPolicy Bypass -File scripts/android/build-debug.ps1
powershell -ExecutionPolicy Bypass -File scripts/android/verify-apk.ps1 app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

Expected: all commands succeed in the current checkout.

- [ ] **Step 2: Verify source and network boundaries**

```powershell
rg -n "requestDeepAnalysis\(|requestGeneralDeepAnalysis\(" app/src/main/java
rg -n "aiDeepV2Api|aiGeneralDeepV2Api|aiGmV2Api" app/src/main app/src/test
git status --short
```

Expected: no target legacy caller remains; all V2 config fields have production and test coverage; only known user-owned
untracked files such as `.agents/skills/apifox-cli/` may remain outside implementation commits.

- [ ] **Step 3: Review commit boundaries**

Confirm every commit contains only its task files, no Base64 payloads, credentials, generated logs, APKs or user-owned skill files.

- [ ] **Step 4: Report completion evidence**

Report test/build/APK results, device serial and installed version, each verified route, unverified scenarios, remaining legacy
callers (if any), and final Git status. Do not push or open a PR without separate user authorization.
