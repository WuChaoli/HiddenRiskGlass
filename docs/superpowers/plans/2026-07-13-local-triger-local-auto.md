# local-triger Local Auto Replacement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a `localTriger` product flavor that replaces `/ai/auto` item detection with local NCNN trigger inference while keeping `/ai/deep`, `/ai/device`, UI, voice, input, and navigation behavior unchanged.

**Architecture:** Add an `autoDetectProvider` config and select a request gateway at the existing detection boundary. HTTP gateway remains the default and owns base64 encoding; local gateway uses `DetectionRequest.jpegBytes` directly and wraps local NCNN inference behind the same `DetectCallback` contract.

**Tech Stack:** Android Gradle product flavors, Kotlin, Java JNI bridge through `HiddenRiskNcnn`, Gson JSONC config, JUnit unit tests, existing `OnlineHazardDetectionService` and `AiArSseService`.

## Global Constraints

- Gradle product flavor name: `localTriger`; external name: `local-triger`.
- Application ID stays `com.rokid.glesse`.
- `standard` and `dataBackup` behavior remains unchanged.
- `localTriger` replaces only item `/ai/auto` trigger detection.
- Missing or blank `placeCode` skips detection and must not load or run local NCNN.
- Local trigger path must not generate base64.
- HiddenRisk JNI calls remain through `HiddenRiskNcnn.java`.
- Current `/ai/deep` and `/ai/device` behavior stays unchanged.
- No NCNN asset replacement in this implementation.

---

## File Structure

- Modify `app/build.gradle`: add `localTriger` flavor.
- Modify `app/src/main/java/com/rokid/glass/config/InspectionAppConfig.kt`: add `AutoDetectProvider` enum and nullable override.
- Modify `app/src/main/java/com/rokid/glass/config/InspectionConfigRepository.kt`: merge `autoDetectProvider`.
- Create `app/src/main/assets/inspection_config.localTriger.jsonc`: flavor overlay.
- Modify `app/src/main/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionService.kt`: move base64 encoding into HTTP gateway, expose gateway factory, add local gateway.
- Create `app/src/main/java/com/rokid/glass/hiddenrisk/LocalTriggerDetectionService.kt`: local NCNN trigger wrapper that receives `AssetManager` from the owning Activity.
- Modify `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`: select gateway through factory.
- Modify `app/src/main/java/com/rokid/glass/hiddenrisk/DeviceGuideActivity.kt`: use shared item detection service instead of direct `AiArSseService.identifyItemHazard()`.
- Modify `app/src/test/java/com/rokid/glass/config/InspectionConfigRepositoryTest.kt`: config tests.
- Modify `app/src/test/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionServiceTest.kt`: gateway contract tests.
- Create `app/src/test/java/com/rokid/glass/hiddenrisk/LocalTriggerDetectionServiceTest.kt`: local trigger tests.

---

### Task 1: Add Flavor And Config Switch

**Files:**
- Modify: `app/build.gradle`
- Modify: `app/src/main/java/com/rokid/glass/config/InspectionAppConfig.kt`
- Modify: `app/src/main/java/com/rokid/glass/config/InspectionConfigRepository.kt`
- Create: `app/src/main/assets/inspection_config.localTriger.jsonc`
- Test: `app/src/test/java/com/rokid/glass/config/InspectionConfigRepositoryTest.kt`

**Interfaces:**
- Produces: `enum class AutoDetectProvider { HTTP, LOCAL_TRIGGER }`
- Produces: `AiInspectionConfig.autoDetectProvider: AutoDetectProvider`
- Consumes later: `InspectionConfigRepository.get().aiInspection.autoDetectProvider`

- [ ] **Step 1: Write failing config tests**

Add these imports and tests to `app/src/test/java/com/rokid/glass/config/InspectionConfigRepositoryTest.kt`:

```kotlin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Test
fun `auto detect provider defaults to HTTP`() {
    val config = InspectionConfigRepository.buildConfig(
        baseJsonc = null,
        overlayJsonc = null,
    )

    assertEquals(AutoDetectProvider.HTTP, config.aiInspection.autoDetectProvider)
}

@Test
fun `auto detect provider can be overridden to local trigger`() {
    val config = InspectionConfigRepository.buildConfig(
        baseJsonc = null,
        overlayJsonc = """
            {
              "aiInspection": {
                "autoDetectProvider": "LOCAL_TRIGGER"
              }
            }
        """.trimIndent(),
    )

    assertEquals(AutoDetectProvider.LOCAL_TRIGGER, config.aiInspection.autoDetectProvider)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.config.InspectionConfigRepositoryTest"
```

Expected: FAIL with unresolved reference `AutoDetectProvider` or missing `autoDetectProvider`.

- [ ] **Step 3: Add config model**

In `app/src/main/java/com/rokid/glass/config/InspectionAppConfig.kt`, add the property to `AiInspectionConfig` after `autoHazardRoutingMode`:

```kotlin
val autoDetectProvider: AutoDetectProvider = AutoDetectProvider.HTTP,
```

Add enum after `AutoHazardRoutingMode`:

```kotlin
enum class AutoDetectProvider {
    HTTP,
    LOCAL_TRIGGER,
}
```

Add nullable override field to `AiInspectionConfigOverride` after `autoHazardRoutingMode`:

```kotlin
val autoDetectProvider: AutoDetectProvider? = null,
```

- [ ] **Step 4: Merge config field**

In `app/src/main/java/com/rokid/glass/config/InspectionConfigRepository.kt`, update `merge(base: AiInspectionConfig, override: AiInspectionConfigOverride?)`:

```kotlin
autoHazardRoutingMode = override?.autoHazardRoutingMode ?: base.autoHazardRoutingMode,
autoDetectProvider = override?.autoDetectProvider ?: base.autoDetectProvider,
captureWarmupMs = override?.captureWarmupMs ?: base.captureWarmupMs,
```

- [ ] **Step 5: Add localTriger flavor and overlay**

In `app/build.gradle`, add inside `productFlavors`:

```groovy
localTriger {
    dimension "edition"
}
```

Create `app/src/main/assets/inspection_config.localTriger.jsonc`:

```jsonc
{
  "aiInspection": {
    "autoDetectProvider": "LOCAL_TRIGGER"
  }
}
```

- [ ] **Step 6: Run config tests**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.config.InspectionConfigRepositoryTest"
```

Expected: PASS.

- [ ] **Step 7: Verify localTriger task exists**

Run:

```bash
./gradlew :app:tasks --all | rg "assembleLocalTrigerDebug|testLocalTrigerDebugUnitTest"
```

Expected: output contains both `assembleLocalTrigerDebug` and `testLocalTrigerDebugUnitTest`.

- [ ] **Step 8: Commit**

```bash
git add app/build.gradle app/src/main/java/com/rokid/glass/config/InspectionAppConfig.kt app/src/main/java/com/rokid/glass/config/InspectionConfigRepository.kt app/src/main/assets/inspection_config.localTriger.jsonc app/src/test/java/com/rokid/glass/config/InspectionConfigRepositoryTest.kt
git commit -m "feat: add local trigger flavor config"
```

---

### Task 2: Refactor Detection Gateway To Own Encoding

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionService.kt`
- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionServiceTest.kt`

**Interfaces:**
- Changes: `RequestGateway.identifyHazard(request, callback): AiArSseService.RequestHandle`
- Changes: `RequestGateway.requestDeepAnalysis(request, onChunk, callback): AiArSseService.RequestHandle`
- Produces: HTTP gateway performs base64 encoding internally.
- Consumes: `DetectionRequest.jpegBytes`

- [ ] **Step 1: Update failing test fake gateway signature**

In `OnlineHazardDetectionServiceTest.kt`, change fake gateway methods to remove `base64Image` and record JPEG bytes:

```kotlin
val startedDetectionJpegBytes = mutableListOf<ByteArray>()

override fun identifyHazard(
    request: OnlineHazardDetectionService.DetectionRequest,
    callback: AiArSseService.DetectCallback,
): AiArSseService.RequestHandle {
    val requestId = request.requestId
    val lane = request.lane
    val handle = AiArSseService.RequestHandle(taskId = "detect-$requestId")
    detectCallbacks[requestId] = callback
    detectionHandles[requestId] = handle
    startedDetectionRequestIds += requestId
    startedDetectionLanes += lane
    startedDetectionJpegBytes += request.jpegBytes
    return handle
}

override fun requestDeepAnalysis(
    request: OnlineHazardDetectionService.DetailRequest,
    onChunk: (String) -> Unit,
    callback: AiArSseService.DetailCallback,
): AiArSseService.RequestHandle {
    val handle = AiArSseService.RequestHandle(taskId = "detail")
    detailCallback = callback
    detailHandle = handle
    startedDetailLanes += request.lane
    return handle
}
```

Add a test:

```kotlin
@Test
fun submitDetection_passesRawJpegBytesToGateway() {
    val env = TestEnv()
    val service = env.createService()
    val request = detectionRequest(requestId = 41L)

    service.submitDetection(request)

    assertEquals(listOf(41L), env.gateway.startedDetectionRequestIds)
    assertTrue(env.gateway.startedDetectionJpegBytes.single().contentEquals(byteArrayOf(1, 2, 3)))
}
```

- [ ] **Step 2: Run service tests to verify compile failure**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.OnlineHazardDetectionServiceTest"
```

Expected: FAIL because `RequestGateway` still expects `base64Image`.

- [ ] **Step 3: Change RequestGateway interface**

In `OnlineHazardDetectionService.kt`, replace the interface with:

```kotlin
internal interface RequestGateway {
    fun identifyHazard(
        request: DetectionRequest,
        callback: AiArSseService.DetectCallback,
    ): AiArSseService.RequestHandle

    fun requestDeepAnalysis(
        request: DetailRequest,
        onChunk: (String) -> Unit,
        callback: AiArSseService.DetailCallback,
    ): AiArSseService.RequestHandle
}
```

- [ ] **Step 4: Remove service-level base64 encoding**

In `requestDeepAnalysis(request: DetailRequest)`, remove the `encodeExecutor.execute` block and call gateway directly on the scheduler:

```kotlin
activeDetailHandle = requestGateway.requestDeepAnalysis(
    request = request,
    onChunk = { accumulatedText ->
        if (activeDetailRequest == request) {
            callback.onDeepAnalysisChunk(request, accumulatedText)
        }
    },
    callback = object : AiArSseService.DetailCallback {
        override fun onOpened(handle: AiArSseService.RequestHandle) {
            infoLogger("detail opened lane=${request.lane.logName} taskId=${handle.taskId} requestId=${request.requestId}")
        }

        override fun onSuccess(handle: AiArSseService.RequestHandle, fullText: String) {
            if (activeDetailRequest != request) return
            activeDetailRequest = null
            activeDetailHandle = null
            callback.onDeepAnalysisSuccess(request, fullText)
        }

        override fun onFailure(handle: AiArSseService.RequestHandle, message: String) {
            if (activeDetailRequest != request) return
            activeDetailRequest = null
            activeDetailHandle = null
            callback.onDeepAnalysisFailure(request, message)
        }
    },
)
```

In `startDetection()`, keep the concurrency/timeout logic but remove the `base64Encoder(request.jpegBytes)` work. Call:

```kotlin
val uploadStartedElapsedMs = elapsedRealtimeProvider()
val handle = requestGateway.identifyHazard(
    request = request,
    callback = object : AiArSseService.DetectCallback {
        override fun onOpened(handle: AiArSseService.RequestHandle) {
            val openedActive = activeDetections[request.requestId] ?: return
            infoLogger(
                "detect opened lane=${request.lane.logName} taskId=${handle.taskId} requestId=${request.requestId} epoch=${request.epoch} activePoolSize=${activeDetections.size} jpegBytes=${request.jpegBytes.size} elapsedMs=${elapsedRealtimeProvider() - openedActive.startedElapsedMs}",
            )
        }

        override fun onSuccess(
            handle: AiArSseService.RequestHandle,
            hasHazard: Boolean,
            fullText: String,
            labels: List<String>,
        ) {
            val completedActive = removeActiveDetection(request.requestId) ?: return
            val completedElapsedMs = elapsedRealtimeProvider()
            val detectElapsedMs = completedElapsedMs - completedActive.startedElapsedMs
            val submitToUploadMs = uploadStartedElapsedMs - completedActive.startedElapsedMs
            val captureToHasHazardMs = durationOrMinusOne(request.frameCapturedAtElapsedMs, completedElapsedMs)
            val uploadToHasHazardMs = completedElapsedMs - uploadStartedElapsedMs
            infoLogger("detect success lane=${request.lane.logName} taskId=${handle.taskId} requestId=${request.requestId} hasHazard=$hasHazard activePoolSize=${activeDetections.size} rawTextLength=${fullText.length} labelCount=${labels.size} totalElapsedMs=$detectElapsedMs")
            infoLogger("detect timing summary lane=${request.lane.logName} taskId=${handle.taskId} requestId=${request.requestId} epoch=${request.epoch} frameTs=${request.frameTimestamp} hasHazard=$hasHazard captureToUploadMs=${durationOrMinusOne(request.frameCapturedAtElapsedMs, uploadStartedElapsedMs)} payloadBuiltToUploadMs=${durationOrMinusOne(request.framePayloadBuiltAtElapsedMs, uploadStartedElapsedMs)} submitToUploadMs=$submitToUploadMs uploadToHasHazardMs=$uploadToHasHazardMs captureToHasHazardMs=$captureToHasHazardMs detectServiceElapsedMs=$detectElapsedMs rawTextLength=${fullText.length} labelCount=${labels.size} jpegBytes=${request.jpegBytes.size}")
            callback.onDetectionResult(request.copy(cooldownLabels = labels), hasHazard, fullText, labels)
        }

        override fun onFailure(handle: AiArSseService.RequestHandle, message: String) {
            val failedActive = removeActiveDetection(request.requestId) ?: return
            val failedElapsedMs = elapsedRealtimeProvider()
            val detectElapsedMs = failedElapsedMs - failedActive.startedElapsedMs
            warningLogger("detect failure lane=${request.lane.logName} taskId=${handle.taskId} requestId=${request.requestId} epoch=${request.epoch} activePoolSize=${activeDetections.size} jpegBytes=${request.jpegBytes.size} totalElapsedMs=$detectElapsedMs captureToFailureMs=${durationOrMinusOne(request.frameCapturedAtElapsedMs, failedElapsedMs)} message=$message")
            callback.onDetectionFailure(request, message)
        }
    },
)
active.handle = handle
```

- [ ] **Step 5: Move base64 into SseRequestGateway**

Change `SseRequestGateway` constructor:

```kotlin
private class SseRequestGateway(
    private val aiArSseService: AiArSseService,
    private val base64Encoder: (ByteArray) -> String,
) : RequestGateway {
```

Update default constructor parameter:

```kotlin
private val requestGateway: RequestGateway = SseRequestGateway(
    AiArSseService(),
    base64Encoder,
),
```

Update methods:

```kotlin
override fun identifyHazard(
    request: DetectionRequest,
    callback: AiArSseService.DetectCallback,
): AiArSseService.RequestHandle {
    val base64Image = base64Encoder(request.jpegBytes)
    return when (request.lane) {
        DetectionLane.ITEM -> aiArSseService.identifyItemHazard(base64Image, callback)
        DetectionLane.SCENE -> aiArSseService.identifySceneHazard(base64Image, callback)
    }
}

override fun requestDeepAnalysis(
    request: DetailRequest,
    onChunk: (String) -> Unit,
    callback: AiArSseService.DetailCallback,
): AiArSseService.RequestHandle {
    val base64Image = base64Encoder(request.jpegBytes)
    return when (request.lane) {
        DetectionLane.ITEM -> aiArSseService.requestDeepAnalysis(
            base64Image = base64Image,
            onChunk = onChunk,
            callback = callback,
        )
        DetectionLane.SCENE -> aiArSseService.requestGeneralDeepAnalysis(
            base64Image = base64Image,
            onChunk = onChunk,
            callback = callback,
        )
    }
}
```

- [ ] **Step 6: Run service tests**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.OnlineHazardDetectionServiceTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionService.kt app/src/test/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionServiceTest.kt
git commit -m "refactor: move online detection encoding into gateway"
```

---

### Task 3: Add Local Trigger Detection Service

**Files:**
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/LocalTriggerDetectionService.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/LocalTriggerDetectionServiceTest.kt`

**Interfaces:**
- Produces: `LocalTriggerDetectionService.detect(request, callback): AiArSseService.RequestHandle`
- Produces: `LocalTriggerDetectionService.NativeEngine`
- Consumes: `OnlineHazardDetectionService.DetectionRequest`
- Consumes: `AiArSseService.DetectCallback`

- [ ] **Step 1: Write failing tests**

Create `app/src/test/java/com/rokid/glass/hiddenrisk/LocalTriggerDetectionServiceTest.kt`:

```kotlin
package com.rokid.glass.hiddenrisk

import com.rokid.glass.workflow.InspectionWorkflowSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTriggerDetectionServiceTest {

    @After
    fun tearDown() {
        InspectionWorkflowSession.clear()
    }

    @Test
    fun detect_skipsWithoutPlaceCodeAndDoesNotRunEngine() {
        InspectionWorkflowSession.clear()
        val engine = FakeNativeEngine()
        val events = mutableListOf<String>()
        val service = LocalTriggerDetectionService(
            nativeEngine = engine,
            bitmapDecoder = { error("decoder should not run") },
            mainPoster = { it.run() },
        )

        service.detect(detectionRequest(), callback(events))

        assertEquals(listOf("success:false:"), events)
        assertFalse(engine.loadCalled)
        assertFalse(engine.detectCalled)
    }

    @Test
    fun detect_returnsLabelsWhenNativeStatsContainDetections() {
        setPlaceCode()
        val engine = FakeNativeEngine(
            stats = NativeInferenceStats(
                HiddenRiskNcnn.BACKEND_GPU,
                "System Vulkan",
                HiddenRiskNcnn.GPU_PROFILE_BALANCED_FP16,
                "Balanced FP16",
                640,
                "GPU",
                640,
                640,
                18L,
                "",
                0,
                "",
                1,
                1,
                arrayOf(DetectionResult("煤炉", 0f, 0f, 10f, 10f, 0.88f, 1)),
            ),
        )
        val events = mutableListOf<String>()
        val service = LocalTriggerDetectionService(
            nativeEngine = engine,
            bitmapDecoder = { FakeBitmapToken },
            mainPoster = { it.run() },
        )

        service.detect(detectionRequest(), callback(events))

        assertTrue(engine.loadCalled)
        assertTrue(engine.detectCalled)
        assertEquals(listOf("success:true:煤炉"), events)
    }

    @Test
    fun detect_failureWhenBitmapDecodeFails() {
        setPlaceCode()
        val events = mutableListOf<String>()
        val service = LocalTriggerDetectionService(
            nativeEngine = FakeNativeEngine(),
            bitmapDecoder = { null },
            mainPoster = { it.run() },
        )

        service.detect(detectionRequest(), callback(events))

        assertEquals(listOf("failure:本地触发图片解码失败"), events)
    }

    @Test
    fun detect_cancelSuppressesCallback() {
        setPlaceCode()
        val events = mutableListOf<String>()
        lateinit var deferred: Runnable
        val service = LocalTriggerDetectionService(
            nativeEngine = FakeNativeEngine(),
            bitmapDecoder = { FakeBitmapToken },
            mainPoster = { deferred = it },
        )

        val handle = service.detect(detectionRequest(), callback(events))
        handle.cancel()
        deferred.run()

        assertTrue(events.isEmpty())
    }

    private fun detectionRequest(): OnlineHazardDetectionService.DetectionRequest {
        return OnlineHazardDetectionService.DetectionRequest(
            epoch = 1L,
            requestId = 9L,
            jpegBytes = byteArrayOf(1, 2, 3),
        )
    }

    private fun setPlaceCode() {
        InspectionWorkflowSession.enterpriseInfo =
            InspectionWorkflowSession.EnterpriseInfo(
                companyName = "test",
                siteName = "test",
                inspectorName = "test",
                qrContent = "test",
                placeCode = "PLACE-001",
            )
    }

    private fun callback(events: MutableList<String>): AiArSseService.DetectCallback {
        return object : AiArSseService.DetectCallback {
            override fun onOpened(handle: AiArSseService.RequestHandle) = Unit

            override fun onSuccess(
                handle: AiArSseService.RequestHandle,
                hasHazard: Boolean,
                fullText: String,
                labels: List<String>,
            ) {
                events += "success:$hasHazard:${labels.joinToString()}"
            }

            override fun onFailure(handle: AiArSseService.RequestHandle, message: String) {
                events += "failure:$message"
            }
        }
    }

    private object FakeBitmapToken

    private class FakeNativeEngine(
        private val stats: NativeInferenceStats = NativeInferenceStats(
            HiddenRiskNcnn.BACKEND_GPU,
            "System Vulkan",
            HiddenRiskNcnn.GPU_PROFILE_BALANCED_FP16,
            "Balanced FP16",
            640,
            "GPU",
            640,
            640,
            10L,
            "",
            0,
            "",
            0,
            0,
            emptyArray(),
        ),
    ) : LocalTriggerDetectionService.NativeEngine {
        var loadCalled = false
        var detectCalled = false

        override fun ensureLoaded(): Boolean {
            loadCalled = true
            return true
        }

        override fun submitBitmap(bitmap: Any): Boolean {
            detectCalled = true
            return true
        }

        override fun latestStats(): NativeInferenceStats = stats
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.LocalTriggerDetectionServiceTest"
```

Expected: FAIL with unresolved reference `LocalTriggerDetectionService`.

- [ ] **Step 3: Implement service**

Create `app/src/main/java/com/rokid/glass/hiddenrisk/LocalTriggerDetectionService.kt`:

```kotlin
package com.rokid.glass.hiddenrisk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.content.res.AssetManager
import com.rokid.glass.workflow.InspectionWorkflowSession
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 本地触发检测服务。
 * 将 NCNN 小模型包装成与 /ai/auto 等价的触发 provider，页面侧仍复用现有 DetectCallback 契约。
 */
internal class LocalTriggerDetectionService(
    assetManager: AssetManager? = null,
    private val nativeEngine: NativeEngine = InspectionSessionNativeEngine(
        requireNotNull(assetManager) { "LocalTriggerDetectionService requires AssetManager" },
    ),
    private val bitmapDecoder: (ByteArray) -> Any? = { bytes ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    },
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
    private val mainPoster: ((Runnable) -> Unit) = { runnable ->
        Handler(Looper.getMainLooper()).post(runnable)
    },
) {
    interface NativeEngine {
        fun ensureLoaded(): Boolean
        fun submitBitmap(bitmap: Any): Boolean
        fun latestStats(): NativeInferenceStats?
    }

    fun detect(
        request: OnlineHazardDetectionService.DetectionRequest,
        callback: AiArSseService.DetectCallback,
    ): AiArSseService.RequestHandle {
        val handle = AiArSseService.RequestHandle(taskId = "local-${request.requestId}")
        val placeCode = InspectionWorkflowSession.enterpriseInfo?.placeCode?.trim().orEmpty()
        if (placeCode.isBlank()) {
            postSuccess(handle, callback, hasHazard = false, fullText = "", labels = emptyList())
            return handle
        }
        worker.execute {
            val bitmap = bitmapDecoder(request.jpegBytes)
            if (bitmap == null) {
                postFailure(handle, callback, "本地触发图片解码失败")
                return@execute
            }
            val loaded = runCatching { nativeEngine.ensureLoaded() }.getOrDefault(false)
            if (!loaded) {
                postFailure(handle, callback, "本地触发模型加载失败")
                recycleBitmap(bitmap)
                return@execute
            }
            val success = runCatching { nativeEngine.submitBitmap(bitmap) }.getOrDefault(false)
            val stats = runCatching { nativeEngine.latestStats() }.getOrNull()
            recycleBitmap(bitmap)
            if (!success) {
                postFailure(handle, callback, stats?.errorMessage?.takeIf { it.isNotBlank() } ?: "本地触发推理失败")
                return@execute
            }
            val labels = stats
                ?.detections
                ?.mapNotNull { it.label?.trim()?.takeIf(String::isNotBlank) }
                ?.distinct()
                .orEmpty()
            val hasHazard = labels.isNotEmpty()
            val fullText = if (hasHazard) {
                labels.joinToString(prefix = "local_trigger:", separator = ",")
            } else {
                ""
            }
            postSuccess(handle, callback, hasHazard, fullText, labels)
        }
        return handle
    }

    fun shutdown() {
        worker.shutdownNow()
    }

    private fun postSuccess(
        handle: AiArSseService.RequestHandle,
        callback: AiArSseService.DetectCallback,
        hasHazard: Boolean,
        fullText: String,
        labels: List<String>,
    ) {
        mainPoster(Runnable {
            if (!handle.isCanceled()) {
                callback.onSuccess(handle, hasHazard, fullText, labels)
            }
        })
    }

    private fun postFailure(
        handle: AiArSseService.RequestHandle,
        callback: AiArSseService.DetectCallback,
        message: String,
    ) {
        mainPoster(Runnable {
            if (!handle.isCanceled()) {
                callback.onFailure(handle, message)
            }
        })
    }

    private fun recycleBitmap(bitmap: Any) {
        (bitmap as? Bitmap)?.takeIf { !it.isRecycled }?.recycle()
    }

    private class InspectionSessionNativeEngine(
        private val assetManager: AssetManager,
    ) : NativeEngine {
        override fun ensureLoaded(): Boolean {
            return InspectionSession.createNcnnInstance() &&
                InspectionSession.loadModel(assetManager)
        }

        override fun submitBitmap(bitmap: Any): Boolean {
            val ncnn = InspectionSession.hiddenRiskNcnn ?: return false
            return ncnn.submitBitmap(bitmap as Bitmap)
        }

        override fun latestStats(): NativeInferenceStats? {
            return InspectionSession.hiddenRiskNcnn?.getLatestInferenceStats()
        }
    }
}
```

- [ ] **Step 4: Run local service tests**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.LocalTriggerDetectionServiceTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/LocalTriggerDetectionService.kt app/src/test/java/com/rokid/glass/hiddenrisk/LocalTriggerDetectionServiceTest.kt
git commit -m "feat: add local trigger detection service"
```

---

### Task 4: Wire Provider Selection Into Detection Flows

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionService.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/DeviceGuideActivity.kt`
- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionServiceTest.kt`

**Interfaces:**
- Produces: `OnlineHazardDetectionService.createDefaultRequestGateway(provider, aiArSseService, localTriggerDetectionService, base64Encoder)`
- Produces: `LocalTriggerRequestGateway`
- Consumes: `AutoDetectProvider`
- DeviceGuide consumes: `OnlineHazardDetectionService` for detection only; detail stays `AiArSseService.fetchInspectionGuide()`

- [ ] **Step 1: Write provider factory tests**

Add tests in `OnlineHazardDetectionServiceTest.kt`:

```kotlin
@Test
fun defaultGatewayFactory_usesHttpGatewayForHttpProvider() {
    val gateway = OnlineHazardDetectionService.createDefaultRequestGateway(
        provider = com.rokid.glass.config.AutoDetectProvider.HTTP,
        aiArSseService = AiArSseService(),
        localTriggerDetectionService = null,
        base64Encoder = { "encoded" },
    )

    assertEquals("SseRequestGateway", gateway.javaClass.simpleName)
}

@Test
fun defaultGatewayFactory_usesLocalGatewayForLocalProvider() {
    val gateway = OnlineHazardDetectionService.createDefaultRequestGateway(
        provider = com.rokid.glass.config.AutoDetectProvider.LOCAL_TRIGGER,
        aiArSseService = AiArSseService(),
        localTriggerDetectionService = LocalTriggerDetectionService(
            nativeEngine = FakeLocalEngine(),
            bitmapDecoder = { null },
            mainPoster = { it.run() },
        ),
        base64Encoder = { "encoded" },
    )

    assertEquals("LocalTriggerRequestGateway", gateway.javaClass.simpleName)
}
```

Add fake local engine near other test fakes:

```kotlin
private class FakeLocalEngine : LocalTriggerDetectionService.NativeEngine {
    override fun ensureLoaded(): Boolean = true
    override fun submitBitmap(bitmap: Any): Boolean = false
    override fun latestStats(): NativeInferenceStats? = null
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.OnlineHazardDetectionServiceTest"
```

Expected: FAIL with unresolved reference `createDefaultRequestGateway`.

- [ ] **Step 3: Add factory and local gateway**

In `OnlineHazardDetectionService.kt`, import:

```kotlin
import com.rokid.glass.config.AutoDetectProvider
```

In companion object, add:

```kotlin
internal fun createDefaultRequestGateway(
    provider: AutoDetectProvider = InspectionConfigRepository.get().aiInspection.autoDetectProvider,
    aiArSseService: AiArSseService = AiArSseService(),
    localTriggerDetectionService: LocalTriggerDetectionService? = null,
    base64Encoder: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) },
): RequestGateway {
    return when (provider) {
        AutoDetectProvider.HTTP -> SseRequestGateway(aiArSseService, base64Encoder)
        AutoDetectProvider.LOCAL_TRIGGER -> LocalTriggerRequestGateway(
            localTriggerDetectionService = requireNotNull(localTriggerDetectionService) {
                "LOCAL_TRIGGER provider requires LocalTriggerDetectionService"
            },
            detailGateway = SseRequestGateway(aiArSseService, base64Encoder),
        )
    }
}
```

Add local gateway class beside `SseRequestGateway`:

```kotlin
internal class LocalTriggerRequestGateway(
    private val localTriggerDetectionService: LocalTriggerDetectionService,
    private val detailGateway: RequestGateway,
) : RequestGateway {
    override fun identifyHazard(
        request: DetectionRequest,
        callback: AiArSseService.DetectCallback,
    ): AiArSseService.RequestHandle {
        return when (request.lane) {
            DetectionLane.ITEM -> localTriggerDetectionService.detect(request, callback)
            DetectionLane.SCENE -> detailGateway.identifyHazard(request, callback)
        }
    }

    override fun requestDeepAnalysis(
        request: DetailRequest,
        onChunk: (String) -> Unit,
        callback: AiArSseService.DetailCallback,
    ): AiArSseService.RequestHandle {
        return detailGateway.requestDeepAnalysis(request, onChunk, callback)
    }
}
```

Change default constructor parameter:

```kotlin
private val requestGateway: RequestGateway = createDefaultRequestGateway(),
```

- [ ] **Step 4: Use factory in AiInspectionActivity**

In `createOnlineHazardDetectionService()`, pass an explicit gateway:

```kotlin
requestGateway = OnlineHazardDetectionService.createDefaultRequestGateway(
    localTriggerDetectionService = LocalTriggerDetectionService(assets),
),
```

Keep the existing callback and timeout logic unchanged.

- [ ] **Step 5: Refactor DeviceGuideActivity detection through service**

Add property:

```kotlin
private val detectionService by lazy {
    OnlineHazardDetectionService(
        callback = object : OnlineHazardDetectionService.Callback {
            override fun onDetectionResult(
                request: OnlineHazardDetectionService.DetectionRequest,
                hasHazard: Boolean,
                rawText: String,
                labels: List<String>,
            ) {
                handleGuideDetectionResult(request, hasHazard)
            }

            override fun onDetectionFailure(
                request: OnlineHazardDetectionService.DetectionRequest,
                message: String,
            ) {
                handleGuideDetectionFailure(message)
            }

            override fun onDetectionDropped(
                request: OnlineHazardDetectionService.DetectionRequest,
                reason: String,
            ) {
                handleGuideDetectionFailure(reason)
            }

            override fun onDeepAnalysisChunk(
                request: OnlineHazardDetectionService.DetailRequest,
                accumulatedText: String,
            ) = Unit

            override fun onDeepAnalysisSuccess(
                request: OnlineHazardDetectionService.DetailRequest,
                fullText: String,
            ) = Unit

            override fun onDeepAnalysisFailure(
                request: OnlineHazardDetectionService.DetailRequest,
                message: String,
            ) = Unit
        },
        requestGateway = OnlineHazardDetectionService.createDefaultRequestGateway(
            localTriggerDetectionService = LocalTriggerDetectionService(assets),
        ),
        detectTimeoutMs = InspectionConfigRepository.get().network.aiAutoApi.detectTimeoutMs,
        detectConcurrencyLimit = 1,
    )
}
```

Add handlers:

```kotlin
private fun handleGuideDetectionResult(
    request: OnlineHazardDetectionService.DetectionRequest,
    hasHazard: Boolean,
) {
    if (request.requestId != activeDetectRequestId) return
    val payload = currentPayload ?: return
    activeDetectRequestId = 0L
    activeDetectHandle = null
    detectInFlight = false
    if (hasHazard) {
        showPromptPending(payload)
    } else {
        scheduleNextDetection(immediate = false)
    }
}

private fun handleGuideDetectionFailure(message: String) {
    activeDetectRequestId = 0L
    activeDetectHandle = null
    detectInFlight = false
    tvDetectingBottomHint.text = message.ifBlank {
        getString(R.string.device_guide_detect_failed)
    }
    scheduleNextDetection(immediate = false)
}
```

Add field:

```kotlin
private var activeDetectRequestId: Long = 0L
```

Replace the direct `detectSseService.identifyItemHazard()` call in the detection worker with:

```kotlin
val requestId = SystemClock.elapsedRealtime()
activeDetectRequestId = requestId
detectionService.submitDetection(
    OnlineHazardDetectionService.DetectionRequest(
        epoch = requestId,
        requestId = requestId,
        jpegBytes = payload.jpegBytes.copyOf(),
    ),
)
```

Keep `detectSseService.fetchInspectionGuide()` in `requestGuideDetails()` unchanged.

In `cancelActiveRequests()`, add:

```kotlin
detectionService.cancelAll()
activeDetectRequestId = 0L
```

In `onDestroy()`, add:

```kotlin
detectionService.shutdown()
```

- [ ] **Step 6: Run targeted tests**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.OnlineHazardDetectionServiceTest"
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.LocalTriggerDetectionServiceTest"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionService.kt app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt app/src/main/java/com/rokid/glass/hiddenrisk/DeviceGuideActivity.kt app/src/test/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionServiceTest.kt
git commit -m "feat: route auto detection through local trigger provider"
```

---

### Task 5: Build Verification And Documentation Touch-Up

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/README.md`
- No change: `scripts/android/CLAUDE.md`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: verified `standardDebug` and `localTrigerDebug` builds.

- [ ] **Step 1: Update hiddenrisk README**

In `app/src/main/java/com/rokid/glass/hiddenrisk/README.md`, update the `/ai/auto` flow section to include:

```markdown
`localTriger` 变体中，item `/ai/auto` 的前置触发判断由 `LocalTriggerDetectionService`
本地 NCNN 小模型平替；命中后仍复用现有 `/ai/deep`，设备指引详情仍复用 `/ai/device`。
缺少 `placeCode` 时继续跳过触发检测，不调用 HTTP，也不运行本地模型。
```

- [ ] **Step 2: Run unit tests for localTriger**

Run:

```bash
./gradlew :app:testLocalTrigerDebugUnitTest
```

Expected: PASS.

- [ ] **Step 3: Run standard regression tests**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest
```

Expected: PASS.

- [ ] **Step 4: Build localTriger debug APK**

Run:

```bash
./gradlew :app:assembleLocalTrigerDebug
```

Expected: PASS and APK at `app/build/outputs/apk/localTriger/debug/app-localTriger-debug.apk`.

- [ ] **Step 5: Build standard debug APK**

Run:

```bash
./gradlew :app:assembleStandardDebug
```

Expected: PASS and APK at `app/build/outputs/apk/standard/debug/app-standard-debug.apk`.

- [ ] **Step 6: Verify there are no unintended files in commit**

Run:

```bash
git status --short
```

Expected: only intended docs changes are unstaged, or clean if all task changes were committed. Existing unrelated `scripts/java/AESUtil.py` modification may remain and must not be committed unless the user explicitly asks.

- [ ] **Step 7: Commit docs update**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/README.md
git commit -m "docs: document local trigger detection flow"
```
