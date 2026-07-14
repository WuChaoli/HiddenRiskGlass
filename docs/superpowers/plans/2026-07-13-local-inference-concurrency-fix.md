# Local NCNN Inference Concurrency Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 串行化进程内所有 NCNN 模型加载、推理和释放操作，并在 JNI 层消除模型裸指针与全局 GPU instance 的生命周期竞态。

**Architecture:** Kotlin 新增进程级 `LocalInferenceCoordinator`，通过唯一单线程 executor 统一执行加载、Bitmap 推理、stats 读取与释放；所有页面和服务只通过异步协调器接口访问模型。JNI 使用独立生命周期互斥覆盖完整加载、完整推理和显式释放，现有状态锁仅保护诊断结果。

**Tech Stack:** Kotlin、Java/JNI、C++17、NCNN Vulkan、JUnit 4、Gradle Android Plugin、Rokid Glass Android 12。

## Global Constraints

- 固定使用输入尺寸 `640`、后端 `System Vulkan`、GPU Profile `Balanced FP16`。
- 不修改模型资产、检测后处理、阈值、NMS 或在线 SSE 协议。
- 所有模型加载、推理、stats 读取和释放必须经过唯一协调器。
- JNI 模型使用期间不得删除 `g_yolov8` 或销毁 GPU instance。
- 页面销毁只抑制过期回调，不中断正在执行的 JNI。
- 严格执行 TDD；不提交现有的 `local.properties`、`scripts/java/AESUtil.py` 和无关未跟踪文件。

---

### Task 1: 建立进程级推理协调器

**Files:**
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/LocalInferenceCoordinator.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/LocalInferenceCoordinatorTest.kt`

**Interfaces:**
- Consumes: `AssetManager`、Bitmap token、`NativeInferenceStats`。
- Produces: `ensureLoaded(assets, callback)`、`detect(assets, bitmap, callback)`、`release(callback)`、`DetectionOutcome`、可注入的 `NativeEngine` 与 `TaskExecutor`。

- [ ] **Step 1: 编写失败测试**

使用受控队列 executor 和 fake engine 覆盖：

```kotlin
@Test
fun twoDetectionsLoadOnceAndRunInOrder() {
    val executor = QueuedTaskExecutor()
    val engine = FakeNativeEngine()
    val coordinator = LocalInferenceCoordinator(executor) { engine }
    val results = mutableListOf<Boolean>()

    coordinator.detect(FakeAssets, BitmapToken("first")) { results += it.success }
    coordinator.detect(FakeAssets, BitmapToken("second")) { results += it.success }
    executor.runAll()

    assertEquals(1, engine.loadCount)
    assertEquals(
        listOf("load", "detect:first", "stats", "detect:second", "stats"),
        engine.calls,
    )
    assertEquals(listOf(true, true), results)
}

@Test
fun failedLoadRetriesOnNextRequest() {
    val engine = FakeNativeEngine(loadResults = ArrayDeque(listOf(false, true)))
    val coordinator = LocalInferenceCoordinator(ImmediateTaskExecutor) { engine }
    val results = mutableListOf<Boolean>()

    coordinator.ensureLoaded(FakeAssets) { results += it.success }
    coordinator.ensureLoaded(FakeAssets) { results += it.success }

    assertEquals(listOf(false, true), results)
    assertEquals(2, engine.loadCount)
}

@Test
fun releaseIsOrderedAndNextDetectionReloads() {
    val executor = QueuedTaskExecutor()
    val engine = FakeNativeEngine()
    val coordinator = LocalInferenceCoordinator(executor) { engine }

    coordinator.detect(FakeAssets, BitmapToken("before")) {}
    coordinator.release {}
    coordinator.detect(FakeAssets, BitmapToken("after")) {}
    executor.runAll()

    assertEquals(
        listOf("load", "detect:before", "stats", "release", "load", "detect:after", "stats"),
        engine.calls,
    )
}
```

- [ ] **Step 2: 确认红灯**

```bash
./gradlew :app:testLocalTrigerDebugUnitTest --tests "com.rokid.glass.hiddenrisk.LocalInferenceCoordinatorTest"
```

Expected: FAIL，缺少协调器及其类型。

- [ ] **Step 3: 实现最小协调器**

```kotlin
internal class LocalInferenceCoordinator(
    private val executor: TaskExecutor,
    private val engineFactory: () -> NativeEngine,
) {
    enum class LoadState { UNLOADED, LOADING, READY, FAILED }
    data class OperationResult(val success: Boolean, val errorMessage: String = "")
    data class DetectionOutcome(
        val success: Boolean,
        val stats: NativeInferenceStats?,
        val errorMessage: String,
    )

    interface NativeEngine {
        fun load(assets: Any): Boolean
        fun detect(bitmap: Any): Boolean
        fun latestStats(): NativeInferenceStats?
        fun release()
        fun errorMessage(): String?
    }

    interface TaskExecutor {
        fun execute(task: () -> Unit)
    }

    fun ensureLoaded(assets: Any, callback: (OperationResult) -> Unit)
    fun detect(assets: Any, bitmap: Any, callback: (DetectionOutcome) -> Unit)
    fun release(callback: (OperationResult) -> Unit = {})
}
```

生产单例使用唯一、命名为 `local-ncnn-coordinator` 的单线程 executor。状态只在该线程读写；`detect()` 在一个任务内完成加载、推理和 stats 读取；失败进入 `FAILED`，下一次请求允许重试；释放后清空 engine 并回到 `UNLOADED`。

- [ ] **Step 4: 确认绿灯并提交**

运行 Step 2 命令，Expected: 全部 PASS。

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/LocalInferenceCoordinator.kt app/src/test/java/com/rokid/glass/hiddenrisk/LocalInferenceCoordinatorTest.kt
git commit -m "feat: serialize local inference operations"
```

### Task 2: 将本地触发服务迁移到共享协调器

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/LocalTriggerDetectionService.kt`
- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/LocalTriggerDetectionServiceTest.kt`
- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionServiceTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `LocalInferenceCoordinator.detect()`。
- Produces: 保持 `detect(request, callback): RequestHandle`；`shutdown()` 仅关闭当前服务的回调交付。

- [ ] **Step 1: 重写失败测试**

删除测试对页面级 `worker` 和服务内 `NativeEngine` 的依赖，注入 fake coordinator gateway。新增：

```kotlin
@Test
fun twoServicesUseSameCoordinator() {
    setPlaceCode()
    val coordinator = FakeCoordinator()
    createService(coordinator).detect(detectionRequest(1L), callback(mutableListOf()))
    createService(coordinator).detect(detectionRequest(2L), callback(mutableListOf()))
    assertEquals(listOf(1L, 2L), coordinator.requestIds)
}

@Test
fun shutdownSuppressesResultWithoutCancelingNativeTask() {
    setPlaceCode()
    val coordinator = DeferredFakeCoordinator()
    val events = mutableListOf<String>()
    val service = createService(coordinator)

    service.detect(detectionRequest(), callback(events))
    service.shutdown()
    coordinator.completeSuccess(emptyStats())

    assertTrue(events.isEmpty())
    assertFalse(coordinator.taskCanceled)
}
```

保留无 `placeCode`、解码失败、handle cancel 和 label 映射测试。注入 bitmap recycler，断言成功、失败及 shutdown 后都恰好回收一次。

- [ ] **Step 2: 确认红灯**

```bash
./gradlew :app:testLocalTrigerDebugUnitTest --tests "com.rokid.glass.hiddenrisk.LocalTriggerDetectionServiceTest" --tests "com.rokid.glass.hiddenrisk.OnlineHazardDetectionServiceTest"
```

Expected: FAIL，现有服务仍拥有独立 executor。

- [ ] **Step 3: 最小迁移服务**

构造边界改为：

```kotlin
interface CoordinatorGateway {
    fun detect(
        assets: Any,
        bitmap: Any,
        callback: (LocalInferenceCoordinator.DetectionOutcome) -> Unit,
    )
}
```

删除 `worker`、`InspectionSessionNativeEngine` 和 `shutdownNow()`。通过 `AtomicBoolean closed` 抑制 shutdown 后回调；Bitmap 在协调器 completion 的 `finally` 中只回收一次。请求取消只抑制回调，不中断 native。

- [ ] **Step 4: 确认绿灯并提交**

运行 Step 2 命令，Expected: 两个测试类全部 PASS。

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/LocalTriggerDetectionService.kt app/src/test/java/com/rokid/glass/hiddenrisk/LocalTriggerDetectionServiceTest.kt app/src/test/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionServiceTest.kt
git commit -m "fix: share local trigger inference coordinator"
```

### Task 3: 在 JNI 层封闭模型与 GPU 生命周期

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/HiddenRiskNcnn.java`
- Modify: `app/src/main/jni/yolov8ncnn.cpp`

**Interfaces:**
- Consumes: 现有 load/submit 接口。
- Produces: `HiddenRiskNcnn.releaseModel(): void`；加载、推理、释放具备最终生命周期安全性。

- [ ] **Step 1: 保存当前危险模式证据**

```bash
rg -n "yolov8 = g_yolov8|destroy_gpu_instance|releaseModel" app/src/main/jni/yolov8ncnn.cpp
```

Expected: RGB 与 hardware-buffer 路径复制裸指针后脱锁使用；不存在 `releaseModel`。

- [ ] **Step 2: 实现生命周期锁与统一释放**

新增 `lifecycle_lock`。固定锁序为“生命周期锁 → 状态锁”。统一释放函数的语义为：

```cpp
static void release_model_with_lifecycle_lock()
{
    ncnn::MutexLockGuard lifecycle_guard(lifecycle_lock);
    YOLOv8* old_yolov8 = 0;
    {
        ncnn::MutexLockGuard state_guard(lock);
        old_yolov8 = g_yolov8;
        g_yolov8 = 0;
        g_loaded_backend_id = -1;
        g_loaded_gpu_profile = -1;
        g_loaded_target_size = 0;
        clear_latest_frame_state_locked();
    }
    delete old_yolov8;
    ncnn::destroy_gpu_instance();
}
```

`loadModel()` 的复用检查、旧模型释放、GPU 重建、候选加载与发布全程持有 lifecycle lock。RGB、NV21 和 hardware-buffer 推理从读取模型到 `detect()` 返回持续持锁，禁止复制后脱锁使用。

- [ ] **Step 3: 增加幂等释放入口**

Java 声明：

```java
public native void releaseModel();
```

JNI 入口和 `JNI_OnUnload()` 复用统一释放函数。连续调用两次必须安全。删除 `g_diagnostic_detect_in_flight` 的虚假防重入语义，或明确只用于日志。

- [ ] **Step 4: 构建并复查**

```bash
./gradlew :app:externalNativeBuildLocalTrigerDebug :app:assembleLocalTrigerDebug
rg -n "yolov8 = g_yolov8" app/src/main/jni/yolov8ncnn.cpp
rg -n "releaseModel|lifecycle_lock" app/src/main/jni/yolov8ncnn.cpp app/src/main/java/com/rokid/glass/hiddenrisk/HiddenRiskNcnn.java
```

Expected: `BUILD SUCCESSFUL`；第一条结构搜索无结果；Java/JNI 声明对称。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/HiddenRiskNcnn.java app/src/main/jni/yolov8ncnn.cpp
git commit -m "fix: guard native model lifecycle"
```

### Task 4: 迁移全部加载入口与会话释放

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionSession.kt`
- Modify: `app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/InspectionSessionInferenceLifecycleTest.kt`

**Interfaces:**
- Consumes: 共享协调器与 `releaseModel()`。
- Produces: `InspectionSession.ensureModelLoaded(assets, callback)`、异步 `release(callback)`；业务代码不再组合调用 `createNcnnInstance()+loadModel()`。

- [ ] **Step 1: 编写失败测试**

```kotlin
@Test
fun releaseClearsStateOnlyAfterNativeReleaseCompletes() {
    val gateway = DeferredSessionCoordinator(initiallyReady = true)
    InspectionSession.installCoordinatorForTest(gateway)

    InspectionSession.release {}
    assertTrue(InspectionSession.isModelLoaded)
    gateway.completeRelease()

    assertFalse(InspectionSession.isModelLoaded)
}

@Test
fun failedEnsureReportsErrorAndCanRetry() {
    val gateway = FakeSessionCoordinator(results = ArrayDeque(listOf(false, true)))
    val results = mutableListOf<Boolean>()
    InspectionSession.installCoordinatorForTest(gateway)

    InspectionSession.ensureModelLoaded(FakeAssets) { results += it }
    InspectionSession.ensureModelLoaded(FakeAssets) { results += it }

    assertEquals(listOf(false, true), results)
    assertTrue(InspectionSession.isModelLoaded)
}
```

测试结束恢复生产 coordinator，防止跨测试污染。

- [ ] **Step 2: 确认红灯**

```bash
./gradlew :app:testLocalTrigerDebugUnitTest --tests "com.rokid.glass.hiddenrisk.InspectionSessionInferenceLifecycleTest"
```

Expected: FAIL，现有 API 同步且 reset 不执行 native release。

- [ ] **Step 3: 实现异步会话接口**

```kotlin
fun ensureModelLoaded(assets: AssetManager, callback: (Boolean) -> Unit) {
    coordinator.ensureLoaded(assets) { result ->
        isModelLoaded = result.success
        errorMessage = result.errorMessage.ifBlank { null }
        callback(result.success)
    }
}

fun release(callback: () -> Unit = {}) {
    coordinator.release {
        isModelLoaded = false
        hiddenRiskNcnn = null
        isInitialized = false
        errorMessage = null
        callback()
    }
}
```

生产 native adapter 只在协调器线程创建唯一 `HiddenRiskNcnn` 并调用 load/release。

- [ ] **Step 4: 迁移全部直接调用点**

将下列入口改为异步等待 `ensureModelLoaded()` 后延续原页面流程：

- `AiInspectionMenuActivity.preloadInspectionSession()`
- `AiInspectionActivity.doInlineSessionInit()`
- `InspectionLoadingActivity` 模型初始化步骤
- 搜索发现的其他直接调用

```bash
rg -n "InspectionSession\.(createNcnnInstance|loadModel)" app/src/main/java
```

Expected: 无业务调用点。

- [ ] **Step 5: 验证并提交**

```bash
./gradlew :app:testLocalTrigerDebugUnitTest --tests "com.rokid.glass.hiddenrisk.InspectionSessionInferenceLifecycleTest" --tests "com.rokid.glass.hiddenrisk.LocalTriggerDetectionServiceTest" --tests "com.rokid.glass.hiddenrisk.OnlineHazardDetectionServiceTest"
git add app/src/main/java/com/rokid/glass/hiddenrisk/InspectionSession.kt app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt app/src/test/java/com/rokid/glass/hiddenrisk/InspectionSessionInferenceLifecycleTest.kt
git commit -m "fix: route model lifecycle through coordinator"
```

Expected: 全部 PASS；提交不包含无关文件。

### Task 5: 完整回归与真机门禁

**Files:**
- Create: `docs/superpowers/reports/2026-07-13-local-inference-concurrency-fix-validation.md`
- Modify only when evidence requires: Tasks 1-4 listed files

**Interfaces:**
- Consumes: 完整修复。
- Produces: 测试、APK、logcat、性能/内存和 crash buffer 验证证据。

- [ ] **Step 1: 快速与完整自动化门禁**

```bash
./gradlew :app:testLocalTrigerDebugUnitTest :app:testStandardDebugUnitTest :app:assembleLocalTrigerDebug
```

Expected: `BUILD SUCCESSFUL`，全部 JVM 测试通过，APK 位于 `app/build/outputs/apk/localTriger/debug/`。

- [ ] **Step 2: 设备检查并清空测试日志**

```bash
bash scripts/android/doctor.sh --device
/home/wuchaoli/.claude/skills/wsl-android-tools/scripts/win-adb.sh logcat -c
```

Expected: 设备 `1901092544019017` 在线。报告记录旧签名 `__kmp_affinity_initialize/SIGABRT` 和 `YOLOv8::load/SIGSEGV 0x8`。

- [ ] **Step 3: 安装并复现原始切页序列**

安装新 APK，人工执行：冷启动进入实时分析；模型加载期间返回菜单并立即进入设备指引；连续检测；结束巡检释放模型；再次进入触发重载。同时采集：

```bash
ADB=/home/wuchaoli/.claude/skills/wsl-android-tools/scripts/win-adb.sh
$ADB logcat -b all -v threadtime | rg "HiddenRiskNcnn|LocalInferenceCoordinator|SIGABRT|SIGSEGV|Fatal signal"
```

Expected: 每个会话一次实际加载；detect begin/end 成对且串行；没有 Fatal signal。

- [ ] **Step 4: 性能和内存回归**

连续运行至少 30 次本地检测，在第 1 次完成 GC 后和第 30 次后采集：

```bash
ADB=/home/wuchaoli/.claude/skills/wsl-android-tools/scripts/win-adb.sh
$ADB shell dumpsys meminfo com.rokid.glesse
$ADB shell dumpsys gfxinfo com.rokid.glesse framestats
```

报告记录加载耗时、detect elapsedMs、Java/native heap 与 TOTAL RSS。验收：没有单调持续增长；第 30 次稳定态 RSS 不超过第 1 次数据的 20%；推理耗时没有超过旧成功基线 20% 的持续退化。

- [ ] **Step 5: crash buffer 与 DropBox 门禁**

```bash
ADB=/home/wuchaoli/.claude/skills/wsl-android-tools/scripts/win-adb.sh
$ADB logcat -b crash -d -v threadtime
$ADB shell dumpsys dropbox --print data_app_native_crash | rg -n "com.rokid.glesse|libhiddenriskncnn|SIGABRT|SIGSEGV"
```

Expected: 测试窗口内没有新 crash 或 tombstone。

- [ ] **Step 6: 写报告、审计并提交**

报告包含命令、测试数量、APK 路径、SN、加载次数、30 次耗时摘要、meminfo 对比、crash buffer 结论和未执行项。

```bash
git diff --check
git status --short
git add docs/superpowers/reports/2026-07-13-local-inference-concurrency-fix-validation.md
git commit -m "test: validate local inference concurrency fix"
```

最终完成前必须使用 `superpowers:verification-before-completion`，任何门禁失败都回到对应任务修复并补测，不得把未执行项写成通过。
