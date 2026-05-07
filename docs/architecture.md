# Architecture

## Summary

`app/src/main` 是一个面向 Rokid Glass 的 Android 应用主模块，核心不是单一功能，而是把多条眼镜端业务链路放在同一进程里运行。

从源码能确认的主目标有四类：

1. 巡检主链：启动、预热、识别、确认、保存、结束上报。
2. 企业分流链：企业二维码、Wi-Fi 扫码、企业信息回填。
3. 通用能力层：相机、统一输入、Rokid SDK 绑定、状态栏、提示层。
4. 原生推理层：HiddenRisk NCNN 模型加载与检测。

这不是一个纯 UI 项目，而是一个“Activity + 会话单例 + 协调器 + 网络服务 + JNI 推理”的组合。

## Runtime And Entry Points

### Application

- `app/src/main/java/com/rokid/glass/MyApplication.kt`
  - 初始化全局 `Context`
  - 初始化主线程 `Handler`
  - 初始化 `ToastUtil`
  - 初始化 `InspectionConfigRepository`
  - 注册 Activity 生命周期回调
  - 当最后一个 Activity 销毁时，清空巡检累计结果和企业扫码数据

### Manifest 入口

- `app/src/main/AndroidManifest.xml`
  - `application` 绑定 `com.rokid.glass.MyApplication`
  - launcher 入口是 `com.rokid.glass.hiddenrisk.InspectionLoadingActivity`
  - 还显式注册了 `AiInspectionActivity`、`DeviceGuideActivity`、`HazardRecordActivity`、`LightshotActivity`、`UnifiedInputDebugActivity`、`EnterpriseQrScanActivity`、`WifiQrScanActivity`、`InspectionModeActivity` 等
  - `AudioService` 和 `InspectionBackgroundUploadService` 作为 `service` 注册

### 主启动链

- `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt`
  - 负责 Rokid SDK 初始化
  - 校验权限
  - 预热相机帧流
  - 完成后按条件分流到后续页面

它的分流逻辑明确依赖：

- `InspectionFeatureFlags.isEnterpriseInspectionFlowEnabled()`
- 当前 Wi-Fi 是否连接
- `EXTRA_NEXT_HOME_ACTIVITY`

分流目标包括：

- `AiInspectionActivity`
- `EnterpriseQrScanActivity`
- `WifiQrScanActivity`

### 其他公开入口

从 `AndroidManifest.xml` 可确认，应用还暴露了这些入口：

- `InspectionModeActivity`
- `HomeActivity`
- `CameraActivity`
- `CameraPageActivity`
- `HiddenRiskProbeActivity`
- `EnterpriseQrScanActivity`
- `WifiQrScanActivity`
- `HazardRecordActivity`
- `DeviceGuideActivity`
- `LightshotActivity`
- `UnifiedInputDebugActivity`

这些入口并不都属于主链，但都在同一应用里共享 SDK、相机和会话层。

## Module Map

### 1. Application And Global State

- `app/src/main/java/com/rokid/glass/MyApplication.kt`
- `app/src/main/java/com/rokid/glass/workflow/InspectionWorkflowSession.kt`

职责：

- 应用生命周期级初始化
- 巡检全局上下文缓存
- 应用退出时清理企业信息和巡检累计结果

### 2. HiddenRisk Inspection Flow

- `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/DeviceGuideActivity.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/HazardRecordActivity.kt`
- `app/src/main/java/com/rokid/glass/InspectionEndReportActivity.kt`

职责：

- 组织巡检主页面流程
- 维护检测、详情、保存、结束等状态
- 处理自动识别和手动流式分析
- 连接后台上报和结束巡检接口

### 3. Enterprise And Wi-Fi Branch

- `app/src/main/java/com/rokid/glass/EnterpriseQrScanActivity.kt`
- `app/src/main/java/com/rokid/glass/WifiQrScanActivity.kt`
- `app/src/main/java/com/rokid/glass/EnterpriseInfoActivity.kt`
- `app/src/main/java/com/rokid/glass/EnterpriseObjectMessageService.kt`

职责：

- 扫描企业二维码
- 解析企业信息并写入巡检会话
- 扫描 Wi-Fi QR 并完成网络连接验证
- 给巡检主链提供企业侧上下文

### 4. Camera And Frame Source Layer

- `app/src/main/java/com/rokid/glass/camera/RokidFrameSource.kt`
- `app/src/main/java/com/rokid/glass/camera/RokidCameraManager.kt`
- `app/src/main/java/com/rokid/glass/camera/RokidCameraRecoveryController.kt`
- `app/src/main/java/com/rokid/glass/component/RokidCameraPreviewView.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionCameraCoordinator.kt`

职责：

- 统一管理相机帧流
- 处理预览绑定与恢复
- 用 generation 防止旧回调污染新页面

### 5. Input Layer

- `app/src/main/java/com/rokid/glass/input/UnifiedInput.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/HeadGestureManager.kt`

职责：

- 将触控、语音、头部动作统一成一个动作层
- 页面只声明动作和触发源，不直接散落注册逻辑
- 对 Rokid SDK 的离线语音能力做封装适配

### 6. UI Components

- `app/src/main/java/com/rokid/glass/component/StatusAlertOverlayView.kt`
- `app/src/main/java/com/rokid/glass/component/StatusAlertStateMachine.kt`
- `app/src/main/java/com/rokid/glass/component/FunctionMenuView.kt`
- `app/src/main/java/com/rokid/glass/component/GlassStatusBar.kt`
- `app/src/main/java/com/rokid/glass/component/BottomPromptView.kt`

职责：

- 状态提示浮层
- 功能菜单
- 状态栏
- 底部提示和引导卡片

### 7. Networking And Background Jobs

- `app/src/main/java/com/rokid/glass/hiddenrisk/AiArSseService.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionService.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/LocalHazardPushService.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionBackgroundUploadService.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionFinishService.kt`

职责：

- `/ai/ar` SSE 流式识别
- 在线隐患检测请求调度
- 本地隐患保存主备提交
- 后台静默上传队列
- 结束巡检主备提交

### 8. Native Inference Layer

- `app/src/main/java/com/rokid/glass/hiddenrisk/HiddenRiskNcnn.java`
- `app/src/main/jni/CMakeLists.txt`
- `app/src/main/jni/yolov8ncnn.cpp`
- `app/src/main/jni/yolov8.cpp`
- `app/src/main/jni/yolov8_det.cpp`

职责：

- 加载 `hiddenrisk.ncnn.param` 和 `hiddenrisk.ncnn.bin`
- 初始化 ncnn GPU/CPU 推理环境
- 接收 Bitmap、NV21、HardwareBuffer
- 执行检测并回传统计

### 9. Embedded Dependencies

- `app/src/main/jni/ncnn/`
- `app/src/main/jni/opencv-mobile-4.13.0-android/`

这两者不是单独的外部包引用，而是被当前 JNI 构建系统直接消费的源码/依赖树。

## Key Flows

### 1. App Launch To Inspection

证据：

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt`
- `app/src/main/java/com/rokid/glass/MyApplication.kt`

流程：

1. App 启动进入 `InspectionLoadingActivity`
2. 页面初始化 Rokid SDK
3. 检查相机和媒体权限
4. 通过 `InspectionCameraCoordinator.acquire(...)` 预热共享帧流
5. 初始化成功后调用 `InspectionSession.markInitialized()`
6. 再根据企业流开关和当前网络状态分流到后续页面

### 2. AI Inspection Main Flow

证据：

- `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/AutoHazardPipelineDecider.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/AutoInferenceLoopDecider.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/AutoHazardPresentationCoordinator.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionService.kt`

流程：

1. 页面进入 `DETECTING`
2. 通过 `InspectionCameraCoordinator` 获取共享帧流
3. `InspectionFrameCaptureService` 选择候选帧
4. `AutoInferenceLoopDecider` 决定本地和在线链路是否启动
5. `OnlineHazardDetectionService` 通过 `AiArSseService` 发起 `ctype=1/2` 检测
6. 结果进入流式详情或本地备用链路
7. `AutoHazardPresentationCoordinator` 控制结果展示延迟
8. 最终进入确认、保存、上报或结束页

### 3. Enterprise QR And Wifi Branch

证据：

- `app/src/main/java/com/rokid/glass/EnterpriseQrScanActivity.kt`
- `app/src/main/java/com/rokid/glass/WifiQrScanActivity.kt`
- `app/src/main/java/com/rokid/glass/workflow/InspectionWorkflowSession.kt`

流程：

1. 加载页按企业流开关决定是否进入企业扫码链路
2. 企业扫码页复用共享帧流识别二维码
3. 成功后解析企业信息并写入 `InspectionWorkflowSession`
4. Wi-Fi 扫码页负责网络接入和后续跳转
5. 这些上下文影响后续巡检与上报参数

### 4. Local Save And Finish Submission

证据：

- `app/src/main/java/com/rokid/glass/hiddenrisk/LocalHazardPushService.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionBackgroundUploadService.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionFinishService.kt`

流程：

1. 页面把隐患结果整理为本地上传 payload
2. `LocalHazardPushService` 并行提交主备端点
3. `InspectionBackgroundUploadService` 可在后台静默消费队列任务
4. 结束巡检时 `InspectionFinishService` 并行提交主备接口
5. 结果状态回写 `InspectionWorkflowSession`

### 5. Unified Input

证据：

- `app/src/main/java/com/rokid/glass/input/UnifiedInput.kt`

流程：

1. 页面声明动作 ID、触发源、启用条件
2. `UnifiedInputSession` 统一适配触控、语音、头部动作
3. 页面通过 `dispatchTouch()` 处理系统按键
4. 语音动作会通过 Rokid SDK 离线命令服务注册
5. 需要头部动作时再启用手势监听

### 6. Native Inference

证据：

- `app/src/main/jni/CMakeLists.txt`
- `app/src/main/jni/yolov8ncnn.cpp`
- `app/src/main/jni/yolov8.cpp`
- `app/src/main/jni/yolov8_det.cpp`

流程：

1. `HiddenRiskNcnn.loadModel()` 调到 JNI `loadModel`
2. JNI 根据 backend、gpuProfile、targetSize 配置 ncnn 参数
3. 读取 `hiddenrisk.ncnn.param` 和 `hiddenrisk.ncnn.bin`
4. 使用 `YOLOv8_det_hiddenrisk` 加载模型
5. `submitBitmap`、`submitNv21`、`submitHardwareBuffer` 进入检测
6. 统计与结果通过 `NativeInferenceStats` 回传 Java/Kotlin

## Data And State

### 关键会话对象

- `InspectionWorkflowSession`
  - 保存巡检模式、企业信息、检测与分析文本、保存结果、结束提交进度、图片缓存
  - 是页面间上下文的核心枢纽

- `InspectionSession`
  - 缓存 `HiddenRiskNcnn`
  - 维护模型加载状态、帧流状态、初始化状态、错误信息

- `InspectionCameraCoordinator`
  - 管理相机 owner、state、generation
  - 避免旧异步回调污染新会话

### 主要状态机

- `InspectionLoadingActivity.LoadingStage`
- `AiInspectionActivity.PageState`
- `AutoHazardPipelineDecider.PipelineMode`
- `AutoInferenceLoopDecider.OnlineLoopAdvance`
- `InspectionCameraCoordinator.CameraSessionState`
- `InspectionWorkflowSession.WorkflowMode`
- `InspectionWorkflowSession.SaveOutcome`

### 数据流

- 企业扫码内容 -> `InspectionWorkflowSession.enterpriseQrPayload`
- 共享帧流 -> `InspectionFrameCaptureService` -> JPEG bytes
- JPEG bytes -> SSE 或 JNI 检测
- 检测结果 -> `InspectionWorkflowSession.recordDetection()` / `recordAnalysis()`
- 保存/结束提交进度 -> `InspectionWorkflowSession.phoneSyncProgress` / `finishSubmitProgress`

## External Dependencies

### Android Platform

Manifest 中声明了相机、录音、蓝牙、Wi-Fi、存储、前台服务、定位等权限。

这说明应用运行时强依赖：

- Camera
- Battery
- Connectivity / Wi-Fi
- Foreground Service
- Storage
- HardwareBuffer

### Rokid SDK

- `app/build.gradle` 引入 `com.rokid.security:glass3.open.sdk:2.1.7-E`
- `RokidSdkManager.kt` 封装：
  - `GlassSdk.bindSecurityService(...)`
  - `GlassSdk.registerClient(...)`
  - `GlassSdk.getGlassDeviceService()`

### Network Stack

- OkHttp
- OkHttp SSE
- Gson

用于：

- `/ai/ar` SSE 流式识别
- 本地隐患保存
- 结束巡检提交

### ML / Vision

- ML Kit barcode scanning
- ncnn
- OpenCV

用于：

- QR 扫描
- 原生检测
- 相机帧预处理

### Local Assets

- `app/src/main/assets/hiddenrisk.ncnn.param`
- `app/src/main/assets/hiddenrisk.ncnn.bin`
- `app/src/main/assets/info.json`

## Configuration And Deployment

### Gradle

证据：

- `app/build.gradle`

关键配置：

- `compileSdk 34`
- `targetSdk 34`
- `minSdk 29`
- `ndkVersion "29.0.14206865"`
- product flavor：
  - `standard`
  - `demoOnlineonly`
- `externalNativeBuild` 指向 `app/src/main/jni/CMakeLists.txt`
- `buildFeatures` 开启 `compose`、`buildConfig`
- `viewBinding` 开启

### Native Build

证据：

- `app/src/main/jni/CMakeLists.txt`

关键点：

- 直接消费仓库内 `ncnn` 源码树
- 直接消费 `opencv-mobile-4.13.0-android`
- 预编译 Vulkan 相关静态库从 `ncnn-20260113-android-vulkan/${ANDROID_ABI}` 引入
- 最终生成 `hiddenriskncnn` shared library

### Runtime Preconditions

系统运行前提包括：

- 相机权限
- 媒体读写权限
- 网络权限
- Rokid SDK 可用
- 共享帧源可成功初始化

## Tests And Verification

### 从源码可确认的验证面

以下模块很适合做 JVM 单测或纯逻辑验证：

- `AutoHazardPipelineDecider`
- `AutoInferenceLoopDecider`
- `AutoHazardPresentationCoordinator`
- `InspectionCameraCoordinator.StateMachine`

### 设备侧验证面

JNI 和相机链路需要设备侧 smoke 验证：

- 模型加载是否成功
- 帧流是否就绪
- SSE 请求是否发出
- 本地保存和结束提交是否成功

### 当前未展开的部分

本次没有通读 `app/src/test` 和 `app/src/androidTest`，因此测试覆盖情况、缺口和断言质量没有写成强结论。

## Risks And Open Questions

- `docs/` 现有内容没有作为依据，本文只基于源码和构建配置。
- `InspectionFeatureFlags` 的具体开关定义没有展开到实现级别。
- `app/src/main/jni/ncnn` 和 `opencv-mobile` 体量很大，本次只确认它们是被本地构建系统直接消费的源码/依赖树。
- `HiddenRiskProbeActivity`、`CameraActivity`、`CameraPageActivity` 等是否应视为生产链还是调试/验证链，取决于后续是否要做裁剪或清理。

## Evidence

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/rokid/glass/MyApplication.kt`
- `app/src/main/java/com/rokid/glass/workflow/InspectionWorkflowSession.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionLoadingActivity.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionCameraCoordinator.kt`
- `app/src/main/java/com/rokid/glass/input/UnifiedInput.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/AutoHazardPipelineDecider.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/AutoInferenceLoopDecider.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/AutoHazardPresentationCoordinator.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/OnlineHazardDetectionService.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/LocalHazardPushService.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionBackgroundUploadService.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/InspectionFinishService.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/AiArSseService.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/HiddenRiskNcnn.java`
- `app/src/main/jni/CMakeLists.txt`
- `app/src/main/jni/yolov8ncnn.cpp`
- `app/src/main/jni/yolov8.cpp`
- `app/src/main/jni/yolov8_det.cpp`
- `app/build.gradle`
