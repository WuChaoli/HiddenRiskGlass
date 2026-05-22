# Rokid Glass3 SDK 接入基线

## 用途

当任务涉及 Gradle、Maven、打包配置、`GlassSdk` 初始化顺序时，先读这个文件。

## 当前仓库基线

- Maven repository: `https://maven.rokid.com/repository/maven-public/`
- SDK dependency: `com.rokid.security:glass3.open.sdk:2.1.8-E`
- 二维码能力依赖：`com.rokid.security.glass3.qrcode:scanner:1.0.0`
- 打包冲突处理：`pickFirst 'lib/*/libr2aud.so'`

基线来源：

- `app/build.gradle`

## 默认接入顺序

1. 确认或补齐 Rokid Maven 仓库。
2. 确认当前任务目标是否真的需要改动 `glass3.open.sdk` 版本。
3. 复用仓库现有 `pickFirst 'libr2aud.so'` 处理，不要重复堆新的 packaging 片段。
4. 先完成 `GlassSdk.bindSecurityService(...)`。
5. 只在 `onServiceConnected()` 之后执行 `GlassSdk.registerClient(...)`。
6. 服务绑定完成后，再拿 `GlassSdk.getGlass...Service()`。

## 升级前检查

不要因为 changelog 有新条目就直接 bump 版本。先确认：

- 当前 `app/build.gradle` 依赖版本
- 官方 changelog 对应的目标 SDK 版本
- 目标版本推荐的 OTA 基线
- 本仓库是否真的使用了会受影响的 service / listener / callback

如果结论只是“当前版本已满足任务”，就不要改 Gradle。

## 本地对照点

对齐或补接线时，优先对照当前仓库已有模式：

- `app/build.gradle`
- `app/src/main/java/com/rokid/glass/utils/GlassSdkUtils.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/RokidSdkManager.kt`

## 实践建议

- 先复用仓库现有接法，不要新增第二套 SDK 生命周期
- 如果行为异常，先检查 bind/register 顺序，再怀疑具体 service
- 如果任务同时涉及共享预览或统一输入，回主技能再读对应章节，不要只停留在 Gradle 视角
