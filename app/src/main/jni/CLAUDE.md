# jni/ — JNI 原生推理层

## 业务概述

C++ JNI 桥接层，封装 NCNN (Vulkan GPU) 上的 YOLOv8 隐患检测推理。构建产物 `libhiddenriskncnn.so`，通过 `HiddenRiskNcnn.java` (`hiddenrisk/`) 以 `System.loadLibrary("hiddenriskncnn")` 加载。支持 CPU / System Vulkan / Turnip 三种后端，三种 GPU 精度策略。

## 文件索引

| 文件 | 职责 | 关键入口 |
|------|------|----------|
| `CMakeLists.txt` | JNI 构建配置，链接 NCNN + OpenCV Mobile | `add_library(hiddenriskncnn SHARED ...)` |
| `yolov8.h` | `Object` 结构体、`YOLOv8` / `YOLOv8_det` / `YOLOv8_det_hiddenrisk` 类声明 | `YOLOv8::load()`, `YOLOv8::detect()` |
| `yolov8.cpp` | 模型加载、GPU Vulkan 配置、NCNN 选项组装 | `YOLOv8::load(AAssetManager*, ...)` |
| `yolov8_det.cpp` | 检测推理全流程 + 33 类标签 + 16 类隐患白名单 + `draw()` | `YOLOv8_det::detect()`, `label_name()` |
| `yolov8ncnn.cpp` | JNI 桥接入口，所有 native 方法实现，NV21/Bitmap/HardwareBuffer 转换 | `Java_*_loadModel`, `Java_*_submitNv21` |
| `ncnn/` | NCNN 框架上游源码（子目录构建） | 上游通用推理框架 |

## 关键约束

- **NCNN param + bin 必须成对替换**，不允许只换一个
- **JNI 调用只能通过 `HiddenRiskNcnn.java`**，禁止在其他位置声明 native 方法
- **模型文件命名固定**：`hiddenrisk.ncnn.param` + `hiddenrisk.ncnn.bin`，存放在 APK assets
- **输入尺寸**：det_target_size 默认 640，支持 320~1280 动态配置
- **输入格式**：NV21（主通道），JNI 内部 OpenCV `cvtColor` 转 RGB 送入推理
- **NMS**：IoU 阈值 0.45，置信度 0.25，类别不共享 NMS 空间
- **16 类隐患白名单**（`is_filtered_label()` 硬编码）：T_btn、gas_alarm、fire_cabinet、emergency_light、hydrant_nozzle、regulator、hose、nozzle、lpg_cylinder、extinguisher、coal_stove、flameout_protection、gas_range、electric_tricycle、electric_bike、gas_hose
- **线程安全**：全局实例 `ncnn::Mutex` 保护，推理 `atomic<bool>` 防重入
- **NDK**：29.0.14206865

## GPU 后端与精度

| backend | 后端 | gpuProfile | 精度策略 |
|---------|------|------------|----------|
| 0 | CPU | 0 | Safe FP32 |
| 1 | System Vulkan | 1 | Balanced FP16 |
| 2 | Turnip (Adreno) | 2 | No Packing FP32 |

## 依赖关系

- **依赖**：NCNN 预编译库 + 源码子目录、OpenCV Mobile 4.13.0、NDK (`android/jnigraphics/log`)、Vulkan 运行时
- **被依赖**：`hiddenrisk/` 通过 `HiddenRiskNcnn.java` 调用全部接口

## 构建

JNI 由 Gradle CMake 自动构建（`app/build.gradle.kts` 中 `externalNativeBuild` 块）：

```bash
./gradlew assembleDebug
```

模型部署路径：`app/src/main/assets/hiddenrisk.ncnn.param` + `hiddenrisk.ncnn.bin`。
