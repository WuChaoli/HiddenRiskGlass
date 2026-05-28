# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Rokid AR 眼镜 Android 应用（"基层应消"），具备 AI 隐患检测功能。通过 NCNN (Vulkan GPU) 运行 YOLOv8 实现端侧推理，同时支持在线 SSE 远端推理作为补充（双轨推理架构）。

- 包名/applicationId：`com.rokid.glesse`
- 技术栈：Kotlin + C++ (JNI)
- 版本：`2.0.3`

## 显示设计基线

眼镜端显示基线：**480 x 640 px**，**240 dpi**（`1dp = 1.5px`）。理论满屏 `320 x 426.7 dp`，常规页面关键内容按 `320 x 402 dp` 设计。

## Android 构建与真机调试

```bash
bash scripts/android/doctor.sh              # 所有构建/设备操作前先做环境检查
bash scripts/android/doctor.sh --device     # 同时确认 Windows ADB 设备通路
bash scripts/android/build-debug.sh         # 构建 standardDebug APK
bash scripts/android/install-debug.sh -s <serial>
bash scripts/android/package-release.sh    # 正式配置不足时只生成 debug 签名演示包
bash scripts/android/verify-apk.sh <apk>    # 输出版本和证书摘要

# 测试
./gradlew :app:testStandardDebugUnitTest   # standard 变体单元测试
./gradlew connectedAndroidTest   # 仪器测试
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glesse.ExampleUnitTest.addition_isCorrect"

# 模型导出（需要 models/ 下的 Python 虚拟环境）
cd models && source .venv/bin/activate  # Windows: .venv/Scripts/activate
bash scripts/export_hiddenrisk_640.sh
bash scripts/validate_hiddenrisk_assets.sh
```

默认业务变体为 `standard`。WSL 编译使用根目录本地 `.env` 中的 JDK/SDK，Rokid Glass 真机操作使用 Windows `adb.exe`；命令、签名规则和踩坑记录见 `scripts/android/README.md`。JNI/C++ 由 Gradle 通过 CMake 自动构建（`app/src/main/jni/CMakeLists.txt`），NDK 版本 `29.0.14206865`。

## 架构

五层架构：页面层 -> 会话层 -> 输入层 -> 相机层 -> 识别链路层。跨模块关系、依赖矩阵、端到端数据流见 **`docs/CODEMAPS.md`**（L2 内存文档）。

核心架构决策：**双轨推理** -- 本地 NCNN (YOLOv8 Vulkan) 始终运行作为 fallback，在线 SSE 推理提供更丰富的分析结果，由 `AutoHazardPipelineDecider` 链路调度。

## 模块代码地图

收到代码定位任务时，先查此表找到对应模块 README.md，再进入具体文件。

| 模块 | 包/路径 | README | 覆盖范围 |
|------|---------|--------|----------|
| 隐患识别/推理 | `hiddenrisk/` | [README](app/src/main/java/com/rokid/glass/hiddenrisk/README.md) | 巡检页面、在线/本地推理、自动链路、隐患上传、设备指引、拍照录入 (~50文件) |
| 相机/帧流 | `camera/` | [README](app/src/main/java/com/rokid/glass/camera/README.md) | 相机管理、帧捕获、预览、恢复控制 |
| 统一输入 | `input/` | [README](app/src/main/java/com/rokid/glass/input/README.md) | 触控、语音、头部动作映射、自动休眠 |
| 巡检工作流 | `workflow/` | [README](app/src/main/java/com/rokid/glass/workflow/README.md) | 跨页面业务上下文、企业信息、QR 解析 |
| UI 组件 | `component/` | [README](app/src/main/java/com/rokid/glass/component/README.md) | 状态栏、取景器、菜单、弹窗、提示 |
| 配置系统 | `config/` | [README](app/src/main/java/com/rokid/glass/config/README.md) | 运行时配置、推理参数、API 端点、特性开关 |
| 原生推理 (JNI) | `jni/` | [README](app/src/main/jni/README.md) | C++ JNI桥接、YOLOv8推理、NCNN Vulkan |
| 网络 | `network/` | [README](app/src/main/java/com/rokid/glass/network/README.md) | OkHttp 单例提供 |
| 应用更新 | `updater/` | [README](app/src/main/java/com/rokid/glass/updater/README.md) | App 版本检查、下载、升级提示 |
| 工具库 | `utils/` | [README](app/src/main/java/com/rokid/glass/utils/README.md) | 日志、Bitmap、SSE、TTS、系统状态查询等 |

## 代码风格

- 代码/文件/目录命名：English；注释和文档：简体中文
- Kotlin 风格：`official`（`kotlin.code.style=official`），JVM 目标 1.8
- 类名：PascalCase，函数/变量：camelCase，常量：UPPER_SNAKE_CASE
- C++ 文件：snake_case；新代码优先使用 Kotlin，Java 仅用于 JNI 接口层和旧代码
- 优先使用 `val` 而非 `var`；纯数据载体使用 `data class`

## 关键依赖

- Rokid Glass SDK `2.1.9-E`（推荐 OTA `1.17.e002-20260509-150201` 及以上）
- NCNN (Vulkan GPU 推理)、OpenCV Mobile 4.13.0
- ML Kit（条码扫描）、Jetpack Compose、Glide、Gson
- OkHttp 4.12.0（HTTP + SSE）

## 架构不变量（全局规则）

以下规则在整个代码库中不可违反：

1. **NCNN param + bin 必须成对替换**，不允许只换一个
2. **JNI 调用只能通过 `HiddenRiskNcnn.java`**，禁止在其他位置声明 native 方法
3. **配置只从 `InspectionConfigRepository` 读取**，禁止硬编码推理参数/API 端点
4. **相机帧流只通过 `InspectionSession` 获取**，Activity 不直接持有 Camera 引用

## 文档导航

| 文档 | 路径 | 说明 |
|------|------|------|
| 模块关系地图 (L2) | `docs/CODEMAPS.md` | 架构层级图、依赖矩阵、数据流、边界规则、术语表、任务速查 |
| AI Agent 补充指南 | `AGENTS.md` | NCNN 经验细节与行为补充 |
| Android 构建与真机调试 | `scripts/android/README.md` | JDK/SDK/ADB 环境、构建、打包签名与排障入口 |
| 各模块 README | 见上方"模块代码地图"表 | 模块内部文件索引与核心调用链 |

---

**三层文档体系**: `CLAUDE.md` (L1 缓存，本文档) -> `docs/CODEMAPS.md` (L2 内存) -> 各模块 `README.md` (L3 硬盘)
