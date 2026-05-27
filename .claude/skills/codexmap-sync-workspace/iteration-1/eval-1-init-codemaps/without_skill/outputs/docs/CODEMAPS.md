# CODEMAPS.md — 项目代码地图 (L2 内存)

> 本文档是三层文档体系中的 L2 层，位于 CLAUDE.md (L1 快速索引) 和各模块 README.md (L3 深度文档) 之间。
> 提供完整的文件清单、模块依赖图、数据流和关键接口契约。

## 1. 项目全景

### 1.1 基本信息

- **项目名称**: Rokid AR 眼镜 "基层应消" Android 应用
- **包名**: `com.rokid.glesse`
- **技术栈**: Kotlin + C++ (JNI via CMake) + Python (后端服务)
- **版本**: 2.0.6.2 (versionCode 8)
- **显示基线**: 480x640 px, 240 dpi (1dp = 1.5px)
- **推理引擎**: NCNN (Vulkan GPU) + 在线 SSE

### 1.2 带注释的目录树

```
rokid-latest/
├── app/                                # [主模块] Android 应用
│   ├── build.gradle                    # 应用构建配置 (productFlavors: standard, dataBackup)
│   └── src/main/
│       ├── assets/                     # 运行时资产
│       │   ├── hiddenrisk.ncnn.param   # NCNN 模型参数
│       │   ├── hiddenrisk.ncnn.bin     # NCNN 模型权重
│       │   ├── inspection_config.base.jsonc  # [核心] 推理/接口运行时配置
│       │   ├── inspection_config.standard.jsonc
│       │   ├── inspection_config.dataBackup.jsonc
│       │   └── *.wav                  # 语音提示音频
│       ├── java/com/rokid/glass/       # Kotlin/Java 源码
│       │   ├── hiddenrisk/             # [核心] 隐患识别推理模块 (~50 文件)
│       │   │   ├── README.md           # 模块深度文档 (L3)
│       │   │   └── state/              # 状态管理子包 (TtsState)
│       │   ├── camera/                 # 相机管理与帧捕获 (3 文件)
│       │   │   └── README.md
│       │   ├── input/                  # 统一输入层 (5 文件)
│       │   │   └── README.md
│       │   ├── workflow/               # 巡检工作流会话 (1 文件)
│       │   │   └── README.md
│       │   ├── component/              # 可复用 UI 组件 (8 文件)
│       │   │   └── README.md
│       │   ├── config/                 # 运行时配置系统 (2 文件)
│       │   │   └── README.md
│       │   ├── network/                # HTTP 客户端 (1 文件)
│       │   ├── data/                   # 全局数据/事件 (2 文件)
│       │   ├── utils/                  # 工具类与扩展函数 (13 文件)
│       │   ├── updater/                # App 版本更新 (4 文件)
│       │   ├── view/                   # 自定义 View (1 文件)
│       │   ├── adapter/                # RecyclerView 适配器 (1 文件)
│       │   └── *.kt                    # 根级 Activity (Home, Menu, QR Scan, EnterpriseInfo, WifiQr, EndReport, MyApplication)
│       ├── jni/                        # [核心] C++ JNI 原生推理层
│       │   ├── CMakeLists.txt          # CMake 构建配置
│       │   ├── yolov8ncnn.cpp          # JNI 桥接 (Java <-> C++)
│       │   ├── yolov8.cpp              # 模型加载、GPU 配置
│       │   ├── yolov8.h                # YOLOv8 类声明
│       │   ├── yolov8_det.cpp          # 检测推理 + DFL 解码 + NMS 后处理
│       │   ├── ncnn-20260113-android-vulkan/  # NCNN 预编译库 (Vulkan)
│       │   └── opencv-mobile-4.13.0-android/  # OpenCV Mobile 预编译库
│       └── res/                        # Android 资源文件
├── servers/HiddenRiskGlassServer/      # [后端] Python Flask 部署服务
│   ├── server.py                       # uvicorn 入口
│   ├── app/                            # FastAPI 应用
│   ├── Dockerfile                      # Docker 部署
│   ├── docker-compose.yml
│   └── config.json
├── models/                             # 模型导出脚本 (当前为空)
├── scripts/                            # 辅助脚本
│   ├── extract_images.sh
│   └── hazard_capture_manager.sh
├── release/                            # 已构建 APK 归档
├── test/                               # 测试文件归档
│   └── 2026-04-30_descrip_menu_visibility/
├── docs/                               # 项目文档
│   ├── Lessions/                       # 经验记录
│   └── superpowers/                    # 开发计划与设计文档归档
├── build.gradle                        # 根 Gradle 配置
├── settings.gradle                     # 项目设置
├── CLAUDE.md                           # [L1] AI Agent 快速参考
├── AGENTS.md                           # AI Agent 行为准则 + NCNN 经验细节
└── gradle/                             # Gradle Wrapper
```

## 2. 模块依赖图

```
                    ┌──────────────────────────────────────┐
                    │         hiddenrisk (核心业务层)        │
                    │  Activity × 6 | SSE × 5 | 推理 × 4   │
                    │  决策 × 3 | 会话 × 4 | 上传 × 5       │
                    │  深度分析 × 3 | UI辅助 × 9 | 基础 × 5 │
                    └──────┬──────┬──────┬──────┬──────────┘
                           │      │      │      │
              ┌────────────┘      │      │      └──────────────┐
              ▼                   ▼      ▼                     ▼
    ┌──────────────┐  ┌──────────────┐  ┌──────────┐  ┌──────────────┐
    │   camera/    │  │   input/     │  │ config/  │  │  workflow/   │
    │ 帧流捕获      │  │ 统一输入     │  │ 运行时配置│  │ 业务上下文    │
    └──────────────┘  └──────────────┘  └──────────┘  └──────────────┘
            │                │                │               │
            ▼                ▼                ▼               ▼
    ┌──────────────────────────────────────────────────────────────┐
    │                     component/ (UI 组件层)                    │
    │    状态栏 | 预览视图 | 菜单 | 提示栏 | 指引卡片 | 弹窗       │
    └──────────────────────────────────────────────────────────────┘
            │                │                │               │
            ▼                ▼                ▼               ▼
    ┌──────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────┐
    │ utils/   │  │  network/    │  │   data/      │  │ adapter/ │
    │ 工具类   │  │ HTTP 客户端  │  │ 全局数据     │  │ 适配器   │
    └──────────┘  └──────────────┘  └──────────────┘  └──────────┘

    ┌──────────────────────────────────────────────────────────────┐
    │                    JNI 原生层 (C++)                           │
    │  yolov8ncnn.cpp ──► yolov8.cpp ──► yolov8_det.cpp           │
    │       │                  │                                     │
    │       ▼                  ▼                                     │
    │  ┌──────────┐    ┌──────────────┐                             │
    │  │  NCNN    │    │ OpenCV Mobile│                             │
    │  │ (Vulkan) │    │ (图像预处理) │                             │
    │  └──────────┘    └──────────────┘                             │
    └──────────────────────────────────────────────────────────────┘

    ┌──────────────────────────────────────────────────────────────┐
    │                   后端服务 (Python)                           │
    │  servers/HiddenRiskGlassServer/                              │
    │  FastAPI + uvicorn → Docker 部署                             │
    │  APK 版本管理 + 更新下发                                      │
    └──────────────────────────────────────────────────────────────┘
```

### 2.1 依赖矩阵

| 模块 | 依赖 | 被依赖 |
|------|------|--------|
| `hiddenrisk/` | camera, input, workflow, config, component, utils, network, data | -- (顶层) |
| `camera/` | Android Camera2 API, Rokid Glass SDK | hiddenrisk, component |
| `input/` | Android Sensor API, Rokid Glass SDK | hiddenrisk |
| `workflow/` | -- (纯内存) | hiddenrisk |
| `config/` | Android AssetManager, Gson | hiddenrisk |
| `component/` | Android View 体系 | hiddenrisk |
| `utils/` | Android SDK, OkHttp, Glide | hiddenrisk, 全局 |
| `network/` | OkHttp | hiddenrisk |
| `updater/` | OkHttp, Gson | root Activities |
| `data/` | -- | 全局 |

## 3. 各模块详细文件清单

### 3.1 hiddenrisk/ — 隐患识别推理核心 (49 个 .kt 文件 + 3 个 .java 文件)

#### 3.1.1 页面 Activity (7 个)

| 文件 | 类型 | 职责 |
|------|------|------|
| `AiInspectionActivity.kt` | 核心页面 | AI 巡检主页面：自动检测 + 结果展示 + 手机端同步 |
| `InspectionLoadingActivity.kt` | 启动页 | SDK 初始化 + 相机预热 + 会话创建 |
| `HazardRecordActivity.kt` | 功能页 | 隐患拍照：拍照 → 分析 → 保存 |
| `DeviceGuideActivity.kt` | 功能页 | 设备指引：检查品判定 + 详情展示 |
| `HiddenRiskProbeActivity.kt` | 调试页 | NCNN 推理验证探针 (非正式功能) |
| `LightshotActivity.kt` | 历史页 | 历史调试页 (非产品基线) |
| `UnifiedInputDebugActivity.kt` | 调试页 | 统一输入调试 |
| `RawCameraPreviewDebugActivity.kt` | 调试页 | 原始相机预览调试 |

#### 3.1.2 在线推理/SSE 服务 (6 个)

| 文件 | 职责 |
|------|------|
| `AiArSseService.kt` | SSE 通信核心：封装 OkHttp SSE 请求 (`identifyItemHazard`, `requestDeepAnalysis`, `fetchInspectionGuide`) |
| `OnlineHazardDetectionService.kt` | 在线检测调度：管理请求队列 + 超时控制 |
| `AiArEventAggregator.kt` | SSE 事件流聚合 |
| `AiArHazardDetailParser.kt` | 远端隐患详情解析 → `ResolvedHazardContent` |
| `OnlineHazardAdviceFormatter.kt` | 在线建议文案格式化 |
| `ResolvedHazardContent.kt` | 解析后隐患内容数据类 |

#### 3.1.3 本地推理/后处理 (5 个)

| 文件 | 类型 | 职责 |
|------|------|------|
| `HiddenRiskNcnn.java` | Java | JNI 桥接：`detect()`, `loadModel()`, `setNumThread()` |
| `LocalHazardResultDeduper.kt` | Kotlin | 本地检测结果去重 |
| `LocalHazardItemMatcher.kt` | Kotlin | 本地检测结果匹配隐患类型 (16 类白名单) |
| `DetectionResult.java` | Java | 检测结果数据模型 |
| `NativeInferenceStats.java` | Java | 原生推理统计 |

#### 3.1.4 自动链路决策 (4 个)

| 文件 | 职责 |
|------|------|
| `AutoHazardPipelineDecider.kt` | 链路调度核心：决定使用远端/本地链路 |
| `AutoInferenceLoopDecider.kt` | 自动推理循环决策 |
| `OnlineHazardCompetitionDecider.kt` | 在线识别竞争决策 |
| `SharedInferenceFrameDecider.kt` | 共享推理帧决策 |
| `DualEndpointSubmitCoordinator.kt` | 双端点提交协调 |

#### 3.1.5 会话与帧流管理 (4 个)

| 文件 | 职责 |
|------|------|
| `InspectionSession.kt` | 全局巡检会话单例：持有 NCNN 引用、帧流、模型状态 |
| `InspectionCameraCoordinator.kt` | 相机帧流协调 (多页面抢占 `acquire/release`) |
| `InspectionFrameCaptureService.kt` | 帧捕获服务 |
| `InspectionBackendSessionId.kt` | 后端 session ID 管理 |

#### 3.1.6 隐患上传/保存 (6 个)

| 文件 | 职责 |
|------|------|
| `LocalHazardPushService.kt` | 本地隐患上传 |
| `LocalHazardUploadItemBuilder.kt` | 组装上传项 (空 hidNum 跳过, 按 hidNum 去重) |
| `InspectionBackgroundUploadQueue.kt` | 后台上传队列 |
| `InspectionBackgroundUploadService.kt` | 后台上传服务 |
| `InspectionFinishService.kt` | 结束巡检提交 |
| `HazardCaptureService.kt` | 隐患截图服务 |
| `HazardRecordUploadService.kt` | 隐患记录上传服务 |

#### 3.1.7 深度分析/验证 (4 个)

| 文件 | 职责 |
|------|------|
| `HazardDeepAnalysisService.kt` | 深度分析服务 |
| `MayHazardDeepVerifyProtocol.kt` | 疑似隐患深度验证协议 |
| `MayHazardDeepVerifyService.kt` | 疑似隐患深度验证服务 |
| `SuggestionChecksProtocol.kt` | 整改建议检查协议 |

#### 3.1.8 UI/辅助组件 (9 个)

| 文件 | 职责 |
|------|------|
| `AutoHazardPresentationCoordinator.kt` | 自动隐患展示协调 |
| `HiddenRiskMultiOverlayRenderer.kt` | 多框渲染 |
| `HazardRecordFrameOverlay.kt` | 拍照帧叠加层 |
| `HazardStreamService.kt` | 隐患流式服务 |
| `InferencePressureMonitor.kt` | 推理压力监控 |
| `SimulatedStreamTextChunker.kt` | 模拟流式文本分块 |
| `SquareViewfinderOverlay.kt` | 取景器叠加层 |
| `InspectionRetryExecutor.kt` | 重试执行器 (最多 4 次, 1s/2s/3s 递增延迟) |
| `DualEndpointSubmitCoordinator.kt` | 双端点提交协调 (见 3.1.4) |

#### 3.1.9 基础/系统 (5 个)

| 文件 | 职责 |
|------|------|
| `BaseGlassActivity.kt` | Activity 基类 |
| `RokidSdkManager.kt` | Rokid SDK 管理器 |
| `HeadGestureManager.kt` | 头部手势管理 |
| `MotionStabilityTracker.kt` | 头部稳定性跟踪 |
| `GlassKeyEvent.kt` | 眼镜按键事件定义 |

#### 3.1.10 状态管理 (state/) — 1 个文件

| 文件 | 职责 |
|------|------|
| `TtsState.kt` | TTS 语音播报状态管理 |

### 3.2 camera/ — 相机管理与帧捕获 (3 个文件)

| 文件 | 职责 |
|------|------|
| `QuickCameraManager.kt` | 相机管理器：打开相机、预览、拍照 (`initialize`, `attachPreviewTexture`, `takePicture`, `GpuFrame`) |
| `RokidCameraRecoveryController.kt` | 相机恢复控制器：检测异常并自动重连 |
| `RokidFrameSource.kt` | 帧源抽象：统一帧获取接口 |

### 3.3 input/ — 统一输入层 (5 个文件)

| 文件 | 职责 |
|------|------|
| `UnifiedInput.kt` | 统一输入核心：注册动作、分发触控/语音/头部手势 (`UnifiedInputSession`, `InputActionSpec`, `InputTrigger`) |
| `AutoSleepStateMachine.kt` | 自动休眠状态机：摘镜检测 + 休眠提示 (`Config`, `Snapshot`, `tick()`) |
| `AutoSleepController.kt` | 自动休眠控制器：协调传感器 + 状态机 + UI |
| `HeadMotionStabilityTracker.kt` | 头部稳定性跟踪：陀螺仪数据 → 稳定性判断 |
| `GlassesWearMonitor.kt` | 眼镜佩戴状态广播监听 |

### 3.4 workflow/ — 巡检工作流会话 (1 个文件)

| 文件 | 职责 |
|------|------|
| `InspectionWorkflowSession.kt` | 巡检业务上下文单例：企业信息、检测结果、截图、上传记录 (`beginInspection`, `updateEnterpriseFromQr`, `recordDetection`, `buildEndReportRecords`) |

### 3.5 component/ — 可复用 UI 组件 (8 个文件)

| 文件 | 职责 |
|------|------|
| `GlassStatusBar.kt` | 顶部状态栏 (时间 + 电量) |
| `RokidCameraPreviewView.kt` | 相机预览视图 (渲染帧流 + 健康监控) |
| `FunctionMenuView.kt` | 右上功能菜单 |
| `BottomPromptView.kt` | 底部提示栏 |
| `OperationGuideView.kt` | 操作指引卡片 |
| `StatusAlertOverlayView.kt` | 状态弹窗叠层 (倒计时 + 动画 + 自动消失) |
| `StatusAlertStateMachine.kt` | 弹窗状态机 (显示/隐藏决策) |
| `StatusAlertModels.kt` | 弹窗数据模型 (`StatusAlertModel`, `AlertBehavior`, `AlertStyle`) |

### 3.6 config/ — 运行时配置系统 (2 个文件)

| 文件 | 职责 |
|------|------|
| `InspectionAppConfig.kt` | 配置数据类：`AiInspectionConfig` (targetInputSize, backend, gpuProfile, autoInferenceMode), `NetworkConfig` (aiAutoApi, aiArApi, saveResultApi), `FeatureFlagsConfig` |
| `InspectionConfigRepository.kt` | 配置加载器：从 assets 读取 + 风味合并 (`init`, `get`, `merge`) |

### 3.7 其他模块

| 模块 | 文件数 | 核心文件 | 职责 |
|------|--------|----------|------|
| `network/` | 1 | `HttpClientProvider.kt` | OkHttp 客户端单例提供 |
| `data/` | 2 | `GlobalData.kt`, `YXData.java` | 全局状态/事件 |
| `utils/` | 13 | `AppFileLogger.kt`, `SSEUtil.kt`, `OfflineTtsPlayer.kt`, `WifiQrParser.kt`, `BitmapUtils.kt`, `DeviceUtil.java`, `DisplayUtils.kt`, `StringUtils.kt`, `SystemStateUtils.kt`, `ToastUtil.kt`, `SpriteToastUtil.java`, `Scopes.kt`, `kt_ext_flow.kt` | 工具类与扩展 |
| `updater/` | 4 | `AppUpdateManager.kt`, `AppUpdateClient.kt`, `AppUpdateInfo.kt`, `AppUpdatePromptActivity.kt` | App 版本检查与更新 |
| `view/` | 1 | `VoiceButton.kt` | 语音按钮自定义 View |
| `adapter/` | 1 | `MenuCardAdapter.kt` | 菜单卡片 RecyclerView 适配器 |

### 3.8 根级 Activity (10 个文件)

| 文件 | 职责 |
|------|------|
| `HomeActivity.kt` | 导航主页 |
| `AiInspectionMenuActivity.kt` | AI 巡检菜单 (功能入口选择) |
| `EnterpriseInfoActivity.kt` | 企业信息展示页 |
| `EnterpriseQrScanActivity.kt` | 企业二维码扫描 |
| `EnterpriseObjectMessageService.kt` | 企业对象消息服务 |
| `WifiQrScanActivity.kt` | WiFi 二维码扫描配网 |
| `InspectionEndReportActivity.kt` | 结束巡检报告页 |
| `InspectionEndReportReturnDestination.kt` | 结束报告返回目标 |
| `MyApplication.kt` | Application 入口 |
| `GlassesButtonReceiver.kt` | 眼镜按钮广播接收器 |
| `InspectionFeatureFlags.kt` | 巡检功能开关 |

## 4. 数据流

### 4.1 推理双轨数据流 (核心架构决策)

```
┌─────────────────────────────────────────────────────────────┐
│                  AutoHazardPipelineDecider                   │
│                    (链路调度决策中枢)                         │
└───────────────┬─────────────────────────┬───────────────────┘
                │                         │
    网络可用? YES                 网络不可用 / 远端连续失败
                │                         │
                ▼                         ▼
┌───────────────────────────┐  ┌──────────────────────────────┐
│     在线 SSE 推理链路      │  │      本地 NCNN 推理链路      │
│                           │  │                              │
│ OnlineHazardDetection     │  │ InspectionSession            │
│   .submitDetection()      │  │   .loadModel()               │
│   └─► AiArSseService      │  │   └─► HiddenRiskNcnn         │
│       └─► POST /ai/auto   │  │       └─► JNI yolov8ncnn    │
│       └─► SSE /ai/deep    │  │           └─► yolov8_det     │
│       └─► SSE /ai/device  │  │               └─► NCNN GPU  │
│                           │  │                              │
│ AiArEventAggregator       │  │ LocalHazardResultDeduper     │
│   └─► AiArHazardDetail    │  │   └─► LocalHazardItemMatcher │
│       Parser.parse()      │  │                              │
└───────────┬───────────────┘  └──────────────┬───────────────┘
            │                                 │
            └────────────┬────────────────────┘
                         ▼
            ┌─────────────────────────┐
            │  AutoHazardPresentation │
            │    Coordinator (展示)    │
            └─────────────────────────┘
                         │
             用户确认 ───► 隐患推送
                         ▼
            ┌─────────────────────────┐
            │ LocalHazardUploadItem   │
            │   Builder.build()       │
            │   └─► LocalHazardPush   │
            │       Service.push()    │
            │       └─► POST pushHid  │
            │           Danger        │
            └─────────────────────────┘
```

### 4.2 初始化数据流

```
MyApplication.onCreate()
  └─► InspectionSession (单例创建, 不加载模型)

HomeActivity → 点击"实时分析"
  ├─► InspectionSession.isInitialized? YES → AiInspectionActivity
  └─► NO → InspectionLoadingActivity
      ├─► RokidSdkManager.ensureInitialized()
      ├─► InspectionSession.initFrameStream()
      │   └─► QuickCameraManager.initialize()
      ├─► InspectionSession.markInitialized()
      ├─► InspectionWorkflowSession.beginInspection(sessionId)
      └─► 导航到 AiInspectionActivity
```

### 4.3 输入事件分发流

```
硬件层:
  触控板      ──► GlassKeyEvent
  麦克风      ──► VoiceRecognition text
  陀螺仪      ──► SensorEvent (HeadMotion)

          ▼
  UnifiedInputSession.dispatch()
    ├─► dispatchTouch(key)    → TouchTrigger
    ├─► dispatchVoice(text)   → VoiceTrigger
    └─► dispatchHeadMotion()  → HeadMotionTrigger
          │
          ▼
  匹配当前 Activity.buildInputActions() 注册的 InputActionSpec
    └─► 筛选 pageState 匹配项
        └─► 执行 Action (lambda)
```

### 4.4 配置加载流

```
InspectionConfigRepository.init(context, flavor)
  ├─► loadFromAssets("inspection_config.base.jsonc")
  ├─► 若 flavor != base:
  │   └─► overlayAssetName("inspection_config.{flavor}.jsonc")
  └─► merge(baseConfig, overlayConfig)
      └─► InspectionAppConfig (immutable snapshot)
          ├─► AiInspectionConfig (推理参数)
          ├─► NetworkConfig (API 端点)
          └─► FeatureFlagsConfig (特性开关)
```

### 4.5 帧流共享协调

```
AiInspectionActivity          HazardRecordActivity
       │                              │
       │ acquire()                    │ acquire()
       ▼                              ▼
  InspectionCameraCoordinator (互斥锁)
       │
       ▼
  QuickCameraManager (单一相机实例)
       │
       ▼
  ImageReader.OnImageAvailable
       │
       ▼
  HardwareBuffer → GpuFrame → RokidFrameSource
       │
       └──► 当前持有 acquire 的 Activity 消费帧
```

## 5. 关键接口契约

### 5.1 JNI 接口 (HiddenRiskNcnn.java ↔ yolov8ncnn.cpp)

```kotlin
// Java 侧: HiddenRiskNcnn.java
class HiddenRiskNcnn {
    fun loadModel(paramPath: String, binPath: String, useGpu: Boolean, gpuProfile: Int): Int
    fun detect(bitmap: Bitmap, targetSize: Int): Array<DetectionResult>
    fun release()
    fun setNumThread(threads: Int)
    fun getModelInputSize(): Int  // 返回当前 target_size (640)
}
```

### 5.2 SSE 接口 (AiArSseService)

```kotlin
// AiArSseService.kt
class AiArSseService {
    fun identifyItemHazard(imageBytes: ByteArray): RequestHandle
        // POST /ai/auto — 物品识别判定

    fun requestDeepAnalysis(imageBytes: ByteArray): RequestHandle
        // SSE /ai/deep — 深度分析 (流式返回描述文本)

    fun fetchInspectionGuide(imageBytes: ByteArray): RequestHandle
        // SSE /ai/device — 设备指引

    class RequestHandle { fun cancel() }
}
```

### 5.3 会话接口 (InspectionSession)

```kotlin
// InspectionSession.kt — 全局单例
object InspectionSession {
    val isInitialized: Boolean
    val ncnn: HiddenRiskNcnn?
    val modelLoaded: Boolean
    val frameStream: RokidFrameSource?

    fun createNcnnInstance()
    fun loadModel(): Int
    fun initFrameStream()
    fun markInitialized()
    fun reset()
    fun release()
}
```

### 5.4 工作流会话接口 (InspectionWorkflowSession)

```kotlin
// InspectionWorkflowSession.kt — 全局单例
object InspectionWorkflowSession {
    val enterpriseQrPayload: EnterpriseQrPayload?
    val sessionId: String?

    fun updateEnterpriseFromQr(qrContent: String)
    fun beginInspection(sessionId: String)
    fun recordDetection(title: String, message: String)
    fun recordAnalysis(text: String)
    fun recordCapture(jpegBytes: ByteArray)
    fun recordSavedHazardAttempt(key: String, itemCount: Int)
    fun updateSavedHazardAttemptOutcome(key: String, outcome: Outcome, hints: List<String>)
    fun buildEndReportRecords(): List<EndReportRecord>
    fun clearForNewInspection()
}
```

### 5.5 统一输入接口 (UnifiedInput)

```kotlin
// UnifiedInput.kt
data class InputActionSpec(
    val trigger: InputTrigger,      // TouchTrigger | VoiceTrigger | HeadMotionTrigger
    val pageState: String,           // 当前页面态 (DETECTING, DESCRIPTION, ADVICE...)
    val action: () -> Unit           // 匹配时执行的动作
)

class UnifiedInputSession {
    fun attach(activity: BaseGlassActivity)
    fun updateActions(specs: List<InputActionSpec>)
    fun dispatchTrigger(trigger: InputTrigger)
    fun detach()
}
```

### 5.6 配置接口 (InspectionConfigRepository)

```kotlin
// InspectionConfigRepository.kt
object InspectionConfigRepository {
    val currentConfig: InspectionAppConfig

    fun init(context: Context, flavor: String = "base")
    fun get(): InspectionAppConfig
    fun reloadForTest(json: String)  // 仅测试用
}
```

## 6. 构建系统

### 6.1 Gradle 配置

```
settings.gradle
  └─► :app (Android application)

app/build.gradle
  ├─► namespace: com.rokid.glesse
  ├─► compileSdk: 34, minSdk: 29, targetSdk: 34
  ├─► versionCode: 8, versionName: 2.0.6.2
  ├─► NDK: 29.0.14206865
  ├─► productFlavors: standard, dataBackup
  └─► externalNativeBuild: CMake → app/src/main/jni/CMakeLists.txt
```

### 6.2 CMake 配置 (JNI 层)

```
CMakeLists.txt
  ├─► 依赖: OpenCV Mobile 4.13.0 (core, imgproc)
  ├─► 依赖: NCNN 20260113 Vulkan (prebuilt static libs)
  ├─► 源文件: yolov8ncnn.cpp, yolov8.cpp, yolov8_det.cpp
  └─► 输出: libhiddenriskncnn.so
```

### 6.3 后端服务

```
servers/HiddenRiskGlassServer/
  ├─► 运行: uvicorn (ASGI) on port 10203
  ├─► 框架: FastAPI
  ├─► 部署: Docker (Dockerfile + docker-compose.yml)
  └─► 功能: APK 版本管理、更新下发、认证
```

## 7. 文档体系索引

### 三层文档结构

| 层级 | 文件 | 定位 | 用途 |
|------|------|------|------|
| **L1** | `CLAUDE.md` | 快速参考 (AI Agent 上下文) | 项目概述、构建命令、模块索引、代码风格 |
| **L2** | `docs/CODEMAPS.md` (本文件) | 代码地图 (结构化索引) | 完整文件清单、依赖图、数据流、接口契约 |
| **L3** | 各模块 `README.md` | 深度文档 | 业务概述、详细调用链、关键入口、依赖关系 |

### L3 模块文档列表

| 模块 | README 路径 | 覆盖内容 |
|------|------------|----------|
| hiddenrisk | `app/src/.../hiddenrisk/README.md` | 巡检页面、在线/本地推理、自动链路、隐患上传、设备指引、拍照录入 |
| camera | `app/src/.../camera/README.md` | 相机管理、帧捕获、预览、恢复控制 |
| input | `app/src/.../input/README.md` | 触控、语音、头部动作映射、自动休眠 |
| workflow | `app/src/.../workflow/README.md` | 跨页面业务上下文、企业信息、QR 解析 |
| component | `app/src/.../component/README.md` | 状态栏、取景器、菜单、弹窗、提示 |
| config | `app/src/.../config/README.md` | 运行时配置、推理参数、API 端点、特性开关 |

## 8. 维护说明

- **更新时机**: 新增模块、重构模块边界、模块间新增/移除依赖时，需同步更新本文档。
- **更新范围**: 第 2 节 (依赖图)、第 3 节 (文件清单)、第 4 节 (数据流) 需保持与实际代码一致。
- **与 L1/L3 的关系**: 本文件不重复 CLAUDE.md 的构建命令和代码风格内容，也不重复各模块 README 的业务细节。
- **CodeGraph 索引**: 本项目已预计算 CodeGraph 知识图谱 (`.codegraph/codegraph.db`)，可用于快速查询调用链和影响范围，与本文档互补充。
