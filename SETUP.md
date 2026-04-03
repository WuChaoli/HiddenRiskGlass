# 项目环境搭建指南

本文档指导新开发者如何配置环境并成功编译本项目。

---

## 1. 环境要求

| 组件 | 版本要求 | 说明 |
|------|---------|------|
| Android Studio | Hedgehog (2023.1.1) 或更新 | 推荐最新稳定版 |
| JDK | 17 | AGP 8.4+ 要求 |
| Gradle | 8.6 | 项目已内置 Gradle Wrapper |
| Android SDK | compileSdk 34 | 需安装 SDK 34 |
| Android NDK | 29.0.14206865 | Gradle 会自动下载 |
| CMake | 3.22.1+ | Android Studio SDK Manager 中安装 |
| Git | 2.30+ | 用于克隆仓库 |
| 操作系统 | Windows 10/11, macOS 12+, Ubuntu 20.04+ | |

---

## 2. 快速开始

### 2.1 克隆仓库

```bash
git clone <repository-url>
cd glassdemo
```

### 2.2 运行依赖安装脚本

项目依赖 ncnn 和 OpenCV 预编译库，这些文件体积较大未提交到 Git。请先运行安装脚本：

**Windows (PowerShell):**
```powershell
.\scripts\setup_dependencies.ps1
```

**macOS / Linux:**
```bash
chmod +x scripts/setup_dependencies.sh
./scripts/setup_dependencies.sh
```

脚本会自动下载以下依赖到正确位置：
- `ncnn-20260113-android-vulkan` → `app/src/main/jni/`
- `opencv-mobile-4.13.0-android` → `app/src/main/jni/`

> **注意**: 如果脚本下载失败，请参考下方 [手动下载依赖](#4-手动下载依赖) 章节。

### 2.3 配置 Firebase（可选）

如果需要使用 Firebase Firestore 功能，需要添加 `google-services.json` 配置文件：

1. 从 Firebase 控制台下载项目的 `google-services.json`
2. 将其放置到 `app/` 目录下：
   ```
   app/google-services.json
   ```
3. 如果不需要 Firebase，可以跳过此步骤，但构建时会有警告

> 该文件包含敏感信息，**不要**提交到 Git 仓库。

### 2.4 编译项目

**使用 Android Studio:**
1. 打开项目根目录
2. 等待 Gradle 同步完成
3. 点击 Run 按钮或 `Shift + F10`

**使用命令行:**
```bash
# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK
./gradlew assembleRelease

# 安装到连接的设备
./gradlew installDebug
```

---

## 3. 第三方依赖说明

### 3.1 Rokid Glass SDK

项目使用 Rokid Glass3 SDK（版本 `2.1.5-E`），通过 Rokid 私有 Maven 仓库分发。

**仓库地址:** `https://maven.rokid.com/repository/maven-public/`

**访问状态:** 该仓库为公开仓库，通常可直接访问。如果下载失败：
1. 检查网络是否能访问 `maven.rokid.com`
2. 联系 Rokid 技术支持确认仓库访问权限
3. 如有需要，可替换为本地 aar 文件（联系项目负责人获取）

### 3.2 ncnn 推理框架

- **版本:** 20260113 (Vulkan 后端)
- **来源:** [ncnn GitHub Releases](https://github.com/Tencent/ncnn/releases)
- **用途:** NCNN 模型推理（HiddenRisk 检测）
- **安装:** 通过 `setup_dependencies` 脚本自动下载

### 3.3 OpenCV Mobile

- **版本:** 4.13.0
- **来源:** [OpenCV Mobile Releases](https://github.com/nihui/opencv-mobile/releases)
- **用途:** 图像预处理
- **安装:** 通过 `setup_dependencies` 脚本自动下载

### 3.4 其他开源依赖

以下依赖均通过 Maven Central / Google Maven 自动下载，无需额外配置：

| 依赖 | 版本 | 用途 |
|------|------|------|
| OkHttp | 4.12.0 | HTTP 客户端 |
| Retrofit | 2.9.0 | REST 客户端 |
| Gson | 2.8.6 | JSON 序列化 |
| Glide | 4.11.0 | 图片加载 |
| utilcodex | 1.31.0 | 工具类库 |
| easypermissions | 2.0.1 | 权限管理 |
| ML Kit Barcode | 17.2.0 | 条码扫描 |

---

## 4. 手动下载依赖

如果自动脚本失败，可以手动下载：

### 4.1 下载 ncnn

1. 访问 [ncnn Releases](https://github.com/Tencent/ncnn/releases)
2. 下载 `ncnn-20260113-android-vulkan.zip`
3. 解压到 `app/src/main/jni/` 目录：
   ```
   app/src/main/jni/ncnn-20260113-android-vulkan/
   ```

### 4.2 下载 OpenCV Mobile

1. 访问 [OpenCV Mobile Releases](https://github.com/nihui/opencv-mobile/releases)
2. 下载 `opencv-mobile-4.13.0-android.zip`
3. 解压到 `app/src/main/jni/` 目录：
   ```
   app/src/main/jni/opencv-mobile-4.13.0-android/
   ```

### 4.3 验证依赖

下载完成后，确认以下路径存在：
```
app/src/main/jni/
├── ncnn-20260113-android-vulkan/
│   ├── arm64-v8a/
│   ├── armeabi-v7a/
│   └── ...
└── opencv-mobile-4.13.0-android/
    └── sdk/
```

---

## 5. 常见问题

### 5.1 Gradle 同步失败：无法下载 Rokid SDK

**错误信息:**
```
Could not resolve com.rokid.security:glass3.open.sdk:2.1.5-E
```

**解决方案:**
1. 检查网络是否能访问 `https://maven.rokid.com/repository/maven-public/`
2. 尝试在浏览器中打开该 URL 确认连通性
3. 如果在国内网络环境下访问 Rokid Maven 有问题，可尝试切换网络或使用代理

### 5.2 CMake 构建失败：找不到 ncnn/OpenCV

**错误信息:**
```
CMake Error: The source directory "xxx/ncnn-20260113-android-vulkan" does not exist
```

**解决方案:**
1. 确认已运行 `setup_dependencies` 脚本
2. 检查 `app/src/main/jni/` 目录下是否存在 ncnn 和 opencv 文件夹
3. 如果文件夹名称版本不匹配，请重新下载正确版本

### 5.3 NDK 下载失败

**解决方案:**
1. 打开 Android Studio → SDK Manager → SDK Tools
2. 勾选 "NDK (Side by side)" 并安装版本 `29.0.14206865`
3. 或者确保 Android Studio 有网络权限，Gradle 会自动下载

### 5.4 Firebase 初始化警告

如果未配置 `google-services.json`，运行时可能会看到 Firebase 相关警告。这不影响核心功能（相机、识别等），仅影响 Firestore 数据同步功能。

### 5.5 构建速度慢

- 首次构建需要下载所有依赖，请耐心等待
- 可以配置 Gradle 国内镜像加速（项目已配置阿里云镜像）
- 使用 `--parallel` 和 `--daemon` 参数加速构建：
  ```bash
  ./gradlew assembleDebug --parallel --daemon
  ```

---

## 6. 项目结构概览

```
glassdemo/
├── app/
│   ├── src/main/
│   │   ├── java/com/rokid/glass/   # Kotlin 源码
│   │   ├── jni/                    # C++ JNI 代码
│   │   │   ├── CMakeLists.txt
│   │   │   ├── ncnn-20260113-android-vulkan/  # (需下载)
│   │   │   └── opencv-mobile-4.13.0-android/  # (需下载)
│   │   └── assets/                 # 模型文件
│   └── build.gradle
├── scripts/
│   ├── setup_dependencies.sh       # 依赖安装脚本 (macOS/Linux)
│   └── setup_dependencies.ps1      # 依赖安装脚本 (Windows)
├── models/                         # 模型训练和导出脚本
├── gradle/libs.versions.toml       # 依赖版本管理
└── SETUP.md                        # 本文档
```

---

## 7. 更新日志

| 日期 | 变更 |
|------|------|
| 2026-04-03 | 初始版本，添加依赖安装脚本和搭建文档 |
