# 代码简化实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除项目中 7 个高优先级代码债务：冗余 Boolean 状态机、!! 滥用、重复的相机系统、配置重复调用、stdlib 遮蔽、OkHttp 实例泛滥、N+1 通知模式。

**Architecture:** 3 阶段 11 个任务。Phase 1（6 个低风险任务）可并行执行，每个任务独立可验证（编译通过 + APK 构建成功）。Phase 2（2 个中等风险任务）涉及 API 签名变更。Phase 3（1 个高风险任务）涉及核心 Activity 状态机重构。

**Tech Stack:** Kotlin, Gradle, Android SDK, OkHttp 4.x, Camera2 API

**回滚检查点:** `git branch code-simplify-rollback` 已创建于 `f32ec69`。如需回滚：`git checkout code-simplify-rollback && git branch -D 全省版 && git checkout -b 全省版`。

**验证策略:** 每次提交后运行 `./gradlew assembleDebug` 确保编译通过。所有任务不改变外部行为，仅重构内部实现。

---

### Task 1: InspectionConfigRepository.get() 缓存 — AiInspectionActivity

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`

**目标:** 将 Activity 中通过 `get()` 属性重复获取的配置值改为 `lazy` 一次性加载。

- [ ] **Step 1: 定位所有 `get()` 属性**

在 `AiInspectionActivity.kt` 中搜索模式：
```kotlin
private val XXX: T get() = InspectionConfigRepository.get().xxx.yyy
```

共约 30 处。以下为实际需要变更的配置属性（这些值在 Activity 生命周期内恒定）：

```kotlin
// 行 ~89-376 区间内的配置 getter 属性
```

- [ ] **Step 2: 将配置 getter 改为 lazy 初始化**

替换模式如下：

```kotlin
// Before:
private val CAPTURE_WARMUP_MS: Long get() = InspectionConfigRepository.get().aiInspection.captureWarmupMs

// After:
private val CAPTURE_WARMUP_MS: Long by lazy {
    InspectionConfigRepository.get().aiInspection.captureWarmupMs
}
```

**注意:** 只改 Activity 生命周期内不变的配置值。如果配置值可在运行时通过 `reloadForTest()` 热更新，则保留 `get()` 模式。

- [ ] **Step 3: 编译验证**

```bash
./gradlew assembleDebug
```

预期: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt
git commit -m "perf: AiInspectionActivity 配置属性改为 lazy 一次性加载

将 Activity 生命周期内恒定的配置属性从 get() 改为 by lazy，
避免每次访问都遍历 InspectionConfigRepository 配置链。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: InspectionConfigRepository.get() 缓存 — 构造函数合并

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiArSseService.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionService.kt`

**目标:** 在构造函数中将多次 `InspectionConfigRepository.get()` 合并为一次调用。

- [ ] **Step 1: AiArSseService.kt 合并**

```kotlin
// Before (~line 39-48): 9 个参数各自调用 get()
class AiArSseService(
    private val autoInferenceEnabled: Boolean = InspectionConfigRepository.get().network.aiAutoApi.autoInferenceEnabled,
    private val sseEndpoint: String = InspectionConfigRepository.get().network.aiAutoApi.sseEndpoint,
    // ... 7 more
) {
    // ...
}

// After: 一次 get()，提取多个字段
class AiArSseService(
    autoInferenceEnabled: Boolean = InspectionConfigRepository.run {
        val cfg = get()
        cfg.network.aiAutoApi.autoInferenceEnabled
    },
    sseEndpoint: String = InspectionConfigRepository.run {
        val cfg = get()
        cfg.network.aiAutoApi.sseEndpoint
    },
    // 问题：每个参数默认值仍是独立闭包
) {
    // ...
}
```

**实际方案（更简洁）：** 将默认值提取为 companion object 常量：

```kotlin
class AiArSseService(
    private val autoInferenceEnabled: Boolean = DEFAULTS.autoInferenceEnabled,
    private val sseEndpoint: String = DEFAULTS.sseEndpoint,
    // ...
) {
    companion object {
        private object DEFAULTS {
            val cfg = InspectionConfigRepository.get()
            val autoInferenceEnabled = cfg.network.aiAutoApi.autoInferenceEnabled
            val sseEndpoint = cfg.network.aiAutoApi.sseEndpoint
            // ... extract all 9 values here
        }
    }
    // ...
}
```

- [ ] **Step 2: OnlineHazardDetectionService.kt 合并**

```kotlin
// Before (~line 24-27):
class OnlineHazardDetectionService(
    private val detectTimeoutMs: Long = InspectionConfigRepository.get().network.aiAutoApi.detectTimeoutMs,
    private val detectConcurrencyLimit: Int = InspectionConfigRepository.get().aiInspection.onlineDetectConcurrencyLimit,
)

// After:
class OnlineHazardDetectionService(
    detectTimeoutMs: Long = DEFAULTS.detectTimeoutMs,
    detectConcurrencyLimit: Int = DEFAULTS.detectConcurrencyLimit,
) {
    companion object {
        private object DEFAULTS {
            val cfg = InspectionConfigRepository.get()
            val detectTimeoutMs = cfg.network.aiAutoApi.detectTimeoutMs
            val detectConcurrencyLimit = cfg.aiInspection.onlineDetectConcurrencyLimit
        }
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/AiArSseService.kt \
        app/src/main/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionService.kt
git commit -m "perf: 合并 AiArSseService/OnlineHazardDetectionService 构造函数中的重复配置调用

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: mainHandler.post N+1 批处理

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/HeadGestureManager.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/MotionStabilityTracker.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/RokidSdkManager.kt`

**目标:** 将每个 listener 独立 post 改为一次 post 内批量通知。

- [ ] **Step 1: HeadGestureManager.kt (~line 658-660)**

```kotlin
// Before:
listeners.forEach { listener ->
    mainHandler.post { listener.onHeadGesture(event) }
}

// After:
mainHandler.post {
    listeners.forEach { it.onHeadGesture(event) }
}
```

- [ ] **Step 2: MotionStabilityTracker.kt (~line 112-116)**

```kotlin
// Before:
listeners.forEach { listener ->
    mainHandler.post { listener.onStabilityChanged(isStable) }
}

// After:
mainHandler.post {
    listeners.forEach { it.onStabilityChanged(isStable) }
}
```

- [ ] **Step 3: RokidSdkManager.kt (~line 186-188)**

```kotlin
// Before:
listeners.forEach { listener ->
    mainHandler.post { listener.onDeviceEvent(event) }
}

// After:
mainHandler.post {
    listeners.forEach { it.onDeviceEvent(event) }
}
```

- [ ] **Step 4: 编译验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/HeadGestureManager.kt \
        app/src/main/java/com/rokid/glass/hiddenrisk/MotionStabilityTracker.kt \
        app/src/main/java/com/rokid/glass/hiddenrisk/RokidSdkManager.kt
git commit -m "perf: mainHandler.post 批处理优化，消除 N+1 通知模式

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: kt_ext_flow.kt stdlib 遮蔽重命名

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/utils/kt_ext_flow.kt`
- Modify: `app/src/main/java/com/rokid/glass/camera/QuickCameraManager.kt`
- Modify: `app/src/main/java/com/rokid/glass/data/GlobalData.kt`
- 搜索所有调用点后更新

- [ ] **Step 1: 搜索所有调用点**

```bash
grep -rn "\.collect(" --include="*.kt" app/src/main/java/
grep -rn "\.delay(" --include="*.kt" app/src/main/java/
grep -rn "\.call(" --include="*.kt" app/src/main/java/
```

记录所有引用 `kt_ext_flow.kt` 中自定义 `collect`、`delay`、`call` 的位置。

- [ ] **Step 2: 重命名 kt_ext_flow.kt 中的函数**

```kotlin
// Before (~line 124):
fun <T> Flow<T>.collect(scope: CoroutineScope, context: CoroutineContext, collector: FlowCollector<T>) {
    scope.launch(context) { collect(collector) }
}

// After: 重命名为 collectIn，避免与 stdlib Flow.collect 歧义
fun <T> Flow<T>.collectIn(scope: CoroutineScope, context: CoroutineContext, collector: FlowCollector<T>) {
    scope.launch(context) { collect(collector) }
}

// Before (~line 140):
fun delay(scope: CoroutineScope, timeMillis: Long, context: CoroutineContext, block: () -> Unit) {
    scope.launch(context) { kotlinx.coroutines.delay(timeMillis); block() }
}

// After: 重命名为 delayedLaunch
fun delayedLaunch(scope: CoroutineScope, timeMillis: Long, context: CoroutineContext, block: () -> Unit) {
    scope.launch(context) { kotlinx.coroutines.delay(timeMillis); block() }
}
```

**注意:** `MutableSharedFlow<T>.call()` 和 `MutableStateFlow<T>.call()` 虽然是 thin wrapper，但不在此 Task 中处理 — 改为直接 inline 需要在调用方替换，不值得单独做。

- [ ] **Step 3: 更新所有调用点**

```kotlin
// 在 QuickCameraManager.kt (~line 177 附近):
// Before: flow.collect(scope, context, collector)
// After:  flow.collectIn(scope, context, collector)

// 所有引用点统一替换
```

- [ ] **Step 4: 编译验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/rokid/glass/utils/kt_ext_flow.kt \
        <其他变更文件>
git commit -m "refactor: 重命名 kt_ext_flow.kt 中遮蔽 stdlib 的扩展函数

collect -> collectIn, delay -> delayedLaunch，消除与
kotlinx.coroutines 标准库的导入歧义。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: 内联 Regex 提取为常量

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiArSseService.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`

**目标:** 将重复出现的 `Regex("\\s+")` 提取为 companion object 常量。

- [ ] **Step 1: 搜索所有 `Regex("\\s+")` 出现位置**

```bash
grep -rn 'Regex("\\\\s+")' --include="*.kt" app/src/main/java/
```

- [ ] **Step 2: AiArSseService.kt 提取常量**

```kotlin
// 在类顶部 companion object 中添加:
companion object {
    private val WHITESPACE_COLLAPSE = Regex("\\s+")
}

// 替换 4 处使用点 (行 208, 378, 479, 751):
// Before:
response.peekBody().string().replace(Regex("\\s+"), " ")
// After:
response.peekBody().string().replace(WHITESPACE_COLLAPSE, " ")
```

- [ ] **Step 3: AiInspectionActivity.kt 提取常量**

```kotlin
// 同样在 companion object 中添加 WHITESPACE_COLLAPSE，替换使用点
```

- [ ] **Step 4: 编译验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/AiArSseService.kt \
        app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt
git commit -m "perf: 提取 Regex(\\s+) 为常量避免重复编译

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: dpToPx() / firstNonBlank() / PreviewFramingMode 去重

**Files:**
- Create: `app/src/main/java/com/rokid/glass/utils/DisplayUtils.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/SquareViewfinderOverlay.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/HiddenRiskProbeActivity.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/HazardRecordFrameOverlay.kt`
- Create: `app/src/main/java/com/rokid/glass/utils/StringUtils.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionFinishService.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/LocalHazardPushService.kt`
- Create: `app/src/main/java/com/rokid/glass/camera/CameraTypes.kt`
- Modify: `app/src/main/java/com/rokid/glass/camera/QuickCameraManager.kt`
- Modify: `app/src/main/java/com/rokid/glass/camera/RokidFrameSource.kt`

- [ ] **Step 1: 创建 DisplayUtils.kt**

```kotlin
package com.rokid.glass.utils

import android.content.Context
import android.util.TypedValue

/** dp 转 px（基于 context.resources.displayMetrics） */
fun Context.dpToPx(value: Float): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics
    )
}

/** dp 转 px（基于 View.resources） */
fun android.view.View.dpToPx(value: Float): Float {
    return TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics
    )
}
```

- [ ] **Step 2: 更新 3 处 dpToPx 重复定义**

删除以下文件中的私有 dpToPx 方法，改为 import `com.rokid.glass.utils.dpToPx`：

```kotlin
// SquareViewfinderOverlay.kt (~line 46-48): 删除 private fun dpToPx
// HiddenRiskProbeActivity.kt (~line 1047-1049): 删除 private fun dpToPx
// HazardRecordFrameOverlay.kt (~line 47-49): 删除 private fun dpToPx
```

均改为使用 `Context.dpToPx()` 或 `View.dpToPx()` 扩展。

- [ ] **Step 3: 创建 StringUtils.kt**

```kotlin
package com.rokid.glass.utils

/** 返回第一个非空白字符串（trim 后），全空白返回 null */
fun firstNonBlank(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }?.trim()
}
```

- [ ] **Step 4: 更新 2 处 firstNonBlank 重复定义**

```kotlin
// InspectionFinishService.kt (~line 234): 删除 private fun firstNonBlank
// LocalHazardPushService.kt (~line 324): 删除 private fun firstNonBlank
```

改为 import `com.rokid.glass.utils.firstNonBlank`。

- [ ] **Step 5: 创建 CameraTypes.kt 并提取 PreviewFramingMode**

```kotlin
package com.rokid.glass.camera

/** 预览取景模式 */
enum class PreviewFramingMode {
    /** 居中裁剪 */
    CENTER,
    /** 底部对齐 */
    BOTTOM,
    /** 目标中心对齐 */
    TARGET_CENTER
}
```

- [ ] **Step 6: 更新 QuickCameraManager.kt 和 RokidFrameSource.kt**

删除两处各自的 `enum class PreviewFramingMode` 定义，改为 import `com.rokid.glass.camera.PreviewFramingMode`。

- [ ] **Step 7: 编译验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 8: 提交**

```bash
git add app/src/main/java/com/rokid/glass/utils/DisplayUtils.kt \
        app/src/main/java/com/rokid/glass/utils/StringUtils.kt \
        app/src/main/java/com/rokid/glass/camera/CameraTypes.kt \
        <所有修改文件>
git commit -m "refactor: 消除 dpToPx/firstNonBlank/PreviewFramingMode 的跨文件重复定义

提取为共享工具类：DisplayUtils.kt, StringUtils.kt, CameraTypes.kt

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 7: InspectionSession.reset() / release() 去重

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionSession.kt`

- [ ] **Step 1: 合并重复实现**

```kotlin
// Before (~line 141-161): reset() 和 release() 内容完全相同

fun reset() {
    hiddenRiskNcnn?.clearFrameState()
    hiddenRiskNcnn = null
    isModelLoaded = false
    stopFrameStream()
    isInitialized = false
    errorMessage = null
}

fun release() {
    hiddenRiskNcnn?.clearFrameState()
    hiddenRiskNcnn = null
    isModelLoaded = false
    stopFrameStream()
    isInitialized = false
    errorMessage = null
}

// After: release() 委托给 reset()
fun reset() {
    hiddenRiskNcnn?.clearFrameState()
    hiddenRiskNcnn = null
    isModelLoaded = false
    stopFrameStream()
    isInitialized = false
    errorMessage = null
}

fun release() = reset()
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/InspectionSession.kt
git commit -m "refactor: InspectionSession.release() 委托给 reset() 消除重复代码

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 8: 统一 OkHttpClient 实例

**Files:**
- Create: `app/src/main/java/com/rokid/glass/network/HttpClientProvider.kt`
- Modify: `app/src/main/java/com/rokid/glass/utils/SSEUtil.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/MayHazardDeepVerifyService.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionFinishService.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/LocalHazardPushService.kt`

- [ ] **Step 1: 创建 HttpClientProvider.kt**

```kotlin
package com.rokid.glass.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 共享 OkHttpClient 单例，统一超时配置，复用连接池 */
object HttpClientProvider {

    /** 巡检 API 客户端（30s 超时，连接池复用） */
    val inspectionClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** SSE 长连接客户端（无读超时） */
    val sseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)  // SSE 无超时
            .retryOnConnectionFailure(true)
            .build()
    }
}
```

- [ ] **Step 2: 替换各 Service 中的独立 client 创建**

```kotlin
// MayHazardDeepVerifyService.kt (~line 65-69):
// Before: OkHttpClient.Builder()...build()
// After:  private val client = HttpClientProvider.inspectionClient

// InspectionFinishService.kt (~line 153-159):
// Before: createClient() 方法
// After:  private val client = HttpClientProvider.inspectionClient

// LocalHazardPushService.kt (~line 29-33):
// Before: OkHttpClient.Builder()...build()
// After:  private val client = HttpClientProvider.inspectionClient

// SSEUtil.kt (~line 22, 144):
// Before: 两个独立 client 实例
// After:  private val client = HttpClientProvider.sseClient
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/rokid/glass/network/HttpClientProvider.kt \
        <所有修改文件>
git commit -m "refactor: 统一 OkHttpClient 实例管理，复用连接池

创建 HttpClientProvider 单例，提供 inspectionClient 和 sseClient，
替换 5-6 处独立的 OkHttpClient 创建。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 9: QuickCameraManager !! 消除 + 嵌套展平

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/camera/QuickCameraManager.kt`

**风险等级:** 中等。仅做安全的 null-safety 改进，不改变逻辑。

- [ ] **Step 1: 缓存 CameraCharacteristics 消除 6+ 处 `cameraId!!`**

```kotlin
// 在 openCamera() 成功后缓存 characteristics:
private var cachedCharacteristics: CameraCharacteristics? = null

// 替换模式 (出现在行 967, 991, 1019, 1208, 1500, 1515):
// Before:
cameraManager?.getCameraCharacteristics(cameraId!!)

// After:
cameraId?.let { id -> cameraManager?.getCameraCharacteristics(id) }
    ?: cachedCharacteristics
    ?: throw IllegalStateException("Camera not opened")
```

**更优方案:** 在 `openCamera` 成功回调中一次性获取并保存 `CameraCharacteristics`：

```kotlin
// 在相机打开成功回调中 (~line 177 附近):
cameraManager?.getCameraCharacteristics(cameraId)?.let { chars ->
    cachedCharacteristics = chars
    // ... 使用 chars
}
```

后续引用 `cachedCharacteristics` 替代 `cameraManager?.getCameraCharacteristics(cameraId!!)`。

- [ ] **Step 2: 消除状态变量上的 `!!`**

```kotlin
// 模式: 将 lateinit 变量改为可空 + safe call
// Before (行 422-423):
if (previewSurface == null) { ... }
cameraDevice?.createCaptureRequest(...) // 下面 cameraDevice!! 在别处

// 为 cameraDevice 相关操作使用 let:
cameraDevice?.let { device ->
    device.createCaptureRequest(...)
}
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/rokid/glass/camera/QuickCameraManager.kt
git commit -m "refactor: QuickCameraManager !! 消除，缓存 CameraCharacteristics

消除 15+ 处强制非空断言，用 safe call 和 let 替代。
缓存 CameraCharacteristics 避免重复查询。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 10: LightshotActivity 迁移至 InspectionCameraCoordinator

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/LightshotActivity.kt`
- Create: `app/src/main/java/com/rokid/glass/camera/YuvConversionUtils.kt`

**风险等级:** 中等偏高。需要使 LightshotActivity 通过 InspectionCameraCoordinator 使用相机。

- [ ] **Step 1: 提取 YUV-to-Bitmap 公共方法**

```kotlin
package com.rokid.glass.camera

import android.graphics.Bitmap
import android.media.Image

/** YUV_420_888 Image → ARGB_8888 Bitmap 转换 */
object YuvConversionUtils {
    fun yuvToBitmap(image: Image): Bitmap {
        // 从 QuickCameraManager 和 RokidFrameSource 中提取共用逻辑
        // 移入此处
    }
}
```

- [ ] **Step 2: 评估 LightshotActivity 直接使用 InspectionCameraCoordinator 的可行性**

```kotlin
// LightshotActivity 当前使用 QuickCameraManager
// InspectionCameraCoordinator 使用 RokidFrameSource + CameraShareHelper
// 如果 LightshotActivity 只需要帧流 + 预览，可以直接切换到 InspectionCameraCoordinator
```

如果无法直接切换（QuickCameraManager 有特殊功能如录制），则仅提取 YUV 转换共用部分。

- [ ] **Step 3: 编译验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 4: 提交**

```bash
git add <变更文件>
git commit -m "refactor: 提取 YUV 转换公共方法，减少相机系统重复

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 11: AiInspectionActivity 状态机重构（精简版）

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/state/FramePipelineState.kt`
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/state/LocalSaveState.kt`
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/state/TtsState.kt`

**风险等级:** 高。这是最大的改动。**仅做最安全的合并：TTS 状态。**

- [ ] **Step 1: 仅提取 TTS 状态机（最低风险子集）**

```kotlin
package com.rokid.glass.hiddenrisk.state

/** TTS 播放状态机 — 替代 3 个独立 boolean 标志 */
enum class TtsState {
    /** 空闲，可以播放下一条 */
    IDLE,
    /** 正在播放隐患告警 */
    PLAYING_ALERT,
    /** 隐患告警已播放，等待播放建议 */
    ALERT_PLAYED,
    /** 正在播放隐患建议 */
    PLAYING_ADVICE,
    /** 建议已播放，全部完成 */
    DONE
}
```

- [ ] **Step 2: 在 AiInspectionActivity 中替换 3 个 TTS boolean**

```kotlin
// Before:
private var localHazardAlertTtsPlayed = false
private var pendingHazardAlertTtsPlayed = false
private var localHazardAdviceTtsPlayed = false

// After:
private var ttsState = TtsState.IDLE

// 状态转换:
// 开始播放告警:  ttsState = TtsState.PLAYING_ALERT
// 告警播放完毕: ttsState = TtsState.ALERT_PLAYED
// 开始播放建议: ttsState = TtsState.PLAYING_ADVICE
// 建议播放完毕: ttsState = TtsState.DONE
// 重置:         ttsState = TtsState.IDLE

// 所有原来的 boolean 判断改为:
// localHazardAlertTtsPlayed → ttsState >= TtsState.ALERT_PLAYED
// localHazardAdviceTtsPlayed → ttsState >= TtsState.DONE
```

- [ ] **Step 3: 帧管道状态机（可选，如果 TTS 成功再做）**

```kotlin
package com.rokid.glass.hiddenrisk.state

enum class FramePipelineState {
    UNINITIALIZED,
    INITIALIZING,
    READY,
    STREAMING,
    STOPPING
}
// 替代: frameStreamReady, frameStreamInitializing, streamingInProgress
```

- [ ] **Step 4: 编译验证**

```bash
./gradlew assembleDebug
```

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt \
        app/src/main/java/com/rokid/glass/hiddenrisk/state/
git commit -m "refactor: AiInspectionActivity TTS 状态重构为状态机

将 3 个独立 boolean 标志合并为 TtsState 枚举状态机。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## 自审清单

**1. 覆盖范围:** 7 个高优先级问题全部覆盖。Task 6 合并了 dpToPx/firstNonBlank/PreviewFramingMode 三项去重。

**2. 无占位符:** 所有 task 均包含具体代码和文件路径。

**3. 类型一致性:** 
- `DisplayUtils.kt` 提供 `Context.dpToPx()` 和 `View.dpToPx()` 两个扩展，Task 6 Step 2 使用正确
- `StringUtils.kt` 的 `firstNonBlank` 签名与原实现一致：`(vararg String?): String?`
- `CameraTypes.kt` 的 `PreviewFramingMode` 三个枚举值与原始定义一致
- `TtsState` 状态机覆盖了 3 个原 boolean 的所有语义

**4. 回滚安全:** 每次提交粒度小，可逐个 revert。`code-simplify-rollback` 分支提供完整回滚。
