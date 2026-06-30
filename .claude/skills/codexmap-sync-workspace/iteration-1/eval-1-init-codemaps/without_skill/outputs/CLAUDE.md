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

应用同时运行两条推理链路，由 `AutoInferencePipelineDecider` 调度：

1. **本地 NCNN 推理** — YOLOv8 端侧检测，始终运行，作为 fallback
2. **在线 SSE 推理** — 通过 OkHttp SSE 连接远端 `/ai/ar` 等端点，提供更丰富的分析结果

`OnlineHazardDetectionService` 管理在线请求调度，`AiArEventAggregator` 聚合 SSE 事件流，`AiArHazardDetailParser` 解析远端结果。本地结果通过 `LocalHazardResultDeduper` 去重后与在线结果合并展示。

### 全局会话 — InspectionSession

`InspectionSession` 是贯穿整个巡检流程的全局单例，持有：NCNN 引用、共享帧流、模型状态、企业数据。多个 Activity 通过它共享状态，避免重复初始化和数据传递。

### 巡检配置系统

`app/src/main/assets/inspection_config.base.jsonc` 是运行时配置核心，控制：推理参数（后端、GPU profile、输入尺寸）、API 端点、SSE 协议参数、特性开关。通过 `InspectionConfigRepository` 加载，支持标准风味覆盖。

### 统一输入层

`input/` 包提供触控、语音、头部手势的统一抽象（`UnifiedInput`），`HeadMotionStabilityTracker` 跟踪头部稳定性，`AutoSleepStateMachine` 管理自动休眠。

## 模块代码地图（AI 快速索引入口）

收到代码定位任务时，先查此表找到对应模块 README.md，再进入具体文件。完整文件清单、依赖图和接口契约见 [`docs/CODEMAPS.md`](docs/CODEMAPS.md)。

| 模块 | 代码 README | 覆盖范围 |
|------|-------------|----------|
| 隐患识别/推理 | `app/src/.../hiddenrisk/README.md` | 巡检页面、在线/本地推理、自动链路、隐患上传、设备指引、拍照录入 |
| 相机/帧流 | `app/src/.../camera/README.md` | 相机管理、帧捕获、预览、恢复控制 |
| 统一输入 | `app/src/.../input/README.md` | 触控、语音、头部动作映射、自动休眠 |
| 巡检工作流 | `app/src/.../workflow/README.md` | 跨页面业务上下文、企业信息、QR 解析 |
| UI 组件 | `app/src/.../component/README.md` | 状态栏、取景器、菜单、弹窗、提示 |
| 配置系统 | `app/src/.../config/README.md` | 运行时配置、推理参数、API 端点、特性开关 |

## AI 代码定位工具

本项目配置了两套代码智能工具，根据任务特征选择使用：

### Serena（LSP）

基于 Language Server Protocol 的实时语义分析工具。

**适用场景**：
- `get_symbols_overview` — 初次接触文件时快速获取类/方法/变量列表
- `find_declaration` — 精确定位符号定义
- `find_referencing_symbols` — 查找符号的所有引用（带上下文片段）
- `find_implementations` — 查找接口/抽象方法的实现
- `rename_symbol` — 安全的跨文件重命名

**特点**：实时、精确、基于编译器语义，适合精确的符号操作。

### CodeGraph（预索引知识图谱）

基于 Tree-sitter + SQLite 预计算的全库知识图谱，已初始化（`.codegraph/codegraph.db`）。

**适用场景**：
- `codegraph_explore` — 大范围代码探索，返回入口点 + 相关符号 + 代码片段
- `codegraph_context` — 获取符号的完整上下文（调用链、依赖关系）
- `codegraph_callers` — 查找调用者（预计算的调用图）
- `codegraph_impact` — 影响分析（修改某符号会影响哪些代码）
- `codegraph_search` — 基于 FTS5 的全文符号搜索

**特点**：预计算、批量返回跨文件上下文、减少工具调用次数。适合理解模块间关系和调用链。

### 工具选择策略

| 任务类型 | 首选工具 | 原因 |
|----------|----------|------|
| 初次接触陌生模块 | CodeGraph | 一次调用返回入口点 + 相关符号 + 片段 |
| 获取文件结构概览 | Serena | `get_symbols_overview` 精确列出所有符号 |
| 查找函数/类定义 | Serena | LSP 精确跳转，支持重载辨析 |
| 查找所有引用 | Serena | 语义级引用，过滤字符串同名噪声 |
| 跨模块调用链分析 | CodeGraph | 预计算调用图，减少多次往返 |
| 影响半径评估 | CodeGraph | `codegraph_impact` 一键返回影响范围 |
| 批量文本搜索 | Grep | 简单直接，无需语义分析 |
| 重命名符号 | Serena | LSP 提供安全的跨文件重构 |

### 维护说明

- **Serena**：无需额外维护，随开发环境自动工作
- **CodeGraph**：索引文件位于 `.codegraph/`（已加入 `.gitignore`，不提交）。文件变更后自动同步，如索引损坏或需要强制重建：
  ```bash
  npx codegraph index
  ```

## 跨模块文档索引

| 文档 | 路径 | 内容 |
|------|------|------|
| 架构总览 | `docs/公共能力/架构总览.md` | 五层架构（页面/会话/输入/相机/识别链路）|
| 总体旅程图 | `docs/总体旅程图/总体旅程图.md` | 正式巡检主链全景 |
| 页面导航分层 | `docs/公共能力/页面导航分层.md` | 正式主链与附录/调试页边界 |
| 会话与生命周期 | `docs/公共能力/会话与生命周期.md` | 会话、初始化状态边界 |
| 隐患识别链路 | `docs/公共能力/隐患识别链路.md` | 双轨推理的跨文档真相源 |
| 隐患识别验证与排障 | `docs/公共能力/隐患识别验证与排障.md` | 推理验证与排障详细文档 |
| 统一输入设计与接入 | `docs/公共能力/统一输入设计与接入.md` | 统一输入层设计与接入 |
| 头部动作调参与验证 | `docs/公共能力/头部动作调参与验证.md` | 头部动作参数与验证 |
| 日志系统 | `docs/公共能力/日志系统.md` | 日志系统说明 |
| Rokid 新系统相机预览 Surface 输出经验 | `docs/Lessions/rokid_camera_preview_new_system_surface_output.md` | 新 OTA 后 Surface/NV21 预览差异、诊断证据与底部方形裁剪方案 |

### 三层文档体系

| 层级 | 文件 | 定位 |
|------|------|------|
| **L1** | `CLAUDE.md` (本文件) | AI Agent 快速参考：概述、命令、模块索引 |
| **L2** | `docs/CODEMAPS.md` | 结构化代码地图：完整文件清单、依赖图、数据流、接口契约 |
| **L3** | 各模块 `README.md` | 深度模块文档：业务细节、调用链、关键入口 |

## Kotlin 包结构（`com.rokid.glass/`）

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
| `adapter/` | RecyclerView 适配器 |
| `annotation/` | 注解 |
| `enum/` | 枚举 |
| `recycleview/` | RecyclerView 相关 |

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
- 详细模型经验和重导约束见 `AGENTS.md`

## 关键依赖

- Rokid Glass SDK `2.1.9-E`（推荐 OTA `1.17.e002-20260509-150201` 及以上）、NCNN (Vulkan)、OpenCV Mobile 4.13.0
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
- `docs/CODEMAPS.md` — 项目代码地图 (L2)：完整文件清单、依赖图、数据流、接口契约
- `docs/README.md` — 产品文档总导航
- `docs/公共能力/README.md` — 公共能力目录总入口
- `docs/公共能力/架构总览.md` — 页面层、会话层、输入层与识别链路总览
- `docs/公共能力/隐患识别验证与排障.md` — 推理验证与排障详细文档
- `docs/公共能力/隐患识别链路.md` — 隐患识别链路与双轨推理的跨文档真相源
- `docs/公共能力/头部动作调参与验证.md` — 头部动作识别参数与验证
- `docs/公共能力/统一输入设计与接入.md` — 统一输入层设计与接入
- `docs/公共能力/会话与生命周期.md` — 会话、生命周期、初始化状态边界
- `docs/公共能力/页面导航分层.md` — 正式主链与附录/调试页边界
- `docs/公共能力/日志系统.md` — 日志系统说明
- `docs/Lessions/rokid_camera_preview_new_system_surface_output.md` — Rokid 新系统相机预览 Surface 输出经验
- `docs/公共能力/统一输入.md` — 业务接入层统一输入规则
- `docs/功能模块/` — 各功能模块详细规格
- `models/README.md` — 模型导出完整指南
