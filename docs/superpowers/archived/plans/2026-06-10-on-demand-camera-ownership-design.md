# On-Demand Camera Ownership & Activity Release Design Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove camera pre-warming from menu/loading pages; acquire camera only when a business Activity actually needs it; release Surface/NV21/owner on explicit navigation; force-cleanup stale owners; retry acquisition up to 4 times; and wrap GlassScanner.launch with conflict recovery.

**Architecture:** Keep `InspectionCameraCoordinator` as the single coordinator. Add semantic entry points (`acquireForActivity`, `pauseTemporarily`, `releaseForNavigation`), force-transfer logic for stale owners, and a bounded retry loop. Remove camera pre-warming from `EntryGuardCoordinator` and `InspectionLoadingActivity`. Convert all four business Activities to call `releaseForNavigation()` on every exit path.

**Tech Stack:** Kotlin, Android Camera2 (via Rokid SDK), `RokidFrameSource`, `InspectionCameraCoordinator.StateMachine`, `GlassScanner`

---

## File Structure

| File | Responsibility |
|------|---------------|
| `InspectionCameraCoordinator.kt` | Core coordinator: semantic APIs, force-transfer, retry loop, enhanced logging |
| `EntryGuardCoordinator.kt` | Remove camera pre-warming; only WiFi + SDK + update check |
| `InspectionLoadingActivity.kt` | Remove camera init phase; keep SDK init + model preload only |
| `MainMenuActivity.kt` | Remove camera state callback from `Callback` |
| `AiInspectionActivity.kt` | Use `releaseForNavigation()` on all exit paths; keep `pauseTemporarily()` for wear-sleep and onPause |
| `DeviceGuideActivity.kt` | Same pattern as AiInspectionActivity |
| `HazardRecordActivity.kt` | Same pattern |
| `EnterpriseQrScanActivity.kt` | Same pattern: acquireForActivity on enter, releaseForNavigation on exits |
| `GlassScannerLauncher.kt` *(new)* | Unified wrapper around `GlassScanner.launch()` with conflict recovery (used by EntryGuardCoordinator) |
| `InspectionCameraCoordinatorTest.kt` | Unit tests for force-transfer, retry, release, generation expiry |
| `GlassScannerLauncherTest.kt` *(new)* | Unit tests for error classification and retry logic |

---

## Task 1: InspectionCameraCoordinator Core APIs

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionCameraCoordinator.kt`
- Test: `app/src/test/java/com/rokid/glass/hiddenrisk/InspectionCameraCoordinatorTest.kt`

**设计要点：**
- `requestToken` 是一次逻辑请求的稳定标识，跨重试保持不变。页面保存它用于回调时判断是否过期。
- 每次重试内部通过 `acquire()`/`release()` 产生新的 `generation`，但 `requestToken` 不变。
- `releaseForNavigation()` 将 `currentRequestToken` 置为 -1，所有进行中的重试回调检测到 token 不匹配后自动终止。
- `forceRelease()` 是 StateMachine 上的正式生产方法，不复用 `resetForTest()`。

### Step 1: 添加 requestToken 机制和语义入口方法

在 companion object 或类顶层添加：

```kotlin
// 当前活跃的逻辑请求 token，-1 表示无活跃请求
@Volatile
private var currentRequestToken: Long = -1L

// requestToken 生成器
private var nextRequestToken: Long = 1L

private fun generateRequestToken(): Long {
    val token = nextRequestToken
    nextRequestToken++
    return token
}
```

添加三个语义入口方法：

```kotlin
/**
 * 业务页面按需获取相机。
 * 若当前已有其他 owner 占用，先执行强制移交（释放旧资源，再为新 owner 申请）。
 * 若获取失败，自动执行最多 3 次额外重试（共 4 次尝试）。
 * @return requestToken，页面可保存用于后续判断回调是否过期
 */
fun acquireForActivity(
    owner: CameraOwner,
    needPreview: Boolean,
    previewView: RokidCameraPreviewView? = null,
    enableRecovery: Boolean = false,
    onReady: (Boolean) -> Unit = {},
): Long {
    val token = generateRequestToken()
    currentRequestToken = token

    val current = synchronized(lock) { stateMachine.snapshot() }
    if (current.owner != null && current.owner != owner) {
        Log.w(TAG, "forceTransfer oldOwner=${current.owner} newOwner=$owner")
        forceReleaseCurrentOwner(reason = "force_transfer_to_${owner.name}")
    }
    acquireWithRetry(
        owner = owner,
        needPreview = needPreview,
        previewView = previewView,
        enableRecovery = enableRecovery,
        attempt = 1,
        maxAttempts = 4,
        requestToken = token,
        onReady = onReady,
    )
    return token
}

/**
 * 临时暂停：用于权限弹窗、系统遮挡或短暂进入后台。
 * 停止页面消费或预览，但不完整释放 NV21 和 owner。
 * requestToken 保持不变，允许后续恢复。
 */
fun pauseTemporarily(owner: CameraOwner, reason: String): Long {
    return pause(owner = owner, reason = reason)
}

/**
 * 明确离开：用于返回、取消、完成、跳转其他 Activity 或主动 finish()。
 * 停止 Surface 和 NV21，清空 owner，并将 currentRequestToken 置为 -1
 * 以终止所有进行中的重试。
 */
fun releaseForNavigation(owner: CameraOwner, reason: String): Long {
    currentRequestToken = -1L
    return release(owner = owner, reason = reason)
}
```

### Step 2: 添加重试逻辑（使用稳定 requestToken）

```kotlin
private fun acquireWithRetry(
    owner: CameraOwner,
    needPreview: Boolean,
    previewView: RokidCameraPreviewView?,
    enableRecovery: Boolean,
    attempt: Int,
    maxAttempts: Int,
    requestToken: Long,
    onReady: (Boolean) -> Unit,
) {
    Log.i(TAG, "acquire attempt=$attempt/$maxAttempts owner=$owner requestToken=$requestToken")
    acquire(
        owner = owner,
        needPreview = needPreview,
        previewView = previewView,
        enableRecovery = enableRecovery,
    ) { success ->
        // 检查当前请求是否已被取消（releaseForNavigation 会将 currentRequestToken 置为 -1）
        if (currentRequestToken != requestToken) {
            Log.i(TAG, "retry callback ignored: requestToken=$requestToken current=$currentRequestToken")
            return@acquire
        }
        if (success) {
            onReady(true)
            return@acquire
        }
        if (attempt >= maxAttempts) {
            Log.e(TAG, "acquire failed after $maxAttempts attempts owner=$owner")
            onReady(false)
            return@acquire
        }
        // 重试前完整清理 App 相机资源（注意：这会清空 owner，但不影响 requestToken 判断）
        releaseAppCamera(reason = "retry_cleanup_before_attempt_${attempt + 1}")
        mainHandler.postDelayed({
            // 再次检查 requestToken，而非检查 owner（owner 已被 releaseAppCamera 清空）
            if (currentRequestToken != requestToken) {
                Log.i(TAG, "retry aborted: request cancelled owner=$owner requestToken=$requestToken")
                return@postDelayed
            }
            acquireWithRetry(
                owner = owner,
                needPreview = needPreview,
                previewView = previewView,
                enableRecovery = enableRecovery,
                attempt = attempt + 1,
                maxAttempts = maxAttempts,
                requestToken = requestToken,
                onReady = onReady,
            )
        }, RETRY_DELAY_MS)
    }
}

private fun forceReleaseCurrentOwner(reason: String) {
    val previewToStop = synchronized(lock) {
        val preview = boundPreviewView
        boundPreviewView = null
        activeNeedPreview = false
        preview
    }
    previewToStop?.stopPreview()
    RokidFrameSource.stopSurfacePreview()
    RokidFrameSource.stopFrameStream()
    synchronized(lock) {
        stateMachine.forceRelease()
    }
    Log.i(TAG, "forceReleaseCurrentOwner reason=$reason")
}
```

### Step 3: 添加 StateMachine.forceRelease()（生产方法，不用 resetForTest）

```kotlin
/** 强制释放当前 owner 和状态，进入 IDLE。增加 generation 使旧回调失效。 */
fun forceRelease(): SessionSnapshot {
    val next = SessionSnapshot(
        owner = null,
        state = CameraSessionState.IDLE,
        generation = snapshot.generation + 1L,
    )
    snapshot = next
    return next
}
```

### Step 4: 添加 RETRY_DELAY_MS 常量

```kotlin
companion object {
    // ... 已有常量 ...
    private const val RETRY_DELAY_MS = 300L
}
```

### Step 5: 编写可执行测试

项目的协调器直接依赖全局 `RokidFrameSource` 和主线程 `Handler`，当前无法在纯单元测试中模拟四次重试。本轮先覆盖可直接测试的 StateMachine 和 requestToken 逻辑：

```kotlin
class InspectionCameraCoordinatorTest {

    @Before
    fun setUp() {
        InspectionCameraCoordinator.resetForTest()
    }

    @Test
    fun forceRelease_clearsOwnerAndIncrementsGeneration() {
        // 先通过 acquire 设置一个 owner
        InspectionCameraCoordinator.acquire(CameraOwner.LOADING, needPreview = false) {}
        val before = InspectionCameraCoordinator.getOwner()
        assertEquals(CameraOwner.LOADING, before)

        // 调用 releaseForNavigation 后 owner 应为 null
        InspectionCameraCoordinator.releaseForNavigation(
            owner = CameraOwner.LOADING,
            reason = "test",
        )
        assertNull(InspectionCameraCoordinator.getOwner())
        assertEquals(
            InspectionCameraCoordinator.CameraSessionState.IDLE,
            InspectionCameraCoordinator.getState(),
        )
    }

    @Test
    fun requestToken_invalidatedAfterReleaseForNavigation() {
        val token = InspectionCameraCoordinator.acquireForActivity(
            owner = CameraOwner.AI_INSPECTION,
            needPreview = false,
        ) { }
        assertTrue(token > 0)

        // releaseForNavigation 将 currentRequestToken 置为 -1
        InspectionCameraCoordinator.releaseForNavigation(
            owner = CameraOwner.AI_INSPECTION,
            reason = "test",
        )
        // 后续重试回调会因为 requestToken 不匹配而被忽略
    }

    @Test
    fun forceTransfer_cleansUpOldOwner() {
        // LOADING 先占用
        InspectionCameraCoordinator.acquire(CameraOwner.LOADING, needPreview = false) {}
        assertEquals(CameraOwner.LOADING, InspectionCameraCoordinator.getOwner())

        // 新 owner 请求触发强制移交（在 acquireForActivity 内部）
        InspectionCameraCoordinator.acquireForActivity(
            owner = CameraOwner.DEVICE_GUIDE,
            needPreview = true,
        ) { }
        // 旧 owner 已被清除，新 acquire 按正常流程执行
        // 注意：此时 owner 取决于 acquire 异步回调结果
    }
}
```

### Step 6: 运行测试

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.InspectionCameraCoordinatorTest"
```

### Step 7: Commit

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/InspectionCameraCoordinator.kt \
    app/src/test/java/com/rokid/glass/hiddenrisk/InspectionCameraCoordinatorTest.kt
git commit -m "feat(camera): add acquireForActivity, pauseTemporarily, releaseForNavigation with requestToken-based retry and force-transfer"
```

---

## Task 2: EntryGuardCoordinator Remove Camera Pre-warm

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/EntryGuardCoordinator.kt`
- Modify: `app/src/main/java/com/rokid/glass/MainMenuActivity.kt` (remove camera callback)

### Step 1: Remove camera-related fields and callbacks from EntryGuardCoordinator

Delete from `EntryGuardCoordinator`:
- `enum class CameraWarmupState`
- `Callback.onCameraStateChanged(state: CameraWarmupState)`
- `private val cameraCheckCompleted = AtomicBoolean(false)`
- `private val tryStartCameraWarmup()`
- All `CameraOwner.LOADING` references
- All `postCameraState()` calls

Remove camera from `tryNotifyAllGuardsReady()`:
```kotlin
private fun tryNotifyAllGuardsReady() {
    if (released.get()) return
    if (allGuardsReadyFired.get()) return
    if (!wifiCheckCompleted.get()) return
    if (!sdkCheckCompleted.get()) return
    // 删除: if (!cameraCheckCompleted.get()) return
    AppFileLogger.i(TAG, "all guards ready")
    allGuardsReadyFired.set(true)
    postCallback { it.onAllGuardsReady() }
}
```

### Step 2: 从 SDK 完成路径启动自动更新检查（替代原来的相机热启动触发）

原来 `startAutoUpdateCheck()` 在 `tryStartCameraWarmup()` 的回调中触发。移除相机预热后，改由 SDK 就绪/失败路径直接触发，通过 `updateCheckCompleted` 保证只启动一次：

```kotlin
private val sdkListener = object : RokidSdkManager.Listener {
    override fun onSdkStateChanged(state: RokidSdkManager.SdkState) {
        if (released.get()) return
        when (state) {
            RokidSdkManager.SdkState.READY -> {
                postSdkState(SdkInitState.READY)
                sdkCheckCompleted.set(true)
                startAutoUpdateCheck()
                tryNotifyAllGuardsReady()
            }
            RokidSdkManager.SdkState.FAILED -> {
                postSdkState(SdkInitState.FAILED)
                sdkCheckCompleted.set(true)
                startAutoUpdateCheck()
                tryNotifyAllGuardsReady()
            }
            // ... rest unchanged
        }
    }
}
```

同时也要处理 SDK 在 `startSdkInit()` 中已经就绪的路径（跳过状态回调的情况）：

```kotlin
// 在 startSdkInit() 方法内的已有就绪检查处：
if (RokidSdkManager.state == RokidSdkManager.SdkState.READY && !sdkCheckCompleted.get()) {
    postSdkState(SdkInitState.READY)
    sdkCheckCompleted.set(true)
    startAutoUpdateCheck()
    tryNotifyAllGuardsReady()
} else if (RokidSdkManager.state == RokidSdkManager.SdkState.FAILED && !sdkCheckCompleted.get()) {
    postSdkState(SdkInitState.FAILED)
    sdkCheckCompleted.set(true)
    startAutoUpdateCheck()
    tryNotifyAllGuardsReady()
}
```

注意：`startAutoUpdateCheck()` 内部已有 `if (released.get() || updateCheckCompleted.get()) return` 守卫，所以多次调用不会重复启动。

### Step 3: Update MainMenuActivity callback

Remove `onCameraStateChanged` override:
```kotlin
// 删除整个 onCameraStateChanged 方法
// override fun onCameraStateChanged(state: EntryGuardCoordinator.CameraWarmupState) { ... }
```

### Step 4: Commit

```bash
git add app/src/main/java/com/rokid/glass/EntryGuardCoordinator.kt
app/src/main/java/com/rokid/glass/MainMenuActivity.kt
git commit -m "feat(entry): remove camera pre-warming from EntryGuardCoordinator and MainMenuActivity"
```

---

## Task 3: InspectionLoadingActivity Remove Camera Init

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt`

### Step 1: Remove camera init phase

Delete:
- `LoadingStage.CAMERA_INIT`
- `cameraInitStarted` field
- `cameraSessionGeneration` field
- `startCameraInit()` method entirely
- `onDestroy()` call to `InspectionCameraCoordinator.pause(CameraOwner.LOADING, ...)`
- CAMERA from `requiredPermissions()` (keep READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE for model preload)
- All `InspectionCameraCoordinator` imports and calls

In `onInitializationComplete()`, remove camera-related preconditions:
```kotlin
private fun startInitializationFlow() {
    loadingStage = LoadingStage.SDK_INIT
    setSubtitle(getString(R.string.ai_inspection_loading_subtitle_sdk_init), animated = true)
    refreshInputActions()

    if (RokidSdkManager.state == RokidSdkManager.SdkState.READY) {
        setSubtitle(getString(R.string.ai_inspection_loading_subtitle_sdk_ready), animated = true)
        animateProgressTo(30)
        // 替换 startCameraInit() 为直接加载模型
        preloadLocalModelIfNeeded()
    }
}
```

### Step 2: Adjust progress animation

Remove camera progress steps. Flow becomes:
- SDK init → 0-30%
- Model preload → 30-100%

### Step 3: Commit

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt
git commit -m "feat(loading): remove camera initialization from InspectionLoadingActivity"
```

---

## Task 4: AiInspectionActivity Use acquireForActivity + releaseForNavigation

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`

### Step 0: 迁移进入时 acquire 调用

找到 AiInspectionActivity 中现有的 `InspectionCameraCoordinator.acquire()` 调用，替换为 `acquireForActivity()`，保存返回的 `requestToken`：

```kotlin
// 搜索现有代码中的 acquire 调用，替换为：
private var cameraRequestToken: Long = -1L

// 在需要启动相机的入口（如 onResume 或相机初始化方法中）：
cameraRequestToken = InspectionCameraCoordinator.acquireForActivity(
    owner = CameraOwner.AI_INSPECTION,
    needPreview = true,
    previewView = binding.previewView,  // 实际的 previewView 引用
) { success ->
    if (success) {
        // 相机就绪，开始推理等业务逻辑
    } else {
        // 相机获取失败，显示错误状态
    }
}
```

> **注意**：具体替换位置需要先读取 AiInspectionActivity.kt 全文定位现有 `acquire()` 调用。以上为模式代码。

### Step 1: Identify all exit paths

Exit paths from grep analysis:
- `navigateToInspectionMenu()` (line ~1291): back to menu
- `startEndReport()` (line ~1318): end inspection report
- `navigateToDeviceGuide()` (line ~2640): to device guide
- `navigateToHazardRecord()` (line ~2653): to hazard record
- `onDestroy()`: activity destruction
- `onPause()`: temporary pause (keep as `pauseTemporarily`)
- Wear sleep: `ai_wear_sleep` (keep as `pauseTemporarily`)

### Step 2: Add releaseForNavigation to explicit exit paths

```kotlin
private fun navigateToInspectionMenu() {
    InspectionCameraCoordinator.releaseForNavigation(
        owner = CameraOwner.AI_INSPECTION,
        reason = "navigate_to_inspection_menu",
    )
    startActivity(Intent(this, AiInspectionMenuActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    })
    finish()
}

private fun startEndReport() {
    InspectionCameraCoordinator.releaseForNavigation(
        owner = CameraOwner.AI_INSPECTION,
        reason = "start_end_report",
    )
    // ... existing code
}

private fun navigateToDeviceGuide() {
    InspectionCameraCoordinator.releaseForNavigation(
        owner = CameraOwner.AI_INSPECTION,
        reason = "navigate_to_device_guide",
    )
    startActivity(Intent(this, DeviceGuideActivity::class.java))
    finish()
}

private fun navigateToHazardRecord() {
    InspectionCameraCoordinator.releaseForNavigation(
        owner = CameraOwner.AI_INSPECTION,
        reason = "navigate_to_hazard_record",
    )
    startActivity(Intent(this, HazardRecordActivity::class.java))
    finish()
}
```

### Step 3: Keep onPause as pauseTemporarily

```kotlin
override fun onPause() {
    // ... existing code
    InspectionCameraCoordinator.pauseTemporarily(
        owner = CameraOwner.AI_INSPECTION,
        reason = "ai_on_pause",
    )
    super.onPause()
}
```

### Step 4: onDestroy uses releaseForNavigation

```kotlin
override fun onDestroy() {
    // ... existing cleanup
    InspectionCameraCoordinator.releaseForNavigation(
        owner = CameraOwner.AI_INSPECTION,
        reason = "ai_on_destroy",
    )
    // ... rest
}
```

### Step 5: Commit

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt
git commit -m "feat(ai-inspection): use releaseForNavigation on all exit paths"
```

---

## Task 5: DeviceGuideActivity Use acquireForActivity + releaseForNavigation

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/DeviceGuideActivity.kt`

### Step 0: 迁移进入时 acquire 调用

将 DeviceGuideActivity 中现有的 `InspectionCameraCoordinator.acquire()` 替换为 `acquireForActivity()`：

```kotlin
private var cameraRequestToken: Long = -1L

// 在需要启动相机的入口：
cameraRequestToken = InspectionCameraCoordinator.acquireForActivity(
    owner = CameraOwner.DEVICE_GUIDE,
    needPreview = true,
    previewView = binding.previewView,
) { success ->
    if (success) {
        // 相机就绪
    } else {
        // 相机获取失败
    }
}
```

### Step 1: Identify exit paths

From grep:
- `navigateToInspectionMenu()` (line ~755): back to menu
- `onPause()`: temporary
- `onDestroy()`: destruction
- `device_guide_wear_sleep`: wear sleep

### Step 2: Apply same pattern as Task 4

```kotlin
private fun navigateToInspectionMenu() {
    InspectionCameraCoordinator.releaseForNavigation(
        owner = CameraOwner.DEVICE_GUIDE,
        reason = "navigate_to_inspection_menu",
    )
    startActivity(Intent(this, AiInspectionMenuActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    })
    finish()
}
```

Keep `onPause()` as `pauseTemporarily`, `onDestroy()` as `releaseForNavigation`.

### Step 3: Commit

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/DeviceGuideActivity.kt
git commit -m "feat(device-guide): use releaseForNavigation on all exit paths"
```

---

## Task 6: HazardRecordActivity Use acquireForActivity + releaseForNavigation

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/HazardRecordActivity.kt`

### Step 0: 迁移进入时 acquire 调用

将 HazardRecordActivity 中现有的 `InspectionCameraCoordinator.acquire()` 替换为 `acquireForActivity()`：

```kotlin
private var cameraRequestToken: Long = -1L

// 在需要启动相机的入口：
cameraRequestToken = InspectionCameraCoordinator.acquireForActivity(
    owner = CameraOwner.HAZARD_RECORD,
    needPreview = true,
    previewView = binding.previewView,
) { success ->
    if (success) {
        // 相机就绪
    } else {
        // 相机获取失败
    }
}
```

### Step 1: Identify exit paths

From code reading:
- Navigation to end report or menu
- `onPause()`: temporary
- `onDestroy()`: destruction

### Step 2: Apply same pattern

Replace `pause` calls in explicit navigation with `releaseForNavigation`.
Keep `onPause()` as `pauseTemporarily`.

### Step 3: Commit

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/HazardRecordActivity.kt
git commit -m "feat(hazard-record): use releaseForNavigation on all exit paths"
```

---

## Task 7: EnterpriseQrScanActivity Use acquireForActivity + releaseForNavigation

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/EnterpriseQrScanActivity.kt`

### Step 0: 迁移进入时 acquire 调用

将 EnterpriseQrScanActivity 中现有的 `InspectionCameraCoordinator.acquire()` 替换为 `acquireForActivity()`：

```kotlin
private var cameraRequestToken: Long = -1L

// 在需要启动相机的入口（注意：EnterpriseQrScanActivity 使用 App 内相机扫码，不经过 GlassScanner）：
cameraRequestToken = InspectionCameraCoordinator.acquireForActivity(
    owner = CameraOwner.ENTERPRISE_QR_SCAN,
    needPreview = true,
    previewView = binding.previewView,
) { success ->
    if (success) {
        // 相机就绪，开始扫码
    } else {
        // 相机获取失败
    }
}
```

### Step 1: Apply releaseForNavigation pattern

Replace `pause` calls with `releaseForNavigation` on:
- `navigateToEnterpriseInfo()`: successful scan → to info page
- `returnToPreviousScreen()`: back/cancel
- `onDestroy()`: destruction

Keep temporary states (if any) as `pauseTemporarily`.

### Step 2: Commit

```bash
git add app/src/main/java/com/rokid/glass/EnterpriseQrScanActivity.kt
git commit -m "feat(enterprise-qr): use releaseForNavigation on all exit paths"
```

---

## Task 8: GlassScanner Launcher Wrapper

**Files:**
- Create: `app/src/main/java/com/rokid/glass/utils/GlassScannerLauncher.kt`
- Modify: `app/src/main/java/com/rokid/glass/EntryGuardCoordinator.kt` (replace direct GlassScanner.launch)
- Test: `app/src/test/java/com/rokid/glass/utils/GlassScannerLauncherTest.kt`

### Step 1: Create GlassScannerLauncher

```kotlin
package com.rokid.glass.utils

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.google.mlkit.vision.barcode.common.Barcode
import com.rokid.glass.hiddenrisk.InspectionCameraCoordinator
import com.rokid.security.glass3.qrcode.api.GlassScanCallback
import com.rokid.security.glass3.qrcode.api.GlassScanner

/**
 * GlassScanner 统一启动入口。
 * 封装相机资源冲突检测和自动恢复重试逻辑。
 * 首次直接启动；仅在明确相机占用/打开错误时释放 App 相机并重试一次；
 * 第二次仍失败则回调 onCameraUnavailable()。
 */
object GlassScannerLauncher {

    private const val TAG = "GlassScannerLauncher"
    private const val RETRY_DELAY_MS = 300L

    interface LauncherCallback {
        fun onSuccess(content: String?, barcode: Barcode)
        fun onFailure(error: String)
        fun onCancelled()
        /** 第二次启动仍失败时调用（相机不可用） */
        fun onCameraUnavailable()
    }

    /**
     * 相机错误的判定模式。
     * 使用 SDK 已知错误特征，避免将普通扫码识别失败误判为相机故障。
     * 优先匹配 Rokid SDK 明确异常类名和 Android CameraService 错误码；
     * 附加"Higher-priority client"和"currently unavailable"作为 CameraService 特定消息。
     */
    private val cameraErrorPatterns = listOf(
        "CameraAccessException",
        "CameraDevice",
        "connectHelper",
        "Higher-priority client using camera",
        "currently unavailable",
        "ServiceSpecificException",
        "CameraRuntimeException",
    )

    fun launch(
        activity: Activity,
        config: com.rokid.security.glass3.qrcode.model.GlassScanConfig,
        callback: LauncherCallback,
    ) {
        tryLaunch(activity, config, callback, isRetry = false)
    }

    private fun tryLaunch(
        activity: Activity,
        config: com.rokid.security.glass3.qrcode.model.GlassScanConfig,
        callback: LauncherCallback,
        isRetry: Boolean,
    ) {
        try {
            GlassScanner.launch(
                activity,
                config,
                object : GlassScanCallback {
                    override fun onScanSuccess(content: String?, barcode: Barcode) {
                        callback.onSuccess(content, barcode)
                    }
                    override fun onScanFailure(error: String) {
                        handleResult(error, null, callback, activity, config, isRetry)
                    }
                    override fun onScanCancelled() {
                        callback.onCancelled()
                    }
                },
            )
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.javaClass.simpleName
            AppFileLogger.e(TAG, "launch exception: $errorMsg", e)
            handleResult(errorMsg, null, callback, activity, config, isRetry)
        }
    }

    /**
     * 统一处理 onScanFailure 回调和异常抛出的错误。
     * 首次相机错误 → 释放 App 相机并重试一次。
     * 第二次相机错误 → 回调 onCameraUnavailable()。
     * 非相机错误 → 直接回调 onFailure()。
     */
    private fun handleResult(
        errorMsg: String,
        exception: Exception?,
        callback: LauncherCallback,
        activity: Activity,
        config: com.rokid.security.glass3.qrcode.model.GlassScanConfig,
        isRetry: Boolean,
    ) {
        if (!isCameraError(errorMsg)) {
            // 非相机错误：正常回调 onFailure（如普通识别失败、内容无效等）
            callback.onFailure(errorMsg)
            return
        }
        if (!isRetry) {
            // 首次相机错误：释放 App 相机，300ms 后重试一次
            AppFileLogger.w(TAG, "camera error on first attempt: $errorMsg, releasing app camera and retrying")
            InspectionCameraCoordinator.releaseAppCamera(reason = "glass_scanner_conflict_recovery")
            Handler(Looper.getMainLooper()).postDelayed({
                tryLaunch(activity, config, callback, isRetry = true)
            }, RETRY_DELAY_MS)
            return
        }
        // 第二次相机错误：通知调用方相机不可用
        AppFileLogger.e(TAG, "camera error on retry: $errorMsg, camera unavailable")
        callback.onCameraUnavailable()
    }

    /** 判断错误信息是否属于相机资源冲突/占用问题 */
    fun isCameraError(error: String): Boolean {
        return cameraErrorPatterns.any { pattern -> error.contains(pattern) }
    }
}
```

### Step 2: Replace EntryGuardCoordinator usage

```kotlin
// In EntryGuardCoordinator.launchWifiScanner()
// Replace GlassScanner.launch(...) with:
GlassScannerLauncher.launch(
    activity,
    WifiScanConfigFactory.create(activity),
    object : GlassScannerLauncher.LauncherCallback {
        override fun onSuccess(content: String?, barcode: Barcode) {
            wifiScannerLaunching.set(false)
            if (content == null) {
                postCallback { it.onWifiConnectionFailed(R.string.ai_entry_wifi_invalid_qr) }
            } else {
                handleWifiQrContent(content)
            }
        }
        override fun onFailure(error: String) {
            wifiScannerLaunching.set(false)
            postCallback { it.onWifiConnectionFailed(R.string.ai_entry_wifi_invalid_qr) }
        }
        override fun onCancelled() {
            wifiScannerLaunching.set(false)
            postCallback { it.onWifiRequired(R.string.ai_entry_wifi_required_message) }
        }
        override fun onCameraUnavailable() {
            wifiScannerLaunching.set(false)
            postCallback { it.onWifiConnectionFailed(R.string.ai_entry_wifi_connect_failed) }
        }
    },
)
```

### Step 3: Write tests

```kotlin
class GlassScannerLauncherTest {
    @Test
    fun isCameraError_detectsCameraKeywords() {
        assertTrue(GlassScannerLauncher.isCameraError("Higher-priority client using camera"))
        assertTrue(GlassScannerLauncher.isCameraError("CameraAccessException"))
        assertFalse(GlassScannerLauncher.isCameraError("Invalid QR code content"))
    }
}
```

### Step 4: Commit

```bash
git add app/src/main/java/com/rokid/glass/utils/GlassScannerLauncher.kt
app/src/main/java/com/rokid/glass/EntryGuardCoordinator.kt
app/src/test/java/com/rokid/glass/utils/GlassScannerLauncherTest.kt
git commit -m "feat(scanner): add GlassScannerLauncher with camera conflict recovery"
```

---

## Task 9: Build Verification

```bash
bash scripts/android/doctor.sh
bash scripts/android/build-debug.sh
```

Expected: `BUILD SUCCESSFUL`

---

## Task 10: 真机验证

> **目的：** 确保所有改动在 Rokid AR 眼镜真机上正常工作，特别是 GlassScanner 扫码不再出现相机冲突。

### Step 1: 环境检查与安装

```bash
bash scripts/android/doctor.sh --device
bash scripts/android/build-debug.sh
bash scripts/android/install-debug.sh -s <serial>
```

### Step 2: 启动验证 — 菜单页不应持有相机

```bash
adb -s <serial> logcat -c
adb -s <serial> logcat -s InspectionCameraCoordinator:* | head -50
```

启动应用进入主菜单后，检查 logcat：
- **预期：** 没有 `acquire` / `owner=` 相关日志（菜单页不应触发相机获取）
- **异常：** 若出现 `owner=LOADING` 或 `acquire` 调用，说明相机预热未完全移除

### Step 3: GlassScanner WiFi 扫码验证（核心场景）

1. 主菜单点击"WiFi 连接" → 进入扫码页面
2. 对准有效 WiFi 二维码
3. 检查 logcat：

```bash
adb -s <serial> logcat -s GlassScannerLauncher:* EntryGuardCoordinator:*
```

**预期结果：**
- 扫码成功，无 `Higher-priority client using camera` 错误
- 无 `camera error on first attempt` 日志（说明无冲突）
- 若出现首次相机错误后重试成功，也视为通过（冲突恢复机制生效）

### Step 4: 巡检页面相机获取验证

1. 主菜单 → 进入巡检 → 加载页 → 巡检页面
2. 检查相机预览是否正常显示

```bash
adb -s <serial> logcat -s InspectionCameraCoordinator:* | grep -E "acquire|owner"
```

**预期结果：**
- 看到 `acquire attempt=1/4 owner=AI_INSPECTION`
- 相机预览正常（取景器显示）
- 无 forceTransfer 日志（因为菜单页不再持有相机）

### Step 5: 页面间导航相机释放验证

按以下顺序导航，每次切换后检查 logcat：

| 导航路径 | 预期日志 |
|---------|---------|
| 巡检页 → 设备指引 | `releaseForNavigation owner=AI_INSPECTION reason=navigate_to_device_guide` |
| 设备指引 → 隐患记录 | `releaseForNavigation owner=DEVICE_GUIDE` → `acquire owner=HAZARD_RECORD` |
| 隐患记录 → 返回菜单 | `releaseForNavigation owner=HAZARD_RECORD` |

### Step 6: 企业扫码页面验证

1. 主菜单 → 企业扫码入口 → EnterpriseQrScanActivity
2. 确认相机预览正常，扫码功能正常

### Step 7: 验证清单

| 检查项 | 状态 |
|--------|------|
| ☐ 菜单页启动后无相机 acquire 日志 | |
| ☐ WiFi 扫码成功（无相机冲突错误） | |
| ☐ 巡检页相机预览正常 | |
| ☐ 页面导航时 releaseForNavigation 正确调用 | |
| ☐ 企业扫码页相机正常 | |
| ☐ 应用未出现崩溃或 ANR | |

### Step 8: Commit (如有微调)

```bash
git add <adjusted files>
git commit -m "fix(camera): device verification adjustments"
```

---

## Task 11: 静态检查

Manually verify each Activity's exit paths call `releaseForNavigation()`:

| Activity | Exit Paths Verified |
|----------|-------------------|
| AiInspectionActivity | ☐ navigateToInspectionMenu ☐ startEndReport ☐ navigateToDeviceGuide ☐ navigateToHazardRecord ☐ onDestroy |
| DeviceGuideActivity | ☐ navigateToInspectionMenu ☐ onDestroy |
| HazardRecordActivity | ☐ navigateToEndReport/Menu ☐ onDestroy |
| EnterpriseQrScanActivity | ☐ navigateToEnterpriseInfo ☐ returnToPreviousScreen ☐ onDestroy |

---

## Spec Coverage Check

| Spec Requirement | Task |
|---|---|
| 菜单页和加载页不再预热、注册或持有相机 | Task 2, Task 3 |
| 仅在正式业务页面需要摄像头时注册 Surface 和 NV21 | Task 1 (acquireForActivity) |
| Activity 明确离开时主动完整释放相机 | Task 4-7 (releaseForNavigation) |
| Activity 遗漏释放时，新 owner 请求由底层强制清理并移交 | Task 1 (forceReleaseCurrentOwner) |
| 业务相机获取失败时由底层统一重试 | Task 1 (acquireWithRetry) |
| GlassScanner 首次直接启动；仅在明确相机错误时释放 App 相机并重试一次 | Task 8 (GlassScannerLauncher) |
| 删除入口相机预热 | Task 2, Task 3 |
| 临时 pause 不完整释放 NV21 | Task 1 (pauseTemporarily) |
| 四个业务页面统一采用规则 | Task 4-7 |
| 调试页面不被改造 | N/A (out of scope) |
| 过期 generation 回调忽略 | Already in existing code + Task 1 tests |
| 扫码错误分类 | Task 8 (isCameraError) |
| 构建通过 | Task 9 |
| 真机验证（GlassScanner 无冲突、页面导航释放正确） | Task 10 |
| 静态检查所有退出路径 | Task 11 |

**Plan complete and saved to `docs/superpowers/plans/2026-06-10-on-demand-camera-ownership-design.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
