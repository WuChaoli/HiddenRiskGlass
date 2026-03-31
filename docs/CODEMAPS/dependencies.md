# 项目依赖清单

<!-- Generated: 2026-03-30 | Files scanned: 8 | Token estimate: ~900 -->

## Android 依赖

### Jetpack / AndroidX

| 依赖 | 版本 | 用途 |
|------|------|------|
| `androidx.core:core-ktx` | 1.9.0 | Kotlin 扩展 |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.6.1 | 生命周期管理 |
| `androidx.activity:activity-compose` | 1.8.0 | Compose Activity |
| `androidx.compose:compose-bom` | 2023.08.00 | Compose BOM |
| `androidx.compose.material3:material3` | BOM | Material3 组件 |
| `androidx.constraintlayout:constraintlayout` | 2.2.1 | 约束布局 |
| `androidx.appcompat:appcompat` | 1.7.1 | 向后兼容 |
| `androidx.datastore:datastore-core-android` | 1.1.7 | 数据存储 |

### Google / ML Kit

| 依赖 | 版本 | 用途 |
|------|------|------|
| `com.google.android.material:material` | 1.12.0 | Material Design |
| `com.google.mlkit:barcode-scanning` | 17.2.0 | 条码扫描 |
| `com.google.android.gms:play-services-mlkit-barcode-scanning` | 18.3.1 | ML Kit 服务 |
| `com.google.firebase:firebase-firestore-ktx` | 25.1.4 | Firestore 数据库 |

### 第三方库

| 依赖 | 版本 | 用途 |
|------|------|------|
| `com.rokid.security:glass3.open.sdk` | 2.1.5-E | **Rokid Glass SDK** |
| `com.blankj:utilcodex` | 1.31.0 | 工具类库 |
| `com.google.code.gson:gson` | 2.8.6 | JSON 序列化 |
| `com.github.bumptech.glide:glide` | 4.11.0 | 图片加载 |
| `pub.devrel:easypermissions` | 2.0.1 | 权限管理 |

### 测试依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| `junit:junit` | 4.13.2 | 单元测试 |
| `androidx.test.ext:junit` | 1.1.5 | Android 测试扩展 |
| `androidx.test.espresso:espresso-core` | 3.5.1 | UI 测试 |

## Native 依赖

### NCNN (Android Vulkan)

**路径**: `app/src/main/jni/ncnn-20260113-android-vulkan/`

| 库 | 类型 | 说明 |
|----|------|------|
| `libncnn.a` | 静态库 | NCNN 核心推理库 |
| `libglslang.a` | 静态库 | GLSL 编译器 |
| `libSPIRV.a` | 静态库 | SPIR-V 中间表示 |
| `libGenericCodeGen.a` | 静态库 | glslang 代码生成 |

### OpenCV Mobile

**路径**: `app/src/main/jni/opencv-mobile-4.13.0-android/`

| 组件 | 说明 |
|------|------|
| `opencv_core` | 核心功能 |
| `opencv_imgproc` | 图像处理 |

### 构建配置 (CMakeLists.txt)

```cmake
NCNN_SHARED_LIB=OFF          # 静态链接
NCNN_VULKAN=ON               # 启用 Vulkan GPU
NCNN_SYSTEM_GLSLANG=ON       # 使用系统 glslang
NCNN_BUILD_TOOLS=OFF         # 不构建工具
NCNN_BUILD_EXAMPLES=OFF      # 不构建示例
```

## Python 依赖 (模型导出)

| 依赖 | 版本 | 用途 |
|------|------|------|
| `torch` | >=2.6,<3 | PyTorch 框架 |
| `onnx` | >=1.17,<2 | ONNX 格式 |
| `ultralytics` | >=8.3,<9 | YOLOv8 训练/导出 |

## 仓库配置

```gradle
dependencyResolutionManagement {
    repositories {
        google()  // 过滤: com.android.*, com.google.*, androidx.*
        mavenCentral()
        maven { url 'https://maven.aliyun.com/nexus/content/repositories/releases/' }
        maven { url 'https://maven.aliyun.com/repository/google/' }
        maven { url 'https://maven.aliyun.com/repository/public/' }
        maven { url 'https://maven.rokid.com/repository/maven-public/' }
        maven { url 'https://www.jitpack.io' }
    }
}
```

## 依赖关系图

```
Android App
├── Kotlin/Java
│   ├── Jetpack Compose (UI)
│   ├── Rokid Glass SDK (硬件交互)
│   ├── ML Kit (条码扫描)
│   └── Firebase (数据存储)
│
├── Native (JNI)
│   ├── hiddenriskncnn.so
│   │   ├── NCNN (推理引擎)
│   │   ├── OpenCV (图像处理)
│   │   └── Vulkan (GPU 加速)
│   └── glslang (着色器编译)
│
└── Assets
    └── hiddenrisk.ncnn.* (模型文件)

Model Pipeline (Python)
├── PyTorch 2.6+
├── Ultralytics 8.3+
└── ONNX 1.17+
```
