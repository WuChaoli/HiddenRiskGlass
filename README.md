# GlassDemo - Rokid Glass 应用

> Rokid Glass3 智能眼镜应用，包含相机、人脸识别、车牌识别、HiddenRisk NCNN 推理等功能。

---

## ⚠️ 首次编译前必做

本项目依赖 **ncnn** 和 **OpenCV** 预编译库（体积较大，未提交到仓库）。

**首次编译前，请先运行依赖安装脚本：**

```powershell
# Windows PowerShell
.\scripts\setup_dependencies.ps1
```

```bash
# macOS / Linux
chmod +x scripts/setup_dependencies.sh
./scripts/setup_dependencies.sh
```

> 如果脚本下载失败，请查看 [SETUP.md](SETUP.md) 中的手动下载说明。

---

## 快速开始

### 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| Android Studio | Hedgehog (2023.1.1)+ | 推荐最新稳定版 |
| JDK | 17 | AGP 8.4+ 要求 |
| Android SDK | 34 | compileSdk 版本 |
| Android NDK | 29.0.14206865 | Gradle 会自动下载 |

### 编译运行

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK
./gradlew assembleRelease

# 安装到连接的设备
./gradlew installDebug
```

---

## 功能

- 相机预览与拍照
- 人脸识别
- 车牌识别
- HiddenRisk NCNN 推理（Vulkan 后端）
- 条码扫描（ML Kit）
- Firebase Firestore 数据同步

## 项目结构

```
glassdemo/
├── app/src/main/
│   ├── java/com/rokid/glass/   # Kotlin 源码
│   ├── jni/                    # C++ JNI 代码
│   │   ├── CMakeLists.txt
│   │   ├── ncnn-*/             # (需下载，见上方)
│   │   └── opencv-*/           # (需下载，见上方)
│   └── assets/                 # 模型文件
├── scripts/
│   ├── setup_dependencies.sh   # 依赖安装脚本 (macOS/Linux)
│   └── setup_dependencies.ps1  # 依赖安装脚本 (Windows)
├── models/                     # 模型训练和导出脚本
└── docs/                       # 技术文档
```

## 详细文档

- [SETUP.md](SETUP.md) — 完整环境搭建指南（依赖说明、常见问题）
- [AGENTS.md](AGENTS.md) — 开发规范与 HiddenRisk 经验
- [docs/](docs/) — 技术文档

## 依赖说明

| 依赖 | 来源 | 说明 |
|------|------|------|
| ncnn | [Tencent/ncnn](https://github.com/Tencent/ncnn/releases) | NCNN 推理框架（Vulkan 后端） |
| OpenCV Mobile | [nihui/opencv-mobile](https://github.com/nihui/opencv-mobile/releases) | 图像预处理 |
| Rokid Glass SDK | `maven.rokid.com` | 眼镜端 SDK（私有 Maven） |

详细依赖列表和版本信息请查看 [SETUP.md](SETUP.md)。
