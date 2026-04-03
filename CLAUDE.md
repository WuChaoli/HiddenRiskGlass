# GlassDemo 项目配置

## 项目概述

- **项目名称**: GlassDemo
- **类型**: Android 应用（Rokid Glass3 智能眼镜）
- **主要语言**: Kotlin + Java
- **UI 框架**: Jetpack Compose + XML Layout 混合
- **SDK**: Rokid Glass3 Open SDK

## 技术栈

### Android 组件
- Jetpack Compose (BOM 2024.x)
- AndroidX Core KTX
- Lifecycle Runtime KTX
- ViewBinding (已启用)
- DataStore

### 第三方库
- Rokid Glass3 Open SDK 2.1.5-E
- Retrofit + OkHttp (网络请求)
- Gson (JSON 解析)
- Glide (图片加载)
- ML Kit Barcode Scanning
- Firebase Firestore

### 测试
- JUnit 4
- Espresso
- Compose UI Test

## Claude Code 自动化配置

### MCP 服务器
- **figma**: Figma 设计集成
- **context7**: 技术文档查询 (Jetpack Compose, AndroidX)
- **github**: GitHub 操作集成

### Skills（用户可调用的命令）
- `/new-layout` - 创建新的 XML 布局文件
- `/rokid-sdk-check` - 检查 Rokid SDK 配置状态

### Subagents
- **android-compose-reviewer**: Compose 代码审查专家

### Hooks
- 保存 `.kt` 文件后自动格式化
- 保存 XML 布局文件后提示

## 代码规范

### 命名规范
- XML 布局文件: `snake_case.xml`
- Kotlin 文件: `PascalCase.kt`
- 资源 ID: `snake_case`

### 注释规范
- 类开头添加中文注释说明用途
- 复杂逻辑添加中文解释

### 架构模式
- MVVM 模式（ViewModel + LiveData/StateFlow）
- Repository 模式
- 依赖注入（手动或 Hilt，视情况）

## 项目结构

```
app/src/main/
├── java/com/rokid/glass/
│   ├── adapter/          # RecyclerView 适配器
│   ├── base/             # 基础 Activity/Fragment
│   ├── bean/             # 数据模型
│   ├── camera/           # 相机相关
│   ├── component/        # 自定义组件
│   ├── data/             # 数据管理
│   ├── hiddenrisk/       # AI 隐患检测模块
│   └── MainActivity.kt   # 主入口
├── res/
│   ├── layout/           # XML 布局 (24 个文件)
│   ├── drawable/         # 图形资源
│   └── values/           # 字符串、颜色等资源
└── jni/                  # NDK/CMake 配置
```

## 开发注意事项

### Rokid Glass3 SDK
- SDK 版本: 2.1.5-E
- 需要排除 slf4j 依赖冲突
- 初始化时需注册 Client

### NDK
- CMake 构建
- 支持灵活页面大小

### 权限
- CAMERA
- RECORD_AUDIO
- INTERNET
- 存储权限

## 常用命令

```bash
# 构建 Debug 版本
./gradlew assembleDebug

# 运行测试
./gradlew test

# 安装到设备
./gradlew installDebug
```
