# Unit 单元测试

JVM 单测，不依赖 Android 运行时。

## 覆盖模块

- [hiddenrisk/](hiddenrisk/) — 隐患识别核心（25+ 测试类）
- [component/](component/) — UI 组件
- [workflow/](workflow/) — 业务上下文
- [updater/](updater/) — 应用更新
- [camera/](camera/) — 相机帧流
- [input/](input/) — 统一输入
- [config/](config/) — 配置系统
- [utils/](utils/) — 工具库

## 执行方式

```bash
# 全部单元测试
./gradlew :app:testStandardDebugUnitTest

# 指定模块
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.*"
```
