---
name: rokid-glass3-sdk
description: 审计和维护本仓库的 Rokid Glass3 SDK 集成、SDK/OTA 兼容矩阵、共享相机预览、设备事件、统一输入及眼镜端系统服务。用户询问 Rokid SDK 版本、升级影响、新增接口、CameraShareHelper、GlassSdk、IDeviceService、OTA 兼容性或相关真机故障时使用。
---

# Rokid Glass3 SDK

先读取仓库代码和配置，再判断 SDK 能力或兼容性，不要根据旧文档或记忆直接下结论。

## 工作流程

1. 读取 `app/build.gradle`，确认仓库当前 SDK 依赖。
2. 涉及版本、新接口、OTA 或兼容性时，读取 [版本兼容参考](references/version-compatibility.md)。
3. 搜索实际调用点，区分“官方 SDK 已提供”与“当前仓库已采用”。
4. 涉及设备行为时，核对实机 OTA、运行日志和前台页面状态。
5. 只有用户明确要求升级时才修改 SDK 依赖；不要因官方出现新版本自动升级。

## 约束

- 以代码现状作为仓库行为真相，以官方 changelog 作为 SDK 能力真相。
- 分开陈述仓库当前版本、官方最新版本、推荐 OTA 和最低 SDK 要求。
- 共享相机问题先追踪 helper 生命周期、配置回调、Surface/NV21 状态和 GL 绘制证据。
- 新接口接入前检查 SDK 版本、系统服务可用性、异步回调和设备 OTA。
- 保持变更最小，不顺带迁移未被需求命中的 Rokid 能力。
