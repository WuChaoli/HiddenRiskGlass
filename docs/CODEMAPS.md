# CODEMAPS -- 模块关系地图

> 三层文档体系：CLAUDE.md (L1缓存) -> 本文档 (L2内存) -> 模块CLAUDE.md (L3硬盘)
> 本文档描述模块间的关系、数据流、边界规则。模块内部细节见各 CLAUDE.md。
>
> 最后更新: 2026-07-27

---

## 1. 架构层级图

应用采用五层架构，自上而下为页面层、会话层、输入层、相机层、识别链路层。
每层只依赖下层（或其抽象），不反向引用。

```mermaid
flowchart TB
    subgraph L1_PAGE[页面层]
        MainMenu[主菜单 MainMenuActivity<br/>LAUNCHER入口]
        Menu[二级菜单 AiInspectionMenuActivity]
        Loading[启动加载 InspectionLoadingActivity]
        Inspection[AI巡检 AiInspectionActivity]
        Record[隐患拍照 HazardRecordActivity]
        Guide[设备指引 DeviceGuideActivity]
        Report[结束报告 InspectionEndReportActivity]
        Probe[探针调试 HiddenRiskProbeActivity]
    end

    subgraph L2_SESSION[会话层]
        IS[InspectionSession<br/>全局单例<br/>持有NCNN/帧流/模型状态]
        IWS[InspectionWorkflowSession<br/>业务上下文<br/>企业信息/检测结果/上传记录]
    end

    subgraph L3_INPUT[输入层]
        UI[UnifiedInput<br/>触控+语音+头部手势统一抽象]
        Sleep[AutoSleepStateMachine<br/>摘镜自动休眠]
        Head[HeadMotionStabilityTracker<br/>陀螺仪稳定性跟踪]
    end

    subgraph L4_CAMERA[相机层]
        QCM[QuickCameraManager<br/>Camera2 API GPU帧捕获]
        RFS[RokidFrameSource<br/>统一帧获取接口]
        RC[RokidCameraRecoveryController<br/>异常自动恢复]
    end

    subgraph L5_INFERENCE[识别链路层]
        NCNN[NCNN YOLOv8<br/>本地端侧推理 Vulkan]
        LocalRule[LocalHazardRuleEvaluator<br/>四类缺失保护组合规则]
        LocalDetail[LocalHazardDetailResolver<br/>本地 info.json 详情]
        SSE[SSE在线推理<br/>OkHttp /ai/auto /ai/deep]
        Pipeline[AutoHazardPipelineDecider<br/>双轨调度]
        JNI[JNI桥接<br/>yolov8ncnn.cpp]
    end

    L1_PAGE --> L2_SESSION
    L1_PAGE --> L3_INPUT
    L4_CAMERA --> L5_INFERENCE
    L2_SESSION --> L5_INFERENCE
    L2_SESSION --> L4_CAMERA
```

**层级职责一句话**：

| 层 | 职责 | 核心类/文件 |
|---|------|------------|
| 页面层 | Activity 生命周期、UI 渲染、用户交互响应 | 6个 Activity (见 hiddenrisk/README) |
| 会话层 | 全局巡检状态共享，避免重复初始化 | `InspectionSession`, `InspectionWorkflowSession` |
| 输入层 | 多模态输入统一抽象和分发 | `UnifiedInput`, `AutoSleepStateMachine` |
| 相机层 | 相机生命周期、帧流捕获和恢复 | `QuickCameraManager`, `RokidFrameSource` |
| 识别链路层 | 双轨推理调度、本地NCNN推理、在线SSE推理 | `AutoHazardPipelineDecider`, `HiddenRiskNcnn`, `AiArSseService` |

`localTriger` 固定为 `OFFLINE_LOCAL + LOCAL_ONLY`：跳过 Wi-Fi 与企业扫码入口门禁，
空 `placeCode` 仍运行本地 NCNN；四类组合规则命中后由 `LocalHazardDetailResolver`
读取本地 `info.json`。`InspectionNetworkAccessPolicy` 在所有应用 OkHttp 客户端上提供
最终阻断，因此即使设备实际连接 Wi-Fi 也不会发送业务 HTTP/SSE 请求。

---

## 2. 模块依赖矩阵

### 模块一览

| 模块 | 包路径 | CLAUDE.md | 类型 | 文件数 |
|------|--------|--------|------|--------|
| hiddenrisk | `com.rokid.glass.hiddenrisk` | [README](app/src/main/java/com/rokid/glass/hiddenrisk/README.md) | 业务核心 | ~53 |
| camera | `com.rokid.glass.camera` | [CLAUDE.md](app/src/main/java/com/rokid/glass/camera/CLAUDE.md) | 基础设施 | 6 |
| input | `com.rokid.glass.input` | [CLAUDE.md](app/src/main/java/com/rokid/glass/input/CLAUDE.md) | 基础设施 | 6 |
| workflow | `com.rokid.glass.workflow` | [CLAUDE.md](app/src/main/java/com/rokid/glass/workflow/CLAUDE.md) | 业务上下文 | 1 |
| component | `com.rokid.glass.component` | [CLAUDE.md](app/src/main/java/com/rokid/glass/component/CLAUDE.md) | UI组件库 | 10 |
| config | `com.rokid.glass.config` | [CLAUDE.md](app/src/main/java/com/rokid/glass/config/CLAUDE.md) | 配置系统 | 2 |
| network | `com.rokid.glass.network` | [CLAUDE.md](app/src/main/java/com/rokid/glass/network/CLAUDE.md) | 基础设施 | 1 |
| updater | `com.rokid.glass.updater` | [CLAUDE.md](app/src/main/java/com/rokid/glass/updater/CLAUDE.md) | 功能模块 | 4 |
| utils | `com.rokid.glass.utils` | [CLAUDE.md](app/src/main/java/com/rokid/glass/utils/CLAUDE.md) | 工具库 | 13 |
| data | `com.rokid.glass.data` | -- | 全局状态 | 2 |
| jni | `app/src/main/jni/` | [CLAUDE.md](app/src/main/jni/CLAUDE.md) | 原生推理 | 4(+ncnn) |

### 依赖关系图

```mermaid
graph TD
    hiddenrisk --> camera
    hiddenrisk --> input
    hiddenrisk --> workflow
    hiddenrisk --> component
    hiddenrisk --> config
    hiddenrisk --> network
    hiddenrisk --> utils
    hiddenrisk --> data
    hiddenrisk --> updater
    hiddenrisk --> jni
    updater --> network
    updater --> utils
    camera --> |Android Camera2 API| Platform[Android Platform]
    camera --> |Rokid Glass SDK| Platform
    input --> |Sensor API| Platform
```

**解读**：
- `hiddenrisk/` 是依赖的汇聚点：几乎依赖所有其他模块，是业务编排层
- `workflow/` 零依赖，是纯内存状态容器
- `camera/` 和 `input/` 仅依赖 Android 平台 API，是纯基础设施
- 依赖方向严格：业务模块 -> 基础设施，从未反向

### 模块间协议/约定

| 接口 | 提供方 | 消费方 | 格式/约定 |
|------|--------|--------|-----------|
| 帧流 | `camera/` QuickCameraManager | `hiddenrisk/` 所有推理链路 | HardwareBuffer -> NV21 bitmap (640x640) |
| 统一输入 | `input/` UnifiedInputSession | `hiddenrisk/` 各 Activity | `InputActionSpec(action, trigger, pageState)` |
| 巡检上下文 | `workflow/` InspectionWorkflowSession | `hiddenrisk/` 各 Activity | `EnterpriseQrPayload`, sessionId, 检测记录 |
| 推理配置 | `config/` InspectionConfigRepository | `hiddenrisk/` 所有推理组件 | `InspectionAppConfig` (JSON deserialized) |
| JNI 推理 | `jni/` HiddenRiskNcnn.detect() | `hiddenrisk/` InspectionSession | NV21帧 -> `DetectionResult[]` |
| SSE 通信 | `hiddenrisk/` AiArSseService | `hiddenrisk/` OnlineHazardDetectionService | OkHttp SSE -> `ResolvedHazardContent` |
| HTTP 客户端 | `network/` HttpClientProvider | `hiddenrisk/`, `updater/` | OkHttpClient (单例) |
| Launcher 应用可见性 | `MyApplication` + `hiddenrisk/` RokidSdkManager | Rokid Glass SDK / Launcher | `GlassAppConfig(hiddenApps, thirdApps)`；亮屏后延迟两次幂等提交 |

---

## 3. 端到端数据流

### 3.1 核心链路：初始化 -> 自动检测

```mermaid
sequenceDiagram
    actor User as 用户
    participant MainMenu as MainMenuActivity
    participant QrScan as EnterpriseQrScanActivity
    participant Enterprise as EnterpriseInfoActivity
    participant Menu as AiInspectionMenuActivity
    participant Loading as InspectionLoadingActivity
    participant Session as InspectionSession
    participant AI as AiInspectionActivity

    User->>MainMenu: 启动 App (LAUNCHER)
    MainMenu->>MainMenu: EntryGuardCoordinator 后台静默初始化
    alt localTriger 完全离线模式
        MainMenu->>MainMenu: 跳过 WiFi 与更新检查，只初始化 SDK
    else WiFi 未连接
        MainMenu-->>User: 显示 WiFi 必需对话框
    else WiFi 已连接
        MainMenu->>MainMenu: SDK + 相机预热 + 更新检查
        MainMenu-->>MainMenu: onAllGuardsReady → 解锁"基层应消"
    end

    NCNN --> LocalRule --> LocalDetail
    User->>MainMenu: 点击"基层应消"
    MainMenu->>Menu: 跳转二级菜单
    alt localTriger 完全离线模式
        Menu->>Loading: 跳过企业上下文并进入统一加载页
    else standard 业务 Mock 开启
        Menu->>Menu: 注入固定 placeCode，跳过企业扫码
        Menu->>Loading: 进入统一加载页
    else 企业信息为空
        Menu->>QrScan: 跳转企业扫码
        QrScan->>Enterprise: 解析并确认企业信息
        Enterprise->>Loading: 确认企业信息
    else 企业信息已存在
        Menu->>Loading: 进入统一加载页
    end
    Loading->>Session: ensureModelLoaded()
    Session-->>Loading: 模型加载完成
    Loading->>Menu: 放行进入二级菜单
    Note over Menu,AI: 业务页发现模型未就绪时只能跳回 Loading
    Menu->>AI: 用户选择"实时分析"
    AI->>AI: DETECTING 态，开始自动检测循环
```

### 3.2 核心链路：在线自动识别与结构化深度分析

> 自动、手动、隐患拍照与环境检测统一使用 V2 结构化 JSON：按 `placeCode` 和来源路由到 `/ai/deep/v2`、`/ai/general_deep/v2` 或 `/ai/gm/v2`。`localTriger` 仍为完全离线，不发送业务网络请求。

```mermaid
sequenceDiagram
    participant AI as AiInspectionActivity
    participant ODS as OnlineHazardDetectionService
    participant SSE as AiArSseService(/auto)
    participant V2 as DeepV2Client
    participant Remote as 远端 /ai/auto + /ai/deep/v2

    AI->>ODS: /auto 上传完整 1200x1600 frame
    ODS->>SSE: identifyItemHazard()
    alt placeCode 缺失
        SSE-->>AI: skipHazardDetection(hasHazard=false)
    else placeCode 存在
        SSE->>Remote: HTTP POST (NV21帧)
        Remote-->>SSE: detections
    end
    SSE-->>ODS: 自动检测结果
    AI->>AI: 持续画全部框；筛选未处于共享 15 秒冷却的 label
    AI->>AI: 任一未冷却 bbox 面积 > 1/8
    AI->>AI: 同帧按固定 1m 校准裁成真实世界对齐 3:4 JPEG
    AI->>V2: 单飞 request(aligned 3:4 JPEG)
    V2->>Remote: POST /ai/deep/v2（1200x1600 原图按当前眼镜视野校准裁切）
    Remote-->>V2: JSON detections + hazards + check_items
    V2-->>AI: label_id 关联并归一化
    AI->>AI: 结构化结果页浏览、确认保存或取消重检
    AI->>AI: 无隐患立即、有隐患则回到 /auto 时启动 label 冷却
```

### 3.3 核心链路：本地 NCNN 推理（fallback / localTriger 主链路）

```mermaid
sequenceDiagram
    participant Decider as AutoHazardPipelineDecider
    participant Session as InspectionSession
    participant JNI as HiddenRiskNcnn (Java)
    participant Native as yolov8ncnn.cpp
    participant Dedup as LocalHazardResultDeduper

    alt localTriger
        Decider->>Decider: LOCAL_ONLY，忽略真实网络状态
    else 在线变体 fallback
        Decider->>Decider: 远端连续失败达阈值
    end
    Decider->>Session: loadModel()
    Session->>JNI: loadModel(param, bin)
    JNI->>Native: ncnn::Extractor + GPU Vulkan
    Native-->>JNI: 模型就绪
    Decider->>JNI: detect(NV21 frame)
    JNI->>Native: preprocess + inference + postprocess
    Native-->>JNI: DetectionResult[] (16类白名单)
    JNI->>Dedup: 去重
    Dedup-->>Decider: 最终检测列表
```

### 3.4 核心链路：隐患上传 -> 手机端同步

> `localTriger` 不进入本链路：企业巡检开关和业务网络总闸同时禁止
> `pushHidDanger` 与 `pushHidDangerEnd`，网络恢复也不会改变该权限。standard 的
> `businessMock` 联调开关开启时，两类上传也由独立策略门禁禁止，不依赖伪造企业数据。

```mermaid
sequenceDiagram
    participant AI as AiInspectionActivity
    participant Builder as LocalHazardUploadItemBuilder
    participant Push as LocalHazardPushService
    participant Remote as <QR baseUrl>/pushHidDanger
    participant Retry as InspectionRetryExecutor
    participant Session as InspectionWorkflowSession

    AI->>Builder: build(ResolvedHazardContent) -> 上传项
    Builder->>Builder: 跳过空hidNum，按hidNum去重
    AI->>Push: pushLocalHazard(items)
    Push->>Remote: HTTP POST
    alt 成功
        Remote-->>Push: 200 OK
        Push->>Session: updateSavedHazardAttemptOutcome(SUCCESS)
        Session-->>AI: 进入 ADVICE 态
    else 失败
        Push->>Retry: 重试 (最多4次, 1s/2s/3s递增)
        Retry-->>Push: 仍失败 -> Session.updateSavedHazardAttemptOutcome(FAILED)
        Session-->>AI: 留在 DESCRIPTION 态，显示错误
    end
```

### 3.5 辅助链路：隐患拍照录入

```mermaid
sequenceDiagram
    actor User as 用户
    participant HR as HazardRecordActivity
    participant Camera as QuickCameraManager
    participant V2 as DeepV2Client
    participant Push as LocalHazardPushService

    User->>HR: 单击/语音"拍照"
    HR->>Camera: takePicture()
    Camera-->>HR: JPEG 图片
    HR->>HR: COUNTDOWN -> 截帧并直接显示视野裁切底图
    HR->>V2: request() /ai/deep/v2 或 /ai/gm/v2
    V2-->>HR: detections + hazards + check_items
    HR->>HR: 有隐患时播放提示语音并叠加 bbox/hazard 翻页展示
    User->>HR: 确认
    HR->>Push: pushLocalHazard()
    HR->>HR: 保存请求发起后立即返回 IDLE
    Push-->>HR: 成功 -> 显示保存成功提示
```

### 3.6 辅助链路：设备指引判定

```mermaid
sequenceDiagram
    participant DG as DeviceGuideActivity
    participant SSE as AiArSseService
    participant Remote as /ai/auto + /ai/deep

    DG->>DG: DETECTING 态，循环检测
    loop 检测循环
        DG->>SSE: identifyItemHazard() /ai/auto
        SSE->>Remote: HTTP POST (帧)
        Remote-->>SSE: 判定结果
    end
    Note over DG: 命中 -> 暂停2秒
    DG->>SSE: requestDeepAnalysis() /ai/deep
    Remote-->>DG: RESULT/DETAIL 展示
```

### 3.7 系统链路：Launcher 应用可见性恢复

```mermaid
sequenceDiagram
    participant App as MyApplication
    participant KeepAlive as 可见性保活服务
    participant Scheduler as 亮屏重配调度器
    participant SDK as RokidSdkManager
    participant Launcher as Rokid Launcher
    participant Phone as 手机侧应用配置

    App->>SDK: SDK 首次就绪或服务重连
    SDK->>Launcher: configureAppVisibility(隐藏业务应用, 本App/扫一扫排序)
    App->>KeepAlive: Activity 前台恢复后启动
    KeepAlive->>KeepAlive: startForeground + START_STICKY
    Phone->>Launcher: 亮屏后可能下发默认配置
    App->>Scheduler: ACTION_SCREEN_ON
    Scheduler->>SDK: 300ms screen_on_first
    SDK->>Launcher: 重新提交目标配置
    Scheduler->>SDK: 1500ms screen_on_second
    SDK->>Launcher: 再次覆盖手机侧默认配置
```

`MyApplication` 持有动态 `SCREEN_ON` 监听；保活服务只提高进程存活优先级。`RokidSdkManager` 对 Binder 请求做单次执行保护，并将执行期间的新原因合并为最后一次待提交请求。

---

## 4. 架构不变量与边界规则

| # | 规则 | 原因 |
|---|------|------|
| R1 | **相机帧流只通过 InspectionSession 获取**，Activity 不直接持有 Camera 引用 | 避免多页面帧流抢占；`InspectionCameraCoordinator` 统一管理 acquire/release |
| R2 | **hiddenrisk/ 是唯一依赖汇聚点**，其他模块间不产生新的直接依赖 | 保持依赖图单向无环；新增跨模块依赖需同步更新本文档 |
| R3 | **JNI 调用只能通过 HiddenRiskNcnn.java**，Kotlin 代码不直接声明 native 方法 | JNI 签名集中在单一桥接层，降低接口变更的同步成本 |
| R4 | **NCNN param + bin 必须成对替换**，不允许只换一个 | 两次不同的导出会产生不兼容的模型结构，导致推理崩溃 |
| R5 | **配置只从 InspectionConfigRepository 读取**，禁止硬编码推理参数/API 端点 | 保证风味覆盖机制生效，避免"改了配置不生效"的调试陷阱 |
| R6 | **input/ 的 HEAD_GESTURE_LISTENING_ENABLED 当前全局关闭**，不要在生产代码中重新开启 | 头部手势尚未充分验证稳定性，误触发会干扰用户体验 |
| R7 | **上传前必须按 hidNum 去重，跳过空 hidNum** | `LocalHazardUploadItemBuilder.build()` 的职责，防止重复推送和空数据浪费带宽 |
| R8 | **Launcher 可见性配置必须可重复提交，不能只在 SDK 首次就绪时执行一次** | 重新佩戴亮屏后 Launcher 与手机侧会重新下发默认配置，需由亮屏延迟任务覆盖 |
| R9 | **`localTriger` 必须保持 `OFFLINE_LOCAL + LOCAL_ONLY`，空 placeCode 仍执行本地识别** | 跳过企业扫码后没有场景码；统一网络总闸保证 Wi-Fi 状态不能改变离线语义 |
| R10 | **`businessMock` 只能由风味配置显式开启，Mock 企业上下文不能生成可上传的 EnterpriseQrPayload** | 保证联调可调用 `/auto` 和 `/ai/deep`，同时从数据与策略两层阻断真实业务上传 |

---

## 5. 关键术语表

| 术语 | 含义 | 涉及模块 |
|------|------|----------|
| 双轨推理 | 同时运行本地 NCNN 和在线 SSE 两套推理链路 | hiddenrisk |
| DETECTING | AiInspectionActivity 自动检测态，持续截帧送检 | hiddenrisk |
| STREAM_RESPONSE | SSE 流式返回阶段，DESCRIPTION(确认) -> ADVICE(建议) | hiddenrisk |
| NCNN | 腾讯开源的高性能神经网络推理框架，本项目用 Vulkan GPU 后端 | jni, hiddenrisk |
| SSE | Server-Sent Events，OkHttp 实现的长连接流式协议，用于在线推理 | hiddenrisk |
| EnterpriseQrPayload | 企业扫码后解析的上下文 (authCode, objectId, userId, apiBaseUrl) | workflow |
| GpuFrame | HardwareBuffer 包装的 GPU 帧数据，用于 NCNN 推理输入 | camera, hiddenrisk |
| InspectionSession | 全局巡检单例，持有 NCNN 模型引用、帧流、初始化标记 | hiddenrisk |
| InspectionWorkflowSession | 跨页面业务上下文，记录企业信息、检测结果、上传历史 | workflow |
| 隐患白名单 | 16 类预定义隐患类别（燃气灶、灭火器、消火栓、电动车等） | hiddenrisk, jni |
| Dedup 去重 | 本地推理结果基于类别+位置消重，避免同一物体重复报警 | hiddenrisk |
| 风味覆盖 | Gradle flavor 机制，不同构建变体可覆盖配置项 | config |
| businessMock | standard 联调模式；固定 placeCode、跳过企业扫码并禁止隐患与结束上传 | config, workflow, hiddenrisk |
| OFFLINE_LOCAL | 应用业务完全离线策略；跳过 Wi-Fi/企业入口并阻断所有 HTTP/SSE | config, network, hiddenrisk |
| Vulkan | Android GPU 计算 API，NCNN 使用 Vulkan 后端进行端侧推理加速 | jni |

---

## 6. 常见任务入口速查

| 任务类型 | 起点文件 | 相关文档 |
|----------|----------|----------|
| 修改 LAUNCHER 入口或第一层菜单 | `MainMenuActivity.kt` + `EntryGuardCoordinator.kt` | 本文档 3.1 |
| 新增一个巡检页面 Activity | `hiddenrisk/AiInspectionActivity.kt` (参考) + `hiddenrisk/BaseGlassActivity.kt` (基类) | hiddenrisk/README.md |
| 修改本地 NCNN 推理参数 | `config/InspectionAppConfig.kt` (配置定义) + `hiddenrisk/HiddenRiskNcnn.java` (JNI调用) | config/CLAUDE.md, hiddenrisk/README.md |
| 替换 NCNN 模型 | `jni/yolov8ncnn.cpp` (后处理) + `assets/hiddenrisk.ncnn.param` + `assets/hiddenrisk.ncnn.bin` | 附录 A, AGENTS.md |
| 新增在线 API 端点 | `hiddenrisk/AiArSseService.kt` (SSE请求) + `config/InspectionAppConfig.kt` (端点配置) | hiddenrisk/README.md |
| 增加隐患类别(白名单) | `jni/yolov8_det.cpp` (类别映射) + 重新训练/导出模型 | 附录 A, AGENTS.md |
| 修改输入层动作映射 | `hiddenrisk/<Activity>.kt` 中的 `buildInputActions()` | input/CLAUDE.md |
| 调整相机预览/帧流 | `camera/QuickCameraManager.kt` + `component/RokidCameraPreviewView.kt` | camera/CLAUDE.md, component/CLAUDE.md |
| 修改上传重试策略 | `hiddenrisk/InspectionRetryExecutor.kt` | hiddenrisk/README.md |
| 新增可复用 UI 组件 | `component/` 目录，参考 `GlassStatusBar.kt` 或 `BottomPromptView.kt` | component/CLAUDE.md |
| 添加运行时配置项 | `config/InspectionAppConfig.kt` (字段) + `assets/inspection_config.base.jsonc` (默认值) | config/CLAUDE.md |
| 调整 localTriger 离线策略 | `assets/inspection_config.localTriger.jsonc` + `InspectionFeatureFlags.kt` + `network/InspectionNetworkAccessPolicy.kt` | hiddenrisk/README.md |
| 修改 QR 码解析格式 | `workflow/InspectionWorkflowSession.kt` 中 `updateEnterpriseFromQr()` | workflow/CLAUDE.md |
| 调试 NCNN 推理问题 | `hiddenrisk/HiddenRiskProbeActivity.kt` (探针页) + adb logcat 过滤 `detect ` | hiddenrisk/README.md |
| App 版本更新流程 | `updater/AppUpdateManager.kt` (入口) + `updater/AppUpdatePromptActivity.kt` (UI) | 见源码注释 |
| 修改应用初始化或开机自启动 | `MyApplication.kt` (初始化) + `utils/DeviceUtil.java` (系统属性访问) | utils/CLAUDE.md |
| 修改 Launcher 应用显示、隐藏或排序 | `hiddenrisk/AppVisibilityConfigFactory.kt` (配置) + `hiddenrisk/RokidSdkManager.kt` (提交) + `MyApplication.kt` (亮屏触发) | hiddenrisk/README.md |

---

## 附录 A：NCNN 模型流水线（参考）

- 旧 HiddenRisk Mini/YoloV11 源模型和根目录转换链已退役。
- 当前模型转换、CPU 对齐和发布包生成统一由同级 `../model_transformer/` 管理。
- Android 内置资产仍需成对替换，并单独完成项目构建与真机 Vulkan 验证。
- 原生侧统一读取 blob `out0_raw`；C++ 后处理兼容 raw (64+26) 和 decoded (4+26) 两种 proposal
- 当前 mini 模型输出 `1x30x8400`（decoded 分支）
- 已验证可运行 GPU 组合：`640` 输入尺寸、`System Vulkan`、`Balanced FP16`、`lightmode=true`、`local_pool_allocator=true`

## 附录 B：推理调试关键日志

- `detect preprocess target=640`
- `detect padded ... anchors=8400`
- `detect ex.extract done blob=out0_raw`

GPU 稳定性排查顺序：
1. 检查 `TARGET_INPUT_SIZE=640`
2. 检查 `GPU_PROFILE=Balanced FP16`
3. 检查 `lightmode/local_pool_allocator`
4. 区分是 ncnn 推理失败还是 UI/探针页自身崩溃
