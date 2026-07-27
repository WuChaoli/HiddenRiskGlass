# localTriger 完全离线本地模式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `localTriger` 变体改为不依赖企业扫码和网络、只运行本地 NCNN 推理且禁止所有应用业务网络请求的完全离线模式。

**Architecture:** 使用配置驱动的 `NetworkAccessMode.OFFLINE_LOCAL` 作为统一策略源，页面入口和推理路由主动遵循策略，共享 OkHttp 客户端拦截器提供最终网络阻断。`standard` 等其他变体继续使用 `ONLINE` 默认值。

**Tech Stack:** Kotlin、Android Activity/Service、OkHttp、Gson JSONC overlay、JUnit4、Gradle Android variants。

## Global Constraints

- 默认业务变体保持 `standard`，仅 `localTriger` 启用完全离线模式。
- 配置只从 `InspectionConfigRepository` 读取，不散落使用 `BuildConfig.FLAVOR`。
- 生产模型只由 `InspectionLoadingActivity` 加载。
- JNI 调用仍只通过 `HiddenRiskNcnn.java`，NCNN param/bin 不改动。
- 代码标识符使用 English，注释和文档使用简体中文。
- 保留现有 `.gitignore` 与 `.serena/project.yml` 用户改动。

---

### Task 1: 离线运行策略与 flavor 配置

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/config/InspectionAppConfig.kt`
- Modify: `app/src/main/java/com/rokid/glass/config/InspectionConfigRepository.kt`
- Modify: `app/src/main/java/com/rokid/glass/InspectionFeatureFlags.kt`
- Modify: `app/src/main/assets/inspection_config.localTriger.jsonc`
- Test: `app/src/test/java/com/rokid/glass/config/InspectionConfigRepositoryTest.kt`
- Test: `app/src/test/java/com/rokid/glass/InspectionFeatureFlagsTest.kt`

**Interfaces:**
- Produces: `enum class NetworkAccessMode { ONLINE, OFFLINE_LOCAL }`
- Produces: `InspectionFeatureFlags.isBusinessNetworkAllowed(): Boolean`
- Produces: `InspectionFeatureFlags.isOfflineLocalMode(): Boolean`
- Produces: `InspectionFeatureFlags.isWifiEntryGuardRequired(): Boolean`

- [ ] **Step 1: Write failing configuration and policy tests**

```kotlin
@Test
fun `offline local overlay disables enterprise and remote routes`() {
    val config = InspectionConfigRepository.buildConfig(
        baseJsonc = "{}",
        overlayJsonc = """{"featureFlags":{"enableEnterpriseInspectionFlow":false,"networkAccessMode":"OFFLINE_LOCAL"},"aiInspection":{"autoInferenceMode":"LOCAL_ONLY","autoHazardRoutingMode":"LOCAL_ONLY","autoDetectProvider":"LOCAL_TRIGGER","enableOnlineSceneHazardDetection":false,"forceOnlineDetailForLocalHazard":false,"forceLocalHazardDetailAnalysis":true}}""",
    )
    assertEquals(NetworkAccessMode.OFFLINE_LOCAL, config.featureFlags.networkAccessMode)
    assertFalse(config.featureFlags.enableEnterpriseInspectionFlow)
    assertEquals(AutoInferenceMode.LOCAL_ONLY, config.aiInspection.autoInferenceMode)
}
```

- [ ] **Step 2: Run tests and verify unresolved `NetworkAccessMode` failure**

Run: `powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testLocalTrigerDebugUnitTest --tests "com.rokid.glass.config.InspectionConfigRepositoryTest"`

- [ ] **Step 3: Implement enum, merge logic, policy helpers and overlay values**

- [ ] **Step 4: Re-run targeted tests and confirm PASS**

### Task 2: Wi-Fi、更新和企业扫码入口

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/EntryGuardCoordinator.kt`
- Modify: `app/src/main/java/com/rokid/glass/MainMenuActivity.kt`
- Modify: `app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt`
- Modify: `app/src/main/java/com/rokid/glass/EnterpriseQrScanActivity.kt`
- Test: `app/src/test/java/com/rokid/glass/EntryGuardPolicyTest.kt`

**Interfaces:**
- Consumes: `InspectionFeatureFlags.isOfflineLocalMode()`
- Consumes: `InspectionFeatureFlags.isWifiEntryGuardRequired()`
- Produces: pure `EntryGuardPolicy` decisions for Wi-Fi, update and enterprise navigation.

- [ ] **Step 1: Write failing policy tests**

```kotlin
@Test fun offlineLocalSkipsWifi() = assertFalse(EntryGuardPolicy.requiresWifi(true))
@Test fun offlineLocalSkipsAutoUpdate() = assertFalse(EntryGuardPolicy.allowsAutoUpdate(true))
@Test fun offlineLocalSkipsEnterpriseScan() = assertFalse(EntryGuardPolicy.requiresEnterpriseContext(true))
```

- [ ] **Step 2: Run targeted test and verify missing policy failure**
- [ ] **Step 3: Implement policy and wire Activities/Coordinator**
- [ ] **Step 4: Re-run targeted tests and confirm PASS**

### Task 3: 空 placeCode 本地识别与纯本地自动链路

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/LocalTriggerDetectionService.kt`
- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/LocalTriggerDetectionServiceTest.kt`
- Test: `app/src/test/java/com/rokid/glass/hiddenrisk/InspectionModelLoadPolicyTest.kt`

**Interfaces:**
- Produces: `LocalTriggerDetectionService.detect()` always evaluates the frame regardless of enterprise `placeCode`.
- Consumes: `AutoHazardRoutingMode.LOCAL_ONLY` to suppress remote recovery.

- [ ] **Step 1: Replace the existing skip test with a failing empty-placeCode inference test**

```kotlin
@Test
fun detectRunsCoordinatorWithoutPlaceCode() {
    val coordinator = FakeCoordinator(successOutcome("燃气灶"))
    val events = mutableListOf<String>()
    createService(coordinator).detect(detectionRequest(), callback(events))
    assertEquals(listOf(FakeBitmapToken), coordinator.bitmaps)
    assertEquals(listOf("success:true:燃气灶"), events)
}
```

- [ ] **Step 2: Run the test and verify coordinator was not called**
- [ ] **Step 3: Remove only the placeCode short-circuit and retain diagnostic logging**
- [ ] **Step 4: Re-run local trigger and rule tests**

### Task 4: 集中式业务网络总闸

**Files:**
- Create: `app/src/main/java/com/rokid/glass/network/InspectionNetworkAccessPolicy.kt`
- Modify: `app/src/main/java/com/rokid/glass/network/HttpClientProvider.kt`
- Test: `app/src/test/java/com/rokid/glass/network/InspectionNetworkAccessPolicyTest.kt`

**Interfaces:**
- Produces: `InspectionNetworkAccessPolicy.isAllowed(): Boolean`
- Produces: `InspectionNetworkAccessPolicy.interceptor: Interceptor`
- Error contract: `IOException("offline_local_blocked:<url>")`

- [ ] **Step 1: Write failing allowed/blocked policy and interceptor tests**
- [ ] **Step 2: Run tests and verify missing policy failure**
- [ ] **Step 3: Implement policy with an injectable decision provider for JVM tests**
- [ ] **Step 4: Install interceptor on `inspectionClient` and `sseClient`**
- [ ] **Step 5: Re-run policy and network tests**

### Task 5: 收口无本地替代的联网功能

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`
- Modify: `app/src/main/java/com/rokid/glass/InspectionEndReportActivity.kt`
- Test: `app/src/test/java/com/rokid/glass/OfflineLocalUiPolicyTest.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `OfflineLocalUiPolicy.visibleMenuItems(offlineLocal: Boolean)`
- Produces: `OfflineLocalUiPolicy.manualDeepEnabled(offlineLocal: Boolean)`
- Produces: `InspectionFinishUploadPolicy.canEnqueue(networkAvailable, businessNetworkAllowed)`

- [ ] **Step 1: Write failing menu/manual-analysis/finish-upload tests**
- [ ] **Step 2: Run tests and verify new signatures are absent**
- [ ] **Step 3: Keep only hazard analysis card offline, omit scan voice action, block manual deep, and pass business policy to finish upload**
- [ ] **Step 4: Re-run targeted tests**

### Task 6: 回归验证与文档同步

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/README.md`
- Modify: `docs/CODEMAPS.md`

- [ ] **Step 1: Run localTriger unit tests**

Run: `powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testLocalTrigerDebugUnitTest`

- [ ] **Step 2: Run standard unit tests to detect cross-flavor regressions**

Run: `powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest`

- [ ] **Step 3: Build the APK**

Run: `powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:assembleLocalTrigerDebug`

- [ ] **Step 4: Audit reachable network calls**

Run: `rg -n "requestDeepAnalysis|fetchInspectionGuide|fetchSuggestionChecks|pushLocalHazard|enqueueFinishInspection|checkForUpdate" app/src/main/java`

- [ ] **Step 5: Update architecture documentation with the effective localTriger flow and verification commands**

- [ ] **Step 6: Inspect `git diff --check`, `git diff --stat`, and preserve unrelated dirty files**
