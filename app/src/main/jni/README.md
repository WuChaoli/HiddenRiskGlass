# jni/ — JNI 原生推理层

## 业务概述

C++ JNI 桥接层，封装 NCNN (Vulkan GPU) 上的 YOLOv8 隐患检测推理。构建产物为 `libhiddenriskncnn.so`，通过 `HiddenRiskNcnn.java` (`hiddenrisk/`) 以 `System.loadLibrary("hiddenriskncnn")` 加载，供 Kotlin 层进行本地端侧推理。支持 CPU / System Vulkan / Turnip 三种后端，三种 GPU 精度策略（Safe FP32、Balanced FP16、No Packing FP32）。

## 文件索引

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `CMakeLists.txt` | JNI 构建配置，链接 NCNN + OpenCV Mobile | `add_library(hiddenriskncnn SHARED ...)` |
| `yolov8.h` | 数据结构定义 (`Object`)、`YOLOv8` / `YOLOv8_det` / `YOLOv8_det_hiddenrisk` 类声明 | `YOLOv8::load()`, `YOLOv8::detect()` |
| `yolov8.cpp` | 模型加载、GPU Vulkan 配置、NCNN 选项组装 | `YOLOv8::load(AAssetManager*, ...)` |
| `yolov8_det.cpp` | 检测推理流程（预处理、推理、DFL 解码、NMS 后处理）、33 类标签名、16 类隐患白名单、`draw()` 可视化 | `YOLOv8_det::detect()`, `YOLOv8_det_hiddenrisk::label_name()` |
| `yolov8ncnn.cpp` | JNI 桥接入口，所有 `Java_com_rokid_glass_hiddenrisk_HiddenRiskNcnn_*` 函数，NV21/Bitmap/HardwareBuffer 转换，诊断日志 | `Java_*_loadModel`, `Java_*_submitNv21`, `run_detection_on_rgb()` |
| `ncnn/` | NCNN 框架上游源码（作为子目录构建，不另做详细介绍） | 上游通用推理框架 |

## 核心调用链

```
Kotlin: HiddenRiskNcnn.detect(frame)
  └─ JNI: Java_com_rokid_glass_hiddenrisk_HiddenRiskNcnn_submitNv21()
       └─ run_detection_on_rgb(rgb)
            ├─ g_yolov8->detect(rgb, objects)     // YOLOv8_det::detect()
            │    ├─ submit NCNN Extractor (Vulkan/CPU)
            │    ├─ 读取输出 blob "out0"
            │    ├─ generate_proposals_decoded() / generate_proposals()
            │    │    └─ is_filtered_label() 白名单过滤
            │    └─ qsort_descent_inplace() + nms_sorted_bboxes()
            └─ 更新全局状态 (g_latest_objects, g_latest_inference_time_ms, ...)

Kotlin: HiddenRiskNcnn.getLatestInferenceStats()
  └─ JNI: Java_*_getLatestInferenceStats()
       └─ 从全局状态读取最新推理结果 + 性能指标，返回 NativeInferenceStats 对象
```

## JNI 公开接口一览

| JNI 函数 | 对应 Java 方法签名 | 功能 |
|----------|-------------------|------|
| `loadModel` | `native boolean loadModel(AssetManager, int backend, int gpuProfile, int targetSize)` | 从 APK assets 加载 `hiddenrisk.ncnn.param` + `hiddenrisk.ncnn.bin`，配置后端和精度策略 |
| `submitNv21` | `native boolean submitNv21(byte[] nv21, int width, int height)` | 提交 NV21 帧进行推理（主要通道） |
| `submitBitmap` | `native boolean submitBitmap(Bitmap bitmap)` | 提交 Bitmap 进行推理 |
| `submitHardwareBuffer` | `native boolean submitHardwareBuffer(HardwareBuffer hb, int w, int h, int rotation)` | 零拷贝提交 HardwareBuffer（要求 API 26+） |
| `getLatestInferenceStats` | `native NativeInferenceStats getLatestInferenceStats()` | 获取最新推理结果、耗时、后端信息、诊断信息 |
| `clearFrameState` | `native void clearFrameState()` | 清除当前帧的检测状态 |
| `setDebugResultLimit` | `native void setDebugResultLimit(int limit)` | 设置调试模式下的检测结果数量上限 |
| `setDebugCompareEnabled` | `native void setDebugCompareEnabled(boolean enabled)` | 启用/禁用调试对比模式 |

## 三类子输入通道

| 输入通道 | JNI 函数 | 转换方式 | 适用场景 |
|----------|----------|----------|----------|
| NV21（主通道） | `submitNv21` | CPU 侧 NV21 -> BGR -> RGB (OpenCV) | 相机预览帧常规推理 |
| Bitmap | `submitBitmap` | `AndroidBitmap_lockPixels` 直接读取 RGB | 拍照/截图推理 |
| HardwareBuffer | `submitHardwareBuffer` | Android HardwareBuffer API 零拷贝读取（API 26+） | 低延迟高性能场景 |

## 关键约束

- **NCNN param + bin 必须成对替换**，不允许只换一个文件
- **JNI 调用只能通过 `HiddenRiskNcnn.java`**，禁止在其他位置声明 native 方法
- **模型文件命名固定**：`hiddenrisk.ncnn.param` + `hiddenrisk.ncnn.bin`，存放在 APK assets 目录
- **输入尺寸**：det_target_size 默认 640，支持 320~1280 范围内动态配置
- **输入格式**：NV21（YUV 4:2:0 半平面），JNI 内部通过 OpenCV `cvtColor` 转为 RGB 送入推理
- **检测后处理**：NMS IoU 阈值 0.45（类别不共享 NMS 空间），置信度阈值 0.25
- **16 类隐患白名单**（`is_filtered_label()` 硬编码）：T_btn、gas_alarm、fire_cabinet、emergency_light、hydrant_nozzle、regulator、hose、nozzle、lpg_cylinder、extinguisher、coal_stove、flameout_protection、gas_range、electric_tricycle、electric_bike、gas_hose
- **模型输出 33 类**（YOLOv11），白名单过滤后仅保留 16 类隐患标签
- **线程安全**：全局 YOLOv8 实例通过 `ncnn::Mutex` 保护，推理过程使用 `atomic<bool>` 防止重入
- **NDK 版本**：29.0.14206865

## GPU 后端与精度策略

| backend 值 | 后端 | 说明 |
|------------|------|------|
| 0 | CPU | 纯 CPU 推理，无需 Vulkan 驱动 |
| 1 | System Vulkan | 系统自带 Vulkan 驱动 |
| 2 | Turnip | 开源 Turnip Vulkan 驱动（高通 Adreno） |

| gpuProfile 值 | 精度策略 | 说明 |
|---------------|----------|------|
| 0 | Safe FP32 | 保守模式，排除 FP16 触发的驱动问题 |
| 1 | Balanced FP16 | 平衡模式，FP16 打包/存储/算术以省显存 |
| 2 | No Packing FP32 | 关闭 packing_layout，兼容性优先 |

## 依赖关系

- **依赖：**
  - NCNN 预编译库 (`ncnn-20260113-android-vulkan`) + NCNN 源码 (`ncnn/`) 作为子目录构建
  - OpenCV Mobile 4.13.0 (`opencv-mobile-4.13.0-android/sdk/native/jni`)
  - Android NDK 系统库：`android`、`jnigraphics`、`log`
  - Vulkan 运行时（GPU 后端需要）
- **被依赖：**
  - `hiddenrisk/` 模块 — 通过 `HiddenRiskNcnn.java` 调用全部 JNI 接口
  - `camera/` 模块 — 提供 NV21 相机帧流作为推理输入

## 构建

JNI 由 Gradle 通过 CMake 自动构建，无需手动执行 cmake 命令。CMake 配置文件位于 `app/src/main/jni/CMakeLists.txt`，在 `app/build.gradle.kts` 的 `externalNativeBuild` 块中引用。

```bash
./gradlew assembleDebug          # 自动编译 JNI + Kotlin
```

模型文件部署路径：`app/src/main/assets/hiddenrisk.ncnn.param` 和 `app/src/main/assets/hiddenrisk.ncnn.bin`（运行时通过 AssetManager 加载）。
