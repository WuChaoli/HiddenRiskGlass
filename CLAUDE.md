# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Rokid AR 眼镜 Android 应用（"基层应消"），具备 AI 隐患检测功能。通过 NCNN (Vulkan GPU) 运行 YOLOv8 实现端侧推理，同时支持在线 SSE 远端推理作为补充。

- 包名/applicationId：`com.rokid.glesse`
- 技术栈：Kotlin + C++ (JNI)
- 版本：`2.0.3`

## 显示设计基线

眼镜端显示基线：**480 x 640 px**，**240 dpi**（`1dp = 1.5px`）。理论满屏 `320 x 426.7 dp`，常规页面关键内容按 `320 x 402 dp` 设计。

## 构建命令

```bash
./gradlew assembleDebug          # 构建 debug APK
./gradlew assembleRelease        # 构建 release APK
./gradlew clean assembleDebug    # 清理后构建
./gradlew installDebug           # 安装到已连接设备

# 测试
./gradlew test                   # 所有单元测试
./gradlew connectedAndroidTest   # 仪器测试
./gradlew test --tests "com.rokid.glesse.ExampleUnitTest.addition_isCorrect"  # 单个测试方法

# 模型导出（需要 models/ 下的 Python 虚拟环境）
cd models && source .venv/bin/activate  # Windows: .venv/Scripts/activate
bash scripts/export_hiddenrisk_640.sh
bash scripts/validate_hiddenrisk_assets.sh
```

JNI/C++ 由 Gradle 通过 CMake 自动构建（`app/src/main/jni/CMakeLists.txt`），NDK 版本 `29.0.14206865`。

## 架构

### 推理双轨制（核心架构决策）

应用同时运行两条推理链路，由 `AutoInferenceLoopDecider` 调度：

1. **本地 NCNN 推理** — YOLOv8 端侧检测，始终运行，作为 fallback
2. **在线 SSE 推理** — 通过 OkHttp SSE 连接远端 `/ai/ar` 等端点，提供更丰富的分析结果

`OnlineHazardDetectionService` 管理在线请求调度，`AiArEventAggregator` 聚合 SSE 事件流，`AiArHazardDetailParser` 解析远端结果。本地结果通过 `LocalHazardResultDeduper` 去重后与在线结果合并展示。

### UI 层 — Jetpack Compose + XML Layout

- `InspectionLoadingActivity` — **启动入口 (Launcher)**，SDK 初始化、权限、相机预热
- `AiInspectionActivity` — 核心 AI 巡检页面，自动检测 + 结果展示
- `HiddenRiskProbeActivity` — NCNN 探针/调试页（仅用于验证推理，非正式功能）
- `HomeActivity` — 导航菜单
- `DeviceGuideActivity` — 设备指引
- `HazardRecordActivity` — 隐患录入（拍照 + 保存）
- `InspectionEndReportActivity` — 结束巡检报告

### AI/ML 层 — NCNN YOLOv8

- C++ 代码：`yolov8ncnn.cpp`（JNI 桥接）、`yolov8.cpp`（模型加载/GPU 配置）、`yolov8_det.cpp`（检测 + DFL 解码 + NMS 后处理）
- 模型资产：`app/src/main/assets/hiddenrisk.ncnn.param` + `hiddenrisk.ncnn.bin`
- 16 类隐患白名单（燃气灶、灭火器、消火栓、电动车等）
- 已验证可运行的 GPU 组合：`640` 输入尺寸、`System Vulkan`、`Balanced FP16`、`lightmode=true`、`local_pool_allocator=true`

### 相机层

Camera2 API，通过 `QuickCameraManager` 实现 GPU 帧捕获（HardwareBuffer），`RokidCameraManager` 提供统一管理，`RokidCameraRecoveryController` 处理相机异常恢复。

### 全局会话 — InspectionSession

`InspectionSession` 是贯穿整个巡检流程的全局单例，持有：NCNN 引用、共享帧流、模型状态、企业数据。多个 Activity 通过它共享状态，避免重复初始化和数据传递。

### 巡检配置系统

`app/src/main/assets/inspection_config.base.jsonc` 是运行时配置核心，控制：推理参数（后端、GPU profile、输入尺寸）、API 端点、SSE 协议参数、特性开关。通过 `InspectionConfigRepository` 加载，支持标准风味覆盖。

### 统一输入层

`input/` 包提供触控、语音、头部手势的统一抽象（`UnifiedInput`），`HeadMotionStabilityTracker` 跟踪头部稳定性，`AutoSleepStateMachine` 管理自动休眠。

### Kotlin 包结构（`com.rokid.glass/`）

| 包 | 职责 |
|---|---|
| `hiddenrisk/` | NCNN 推理、巡检流程、在线检测、结果处理、上传 (~50 文件) |
| `camera/` | 相机管理、帧捕获、恢复控制 |
| `input/` | 统一输入、头部动作、自动休眠 |
| `component/` | UI 组件（状态栏、取景器、菜单） |
| `workflow/` | 巡检工作流 session |
| `config/` | 运行时配置加载 |
| `base/` | Activity 基类 |
| `bean/` | 数据模型 |
| `utils/` | 工具类与扩展函数 |
| `data/` | 全局状态/事件 |

## 代码风格

- 代码/文件/目录命名：English；注释和文档：简体中文
- Kotlin 风格：`official`（`kotlin.code.style=official`），JVM 目标 1.8
- 类名：PascalCase，函数/变量：camelCase，常量：UPPER_SNAKE_CASE
- C++ 文件：snake_case；新代码优先使用 Kotlin，Java 仅用于 JNI 接口层和旧代码
- 优先使用 `val` 而非 `var`；纯数据载体使用 `data class`

## NCNN 模型流水线

- 当前部署源：`models/source/hidden_risk_mini_0330.onnx`
- 完整训练资产：`models/source/best.pt`
- 导出链路：`.pt -> torchscript(imgsz=640) -> pnnx(fp16=1) -> ncnn` 或 `.onnx -> pnnx(fp16=1) -> ncnn`
- 原生侧统一读取 blob `out0_raw`；C++ 后处理兼容 raw (64+26) 和 decoded (4+26) 两种 proposal
- 当前 mini 模型输出 `1x30x8400`（decoded 分支）
- 正式资产必须使用同一次导出生成的 `param + bin` 成对替换

## 关键依赖

- Rokid Glass SDK `2.1.7-E`、NCNN (Vulkan)、OpenCV Mobile 4.13.0
- ML Kit（条码扫描）、Jetpack Compose、Glide、Gson
- OkHttp 4.12.0（HTTP + SSE）

## 调试

验证推理是否正常运行的关键日志：
- `detect preprocess target=640`
- `detect padded ... anchors=8400`
- `detect ex.extract done blob=out0_raw`

GPU 稳定性排查顺序：检查 `TARGET_INPUT_SIZE=640` → `GPU_PROFILE=Balanced FP16` → `lightmode/local_pool_allocator` → 区分是 ncnn 推理失败还是 UI/探针页自身崩溃。

## 文档导航

- `AGENTS.md` — AI Agent 补充行为指南与 NCNN 经验细节
- `docs/README.md` — 产品文档总导航
- `docs/公共能力/隐患识别验证与排障.md` — 推理验证与排障详细文档
- `docs/公共能力/架构总览.md` — 页面层、会话层、输入层与识别链路总览
- `docs/公共能力/隐患识别链路.md` — 隐患识别链路与双轨推理的跨文档真相源
- `docs/公共能力/头部动作调参与验证.md` — 头部动作识别参数与验证
- `docs/公共能力/统一输入设计与接入.md` — 统一输入层设计与接入
- `docs/功能模块/` — 各功能模块详细规格
- `models/README.md` — 模型导出完整指南
