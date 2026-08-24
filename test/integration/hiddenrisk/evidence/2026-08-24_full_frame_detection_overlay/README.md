# 完整画幅实时打框测试页真机证据

## 验证环境

- 日期：2026-08-24
- 设备序列号：`1901092544019017`
- 设备：`RG_glasses`
- Activity：`com.rokid.glass.hiddenrisk.FullFrameDetectionOverlayTestActivity`
- APK：`app/build/outputs/apk/standard/debug/app-standard-debug.apk`

## 自动化门禁

- `:app:testStandardDebugUnitTest`：通过。
- `scripts/android/build-debug.ps1`：通过。
- `scripts/android/tests/startFullFrameDetectionOverlayTest.Tests.ps1`：通过。
- APK 安装：`Success`。
- ADB 启动：成功进入独立测试 Activity。

## 相机与请求实测

SDK 支持尺寸日志明确包含：

```text
supported preview sizes=3024x4032:true, 3000x4000:true, ...
```

本次运行关键日志：

```text
nv21 export zoom changed zoomLevel=1
frame stream opened active=true helperReused=false
camera ready=true requested=3024x4032
nv21 actual=3024x4032 aspect=3:4 requested=3024x4032
auto requestId=2 source=3024x4032 request=960x1280 jpegBytes=159481
responseBBoxCount=0 projectionCrop=RectFModel(
  left=1218.4386,
  top=1907.2078,
  right=1825.65,
  bottom=2716.823
) mappedBBoxCount=0
```

结论：测试 Profile 可取得视觉转正的 `3024×4032` 原始 3:4 NV21；请求前未裁切，等比例缩放为 `960×1280`，真实 `/auto` 请求成功返回；固定 1m 标定投影窗口已按原始帧坐标生成。

## 视觉结论与边界

- 本次镜头场景的真实 `/auto` 连续返回 0 个 bbox，页面按设计清空 Overlay，未出现伪框或底部图片预览。
- 因没有真实 bbox，本轮无法验收框与现实目标的视觉重合度，也无法记录有框状态下半透明预览与纯框模式的对比截图。
- 后续现场复验需要让镜头对准 `/auto` 可识别目标；出现 bbox 后，先以 `alpha=0.5` 检查框与投影预览，再切到 `alpha=0` 检查框与现实目标，并记录最终 `scale/offsetX/offsetY`。
- 本阶段未接入 `/ai/deep`，也未实现 bbox 占画面 `1/8` 的门禁；这些属于正式业务页后续改造。
