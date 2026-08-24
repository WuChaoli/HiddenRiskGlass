# Full Frame Detection Overlay Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建一个 ADB 隔离测试页，使用 Camera SDK 实际返回的原始 `3:4` NV21 帧，等比例缩小为 `960×1280` 调用真实 `/auto`，将返回 BBox 恢复到原始帧坐标后应用固定 1m 标定投影，并在 `480×640` 眼镜屏幕上只绘制框和 Label。

**Architecture:** 为共享相机增加显式 `CameraStreamProfile`，默认业务配置保持 `1920×1080`，测试页单独请求 `3024×4032@15fps`、`zoomLevel=1` 并以实际回调尺寸为准。坐标恢复、投影窗口和求交映射放在纯 Kotlin `FullFrameOverlayMapper`；Activity 只编排相机、真实 `/auto` 请求、输入和 UI，透明 Overlay 只绘制已经映射好的框。

**Tech Stack:** Kotlin、Android View、Rokid Glass3 `CameraShareHelper`、NV21、OkHttp、JUnit4、PowerShell ADB 脚本。

**Spec:** `docs/superpowers/specs/2026-08-24-full-frame-detection-overlay-test-design.md`

## Global Constraints

- 本阶段只实现测试页，不实现 BBox `1/8` 门禁、`/ai/deep` 或正式 `AiInspectionActivity` 改造。
- Camera、FOV 和 Zoom 必须与现有对齐测试环境一致；测试 Profile 请求 `3024×4032@15fps`、`zoomLevel=1`。
- Camera 实际回调必须是视觉转正的 `3:4` NV21；不满足时失败关闭并报告实际尺寸，禁止裁切其他比例冒充。
- `/auto` 请求图固定为 `960×1280`，只能等比例缩小，禁止裁切和非等比拉伸。
- 固定 `distanceMeters = 1f`，初始沿用 `AlignmentCalibrationPreset.CALIBRATED_SCALE`、反距离水平偏移和 `offsetY = -234f`。
- Activity 不直接管理业务 Camera；通过 `InspectionCameraCoordinator` 和 `RokidFrameSource` 获取帧与 Surface。
- 测试页不保存图片、不上传隐患、不修改 `InspectionWorkflowSession` 统计。
- 保留当前工作树中与本任务无关的 `docs/.gitignore` 修改和博客 ZIP，不纳入任何提交。

---

### Task 1: 为共享相机增加隔离的 3:4 Stream Profile

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/camera/RokidFrameSource.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionCameraCoordinator.kt`
- Modify: `app/src/test/java/com/rokid/glass/camera/RokidFrameSourceTest.kt`
- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/InspectionCameraCoordinatorStateMachineTest.kt`

**Interfaces:**
- Produces: `CameraStreamProfile(width: Int, height: Int, targetFps: Int, zoomLevel: Int)`。
- Produces: `CameraStreamProfile.businessDefault(zoomLevel) = 1920×1080@15fps` 和 `CameraStreamProfile.FULL_FRAME_OVERLAY_TEST = 3024×4032@15fps, zoomLevel=1`。
- Produces: `InspectionCameraCoordinator.acquireForActivity(..., streamProfile: CameraStreamProfile? = null, ...)`；`null` 必须继续从 `InspectionConfigRepository` 解析当前业务 Zoom，避免改变现有行为。
- Produces: `RokidFrameSource.startFrameStream(profile, onReady)`，Surface 和 NV21 必须使用同一个 active profile。

- [ ] **Step 1: 写 Profile 和 Coordinator 失败测试**

在 `RokidFrameSourceTest.kt` 增加：

```kotlin
@Test
fun `business and overlay profiles keep their requested aspect ratios`() {
    val business = CameraStreamProfile.businessDefault(zoomLevel = 3)
    assertEquals(1920, business.width)
    assertEquals(1080, business.height)
    assertEquals(3, business.zoomLevel)
    assertEquals(3024, CameraStreamProfile.FULL_FRAME_OVERLAY_TEST.width)
    assertEquals(4032, CameraStreamProfile.FULL_FRAME_OVERLAY_TEST.height)
    assertEquals(3f / 4f, CameraStreamProfile.FULL_FRAME_OVERLAY_TEST.aspectRatio, 0f)
}
```

在 `InspectionCameraCoordinatorStateMachineTest.kt` 增加一个测试，证明新 owner 可独立获取并释放：

```kotlin
@Test
fun `full frame overlay test owner releases without affecting next owner`() {
    val acquired = stateMachine.beginAcquire(
        CameraOwner.FULL_FRAME_OVERLAY_TEST,
        readyNow = false,
        needPreview = true,
    )
    assertTrue(stateMachine.finishReady(CameraOwner.FULL_FRAME_OVERLAY_TEST, acquired.generation, true))
    assertNotNull(stateMachine.beginRelease(CameraOwner.FULL_FRAME_OVERLAY_TEST))
    assertNull(stateMachine.snapshot().owner)
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.camera.RokidFrameSourceTest" --tests "com.rokid.glass.hiddenrisk.InspectionCameraCoordinatorStateMachineTest"
```

Expected: 编译失败，提示 `CameraStreamProfile` 或 `FULL_FRAME_OVERLAY_TEST` 未定义。

- [ ] **Step 3: 实现 Profile 与默认兼容接口**

在 `RokidFrameSource.kt` 定义：

```kotlin
data class CameraStreamProfile(
    val width: Int,
    val height: Int,
    val targetFps: Int,
    val zoomLevel: Int,
) {
    init {
        require(width > 0 && height > 0 && targetFps > 0 && zoomLevel > 0)
    }

    val aspectRatio: Float get() = width.toFloat() / height.toFloat()

    companion object {
        fun businessDefault(zoomLevel: Int) = CameraStreamProfile(1920, 1080, 15, zoomLevel)
        val FULL_FRAME_OVERLAY_TEST = CameraStreamProfile(3024, 4032, 15, 1)
    }
}
```

将 `sharedCameraConfig()` 改为接收 active profile；现有业务调用不传参数时仍从 `sharedCameraZoomRatio` 经 `sdkZoomLevelFor()` 生成 `businessDefault(zoomLevel)`。当请求 Profile 与已打开 Profile 不同时，Coordinator 必须先完整释放旧 helper，再以新 Profile 启动，不能复用旧分辨率 session。`startSurfacePreview()` 使用同一个 active profile。

在 `CameraOwner` 增加：

```kotlin
FULL_FRAME_OVERLAY_TEST,
```

- [ ] **Step 4: 运行相机状态测试**

Run 同 Step 2。

Expected: PASS；现有 owner 测试无回归。

- [ ] **Step 5: 提交相机 Profile 支持**

```powershell
git add app/src/main/java/com/rokid/glass/camera/RokidFrameSource.kt app/src/main/java/com/rokid/glass/hiddenrisk/InspectionCameraCoordinator.kt app/src/test/java/com/rokid/glass/camera/RokidFrameSourceTest.kt app/src/test/java/com/rokid/glass/hiddenrisk/InspectionCameraCoordinatorStateMachineTest.kt
git commit -m "相机：增加完整画幅测试配置"
```

### Task 2: 实现请求坐标恢复与投影窗口映射

**Files:**
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/FullFrameOverlayMapper.kt`
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/FullFrameOverlayCalibrationState.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/FullFrameOverlayMapperTest.kt`

**Interfaces:**
- Consumes: `AlignmentCalibrationState.normalizedCameraCrop()`。
- Produces: `FrameSize(width: Int, height: Int)`。
- Produces: `MappedOverlayFrame(sourceCrop: RectFModel, detections: List<AlignmentDetection>)`。
- Produces: `FullFrameOverlayMapper.map(responseDetections, requestSize, sourceSize, overlaySize, calibration)`。
- Produces: `FullFrameOverlayCalibrationState(calibration, previewAlpha)`，固定深度为 `1f`，只循环 `offsetX/offsetY/scale`。

- [ ] **Step 1: 写 BBox 恢复和窗口求交失败测试**

新增测试至少覆盖：

```kotlin
@Test
fun `request bbox restores to source coordinates before projection`() {
    val result = FullFrameOverlayMapper.map(
        responseDetections = listOf(AlignmentDetection("目标", 0.9f, 96f, 128f, 480f, 640f)),
        requestSize = FrameSize(960, 1280),
        sourceSize = FrameSize(3024, 4032),
        overlaySize = FrameSize(480, 640),
        calibration = DetectionOverlayAlignmentState(distanceMeters = 1f).calibrationState(),
    )
    assertEquals(302.4f, result.sourceDetections.single().left, 0.01f)
    assertEquals(403.2f, result.sourceDetections.single().top, 0.01f)
}

@Test
fun `bbox outside projection crop is omitted`() {
    val result = FullFrameOverlayMapper.map(
        responseDetections = listOf(AlignmentDetection("窗口外", 0.8f, 0f, 0f, 50f, 50f)),
        requestSize = FrameSize(960, 1280),
        sourceSize = FrameSize(3024, 4032),
        overlaySize = FrameSize(480, 640),
        calibration = DetectionOverlayAlignmentState(1f).calibrationState(),
    )
    assertTrue(result.detections.isEmpty())
}

@Test
fun `partially intersecting bbox is clipped then mapped to overlay bounds`() {
    val calibration = DetectionOverlayAlignmentState(1f).calibrationState()
    val crop = calibration.normalizedCameraCrop()
    val sourceLeft = crop.left * 3024f
    val sourceTop = crop.top * 4032f
    val sourceRight = (crop.left + crop.width / 2f) * 3024f
    val sourceBottom = (crop.top + crop.height / 2f) * 4032f
    val result = FullFrameOverlayMapper.map(
        responseDetections = listOf(
            AlignmentDetection(
                "部分相交",
                0.9f,
                (sourceLeft - 100f) / 3.15f,
                sourceTop / 3.15f,
                sourceRight / 3.15f,
                sourceBottom / 3.15f,
            ),
        ),
        requestSize = FrameSize(960, 1280),
        sourceSize = FrameSize(3024, 4032),
        overlaySize = FrameSize(480, 640),
        calibration = calibration,
    )
    assertEquals(0f, result.detections.single().left, 0.001f)
    assertEquals(240f, result.detections.single().right, 0.01f)
}
```

同时覆盖：非 `3:4` source 拒绝、非 `960×1280` request 拒绝、全包含框和 offset/scale 改变窗口位置。

增加标定状态测试，断言默认 `distanceMeters == 1f`、`previewAlpha == 0f`，切换后为 `0.5f`，且按键调节不会改变距离。

- [ ] **Step 2: 运行 Mapper 测试并确认失败**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.FullFrameOverlayMapperTest"
```

Expected: 编译失败，提示 `FullFrameOverlayMapper` 未定义。

- [ ] **Step 3: 实现纯 Kotlin Mapper**

实现签名：

```kotlin
internal data class FrameSize(val width: Int, val height: Int)
internal data class RectFModel(val left: Float, val top: Float, val right: Float, val bottom: Float)
internal data class MappedOverlayFrame(
    val sourceCrop: RectFModel,
    val sourceDetections: List<AlignmentDetection>,
    val detections: List<AlignmentDetection>,
)

internal object FullFrameOverlayMapper {
    fun map(
        responseDetections: List<AlignmentDetection>,
        requestSize: FrameSize,
        sourceSize: FrameSize,
        overlaySize: FrameSize,
        calibration: AlignmentCalibrationState,
    ): MappedOverlayFrame
}

internal data class FullFrameOverlayCalibrationState(
    val calibration: AlignmentCalibrationState = DetectionOverlayAlignmentState(1f).calibrationState(),
    val previewAlpha: Float = 0f,
) {
    val distanceMeters: Float get() = 1f
    fun selectNextControl(): FullFrameOverlayCalibrationState
    fun adjust(direction: AdjustmentDirection): FullFrameOverlayCalibrationState
    fun togglePreview(): FullFrameOverlayCalibrationState
}
```

实现顺序必须是：请求坐标乘 `source/request` 比例 → 根据归一化标定窗口得到 source crop → 矩形求交 → 减去 crop origin → 按 crop 宽高缩放到 Overlay → clip 到 Overlay 边界。不得计算 `1/8` 面积。

- [ ] **Step 4: 运行 Mapper 测试并确认通过**

Run 同 Step 2。

Expected: PASS。

- [ ] **Step 5: 提交 Mapper**

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/FullFrameOverlayMapper.kt app/src/main/java/com/rokid/glass/hiddenrisk/FullFrameOverlayCalibrationState.kt app/src/test/java/com/rokid/glass/hiddenrisk/FullFrameOverlayMapperTest.kt
git commit -m "检测：增加完整画幅投影映射"
```

### Task 3: 实现独立请求状态与透明 Overlay

**Files:**
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/FullFrameDetectionRequestState.kt`
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/FullFrameDetectionOverlayView.kt`
- Create: `app/src/test/java/com/rokid/glass/hiddenrisk/FullFrameDetectionRequestStateTest.kt`

**Interfaces:**
- Consumes: `AlignmentAutoDetectionClient.detect(jpegBytes, callback)`。
- Consumes: `MappedOverlayFrame.detections`。
- Produces: `FullFrameDetectionRequestState.begin(nowMs)`、`acceptSuccess(requestId)`、`acceptFailure(requestId)`、`cancel()`。
- Produces: `FullFrameDetectionOverlayView.showDetections(List<AlignmentDetection>)` 和 `clearDetections()`。

- [ ] **Step 1: 写单请求与过期响应失败测试**

```kotlin
@Test
fun `only one auto request can be in flight`() {
    val state = FullFrameDetectionRequestState()
    val first = state.begin(100L)
    assertNotNull(first)
    assertNull(state.begin(200L))
}

@Test
fun `stale response cannot replace current state`() {
    val state = FullFrameDetectionRequestState()
    val first = state.begin(100L)!!
    state.cancel()
    val second = state.begin(200L)!!
    assertFalse(state.acceptSuccess(first))
    assertTrue(state.acceptSuccess(second))
}
```

另加测试：失败结束当前请求、取消后旧回调无效、500ms cadence 未到时不能开始。

- [ ] **Step 2: 运行状态测试并确认失败**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.FullFrameDetectionRequestStateTest"
```

Expected: 编译失败，提示状态类未定义。

- [ ] **Step 3: 实现请求状态和 Overlay View**

请求状态使用递增 `Long requestId`，记录 `lastStartedAtMs` 与 `activeRequestId`。Overlay View 只画屏幕坐标：

```kotlin
fun showDetections(detections: List<AlignmentDetection>) {
    frame = detections.toList()
    invalidate()
}

fun clearDetections() {
    frame = emptyList()
    invalidate()
}
```

成功空数组由 Activity 调用 `clearDetections()`；网络失败不调用 clear，从而保留旧框。

- [ ] **Step 4: 运行状态测试和现有协议测试**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.FullFrameDetectionRequestStateTest" --tests "com.rokid.glass.hiddenrisk.AlignmentDetectionProtocolTest"
```

Expected: PASS。

- [ ] **Step 5: 提交请求状态与 Overlay**

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/FullFrameDetectionRequestState.kt app/src/main/java/com/rokid/glass/hiddenrisk/FullFrameDetectionOverlayView.kt app/src/test/java/com/rokid/glass/hiddenrisk/FullFrameDetectionRequestStateTest.kt
git commit -m "检测：增加完整画幅请求状态与打框视图"
```

### Task 4: 新建 ADB 隔离测试 Activity

**Files:**
- Create: `app/src/main/java/com/rokid/glass/hiddenrisk/FullFrameDetectionOverlayTestActivity.kt`
- Create: `app/src/main/res/layout/activity_full_frame_detection_overlay_test.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `scripts/android/start-full-frame-detection-overlay-test.ps1`
- Create: `scripts/android/tests/startFullFrameDetectionOverlayTest.Tests.ps1`

**Interfaces:**
- Consumes: `CameraStreamProfile.FULL_FRAME_OVERLAY_TEST`、`RokidFrameSource.copyLatestRawFrame()`。
- Consumes: `FullFrameOverlayMapper`、`FullFrameDetectionRequestState`、`AlignmentAutoDetectionClient`。
- Produces: exported Activity `com.rokid.glass.hiddenrisk.FullFrameDetectionOverlayTestActivity`。

- [ ] **Step 1: 写 ADB 启动脚本失败测试**

仿照现有 depth overlay 脚本测试，断言：

```powershell
Assert-Equal 'com.rokid.glass.hiddenrisk.FullFrameDetectionOverlayTestActivity' `
    $defaultResult.Activity `
    'full frame overlay activity'
```

- [ ] **Step 2: 运行脚本测试并确认失败**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/tests/startFullFrameDetectionOverlayTest.Tests.ps1
```

Expected: FAIL，启动脚本不存在。

- [ ] **Step 3: 创建布局与 Manifest 入口**

布局根节点为全屏 `FrameLayout`，按顺序叠放：

```xml
<com.rokid.glass.component.RokidCameraPreviewView
    android:id="@+id/viewFullFramePreview"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />

<com.rokid.glass.hiddenrisk.FullFrameDetectionOverlayView
    android:id="@+id/viewFullFrameOverlay"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />

<TextView
    android:id="@+id/textFullFrameDiagnostics"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_gravity="top" />
```

Manifest Activity 使用 `android:exported="true"` 和 `Theme.Glessedemo`，不添加 LAUNCHER intent-filter。

- [ ] **Step 4: 实现 Activity 的最小闭环**

Activity 继承 `BaseGlassActivity`，在 `onResume()` 使用：

```kotlin
InspectionCameraCoordinator.acquireForActivity(
    owner = CameraOwner.FULL_FRAME_OVERLAY_TEST,
    needPreview = true,
    previewView = previewView,
    streamProfile = CameraStreamProfile.FULL_FRAME_OVERLAY_TEST,
) { success ->
    if (success) scheduleNextDetection(0L) else renderCameraFailure()
}
```

每次循环：

1. `copyLatestRawFrame()`。
2. 校验实际宽高为 `3:4`，否则停止并显示 `unsupported_source_size=<w>x<h>`。
3. `BitmapUtils.nv21ToBitmap()`。
4. 等比例 `Bitmap.createScaledBitmap(..., 960, 1280, true)`。
5. JPEG quality `82` 编码，调用真实 `AlignmentAutoDetectionClient`。
6. 成功回调先经 request ID 校验，再调用 `FullFrameOverlayMapper.map()`。
7. 成功空结果清框；失败保留旧框。
8. 完成后按 cadence 调度下一帧。

生命周期：`onPause()` 取消 Call、停止 Runnable、`pauseTemporarily()`；`onDestroy()` 关闭 executor 并 `releaseForNavigation()`。

- [ ] **Step 5: 实现按键调参与观察模式**

使用 `FullFrameOverlayCalibrationState`：单击 `selectNextControl()`，前/后滑调用 `adjust()`，双击调用 `togglePreview()` 在 `0.5f` 与 `0f` 间切换。每次参数改变后对最近一次原始响应重新运行 Mapper，不等待下一次网络返回。

- [ ] **Step 6: 创建启动脚本并运行脚本测试**

脚本内容：

```powershell
param([string]$Serial)
$launchParameters = @{ Activity = 'com.rokid.glass.hiddenrisk.FullFrameDetectionOverlayTestActivity' }
if ($Serial) { $launchParameters.Serial = $Serial }
& "$PSScriptRoot\start-activity.ps1" @launchParameters
```

Run Step 2 命令。

Expected: PASS。

- [ ] **Step 7: 运行完整单元测试和 Debug 构建**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest
powershell -ExecutionPolicy Bypass -File scripts/android/build-debug.ps1
```

Expected: `BUILD SUCCESSFUL`，生成 `app/build/outputs/apk/standard/debug/app-standard-debug.apk`。

- [ ] **Step 8: 提交测试页**

```powershell
git add app/src/main/java/com/rokid/glass/hiddenrisk/FullFrameDetectionOverlayTestActivity.kt app/src/main/res/layout/activity_full_frame_detection_overlay_test.xml app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml scripts/android/start-full-frame-detection-overlay-test.ps1 scripts/android/tests/startFullFrameDetectionOverlayTest.Tests.ps1
git commit -m "测试：新增完整画幅实时打框页面"
```

### Task 5: 真机验证 3:4 NV21、真实 `/auto` 与现实框对齐

**Files:**
- Modify if verified values change: `app/src/main/java/com/rokid/glass/hiddenrisk/FullFrameOverlayCalibrationState.kt`
- Create: `test/integration/hiddenrisk/evidence/2026-08-24_full_frame_detection_overlay/README.md`

**Interfaces:**
- Consumes: exported test Activity and launch script from Task 4。
- Produces: actual NV21 size, `/auto` request size, projection crop, mapped BBox and visual alignment evidence。

- [ ] **Step 1: 检查并锁定真机**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/doctor.ps1 -Device
```

Expected: 明确列出一台授权 Rokid 设备；多台设备时设置 `$env:ROKID_SERIAL`，无设备时停止并报告真机门禁未完成。

- [ ] **Step 2: 安装 APK 并启动测试页**

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/install-debug.ps1 -Serial $env:ROKID_SERIAL
powershell -ExecutionPolicy Bypass -File scripts/android/start-full-frame-detection-overlay-test.ps1 -Serial $env:ROKID_SERIAL
```

- [ ] **Step 3: 采集运行日志**

使用同一 SDK 的 `adb.exe logcat` 过滤 `FullFrameOverlayTest|RokidFrameSource|AlignmentAutoDetection`，确认：

```text
requested=3024x4032
actual=<width>x<height> aspect=3:4
request=960x1280
responseBBoxCount=<n>
projectionCrop=[...]
```

如果实际 NV21 不支持 `3:4`，保留支持尺寸和错误日志，停止后续视觉验收，不通过裁切绕过门禁。

- [ ] **Step 4: 验证半透明和纯框模式**

先用 `alpha=0.5` 对比框和相机目标，再切 `alpha=0` 对比框和现实目标。调节 `offsetX`、`offsetY`、`scale`，记录最终值、测试距离 `1m` 和至少一张可核验截图/录像说明。

- [ ] **Step 5: 记录证据和最终验证**

README 必须区分：请求配置、SDK 实际回调、自动化测试、真机视觉结论和未验证边界。最后运行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest
powershell -ExecutionPolicy Bypass -File scripts/android/build-debug.ps1
git diff --check
git status --short
```

Expected: 单元测试和构建成功；`git diff --check` 无输出；提交边界只包含本任务文件与真机证据。

- [ ] **Step 6: 提交真机证据**

```powershell
git add test/integration/hiddenrisk/evidence/2026-08-24_full_frame_detection_overlay app/src/main/java/com/rokid/glass/hiddenrisk/FullFrameOverlayCalibrationState.kt
git commit -m "验证：记录完整画幅打框真机结果"
```

若参数文件未变化，不得把不存在或无变化的文件加入提交。
