# Rokid 新系统相机预览 Surface 输出经验

日期：2026-05-25

## 背景

Rokid Glass 系统更新后，业务预览页出现画面被压缩、只在 View 下半部分显示、视野异常等问题。初始判断曾集中在业务 ROI 裁切和 `RokidCameraPreviewView` 的 Surface/NV21 坐标映射上，但后续测试证明新系统的 Surface 输出语义已经与原有假设不同。

本经验用于后续再次排查相机预览形变、黑边、裁切、Surface 与 NV21 不一致等问题。

## 验证结论更新

2026-05-26 使用 Glass3 SDK Demo 最新代码与 `com.rokid.security:glass3.open.sdk:2.1.9-E` 复测：

- 上游基线提交：`76e17c6fb98f7c84aa621d01236d9f7a1218dade`。
- Surface 拉伸修复来源提交：`33483b2442875013ffd36db059b3f6d4b1ab4d93`。
- 在真机同屏展示 Surface 与 NV21 原始画面时，两路视野几乎一致，旧版本观察到的 Surface 异常不能继续作为正式渲染假设。
- 正式方案恢复为 Surface 与 NV21 使用同一中心方形 ROI。

眼镜端显示基线仍为：

- 固件：`1.17.e002-20260509-150201`
- 屏幕：`480x640`
- Surface 预览回调：`1080x1920`
- NV21 帧：`1920x1080`
- Surface transform matrix 摘要：`[1.0, 0.0, 0.0, -1.0, 0.0, 1.0]`
- SDK Demo 对比模式 zoom：`zoomLevel=1`（隔离验证值，不是正式业务默认值）

此前旧 SDK/系统组合在测试页中曾观察到：

- `SURFACE_RAW` 无拉伸，旋转正确。
- `SURFACE_RAW` 使用全屏 `480x640` View 时，完整显示 SDK Surface 内容，不做裁剪。
- `SURFACE_RAW` 的 Surface 原始内容本身是上方黑区、下方有效画面，不是业务 View 遮挡导致。
- `NV21_RAW` 无形变。
- `NV21_SQUARE_BASELINE` 使用 `1920x1080` 中心方形裁剪，裁剪框为 `Rect(420,0,1500,1080)`，显示正常。
- 将 NV21 方形 ROI 直接映射到当时的 Surface 纹理坐标，不能稳定得到正确画面。

## 关键证据

全屏显示 Surface 原始内容时，日志为：

```text
gl surface changed width=480 height=640
surface preview camera opened width=1080 height=1920
first preview draw textureId=1 crop=[0.0,0.0,1.0,1.0]
preview raw aspect fit configured=1080x1920 viewport=480x640 matrixSwapped=false scale=0.75x1.0 matrix=[1.0,0.0,0.0,-1.0,0.0,1.0]
```

含义：

- `crop=[0.0,0.0,1.0,1.0]` 说明没有裁剪 Surface 纹理。
- `scale=0.75x1.0` 只是等比完整显示 `1080x1920` 到 `480x640`，不是 camera zoom，也不是 ROI 裁剪。
- 因此截图中的上方黑区来自 SDK Surface 原始纹理内容，而不是我们的 View 把画面遮住。

测试底部方形 Surface 裁剪时，日志为：

```text
gl surface changed width=330 height=330
surface preview camera opened width=1080 height=1920
first preview draw textureId=1 crop=[0.0,0.4375,1.0,0.5625]
preview surface bottom square configured=1080x1920 viewport=330x330 crop=[0.0,0.4375,1.0,0.5625] matrix=[1.0,0.0,0.0,-1.0,0.0,1.0]
```

含义：

- `1080x1920` 中底部 `1080x1080` 区域对应纹理裁剪：
  - `top = (1920 - 1080) / 1920 = 0.4375`
  - `height = 1080 / 1920 = 0.5625`
- View 也必须是方形，例如 `330x330`，否则即使裁剪出方形纹理，也会被 View 拉成竖屏。

## 历史临时方案

旧版本问题定位期间，`SURFACE_BOTTOM_SQUARE` 用于从异常 Surface 纹理底部取 `1080x1080` 画面，验证人眼看到的内容不被拉伸。该模式现在只保留在调试页供回归旧问题，不能作为正式业务默认策略。

## 当前正式策略

共享相机视野策略统一由 `camera/SharedCameraViewportPolicy.kt` 管理：

- 正式 Surface 预览、NV21 检测、上传、扫码与探针链固定使用同一个中心方形 ROI。
- `1920x1080` NV21 的统一 ROI 为 `Rect(420,0,1500,1080)`。
- 正式 NV21 与 Surface 的 zoom 统一由 `inspection_config.base.jsonc` 的 `sharedCameraZoomRatio` 控制，当前默认值为 `2.0`；中心方形 ROI 策略不变。
- 正式代码以已修复 SDK/系统组合为部署基线，不维护旧 Surface 异常的固件回退分支。
- SDK Demo 同屏模式把 Surface 与 NV21 都裁为中心 `1080x1080`，用于真机直接核对正式 ROI 视野。
- `2.1.9-E` 下 NV21 故障恢复优先复用已有 `CameraShareHelper` session；调试页显示 NV21/Surface active、配置回调结果与支持分辨率，正式分辨率仍固定 `1920x1080@15fps`。

## 后续修改准则

1. 正式业务 Surface 预览通过 `AUTO_SURFACE_SQUARE` 使用中心方形 ROI，不在页面内自行选择其他取景方式。
2. 识别、上传、扫码与探针链统一以 NV21 帧和 `calculateValidatedNv21SquareCropRect(...)` 为准。
3. 如果 Rokid 后续 OTA 或 SDK 再改变 Surface 布局，优先重新打开 `RawCameraPreviewDebugActivity`，先验证：
   - `SDK_DEMO_COMPARE`
   - `SURFACE_RAW`
   - `SURFACE_VALIDATED_CENTER`
   - `NV21_RAW`
   - `NV21_SQUARE_BASELINE`
   - `SURFACE_BOTTOM_SQUARE`
4. 任何“画面被裁掉/被压扁”的判断，都先同时记录：
   - View 尺寸
   - Surface 回调尺寸
   - NV21 帧尺寸
   - transform matrix
   - crop 参数
   - vertex scale

## 常用验证命令

### SDK Demo 同屏复测

2026-05-26 起，`RawCameraPreviewDebugActivity` 默认提供隔离的 `SDK_DEMO_COMPARE` 模式：

- 代码来源基线为 `glass3sdkdemo` 的 `76e17c6fb98f7c84aa621d01236d9f7a1218dade`，其中拉伸修复来自 `33483b2442875013ffd36db059b3f6d4b1ab4d93`。
- 应用依赖基线升级到 `com.rokid.security:glass3.open.sdk:2.1.9-E`。
- 上半屏走 SDK Demo Surface 共享渲染，下半屏走 SDK Demo NV21 GL 渲染。
- 两路都固定使用 `1920x1080@15fps`、`EIS=false`、`zoomLevel=1`，并显示中心 `1080x1080` 方图，用于隔离核对 ROI 边界；正式链路默认 `sharedCameraZoomRatio=2.0`，不得由该验证值反推。
- 该模式用于验证；正式链路仍由 `RokidFrameSource`、`RokidCameraPreviewView` 和相机协调/恢复层管理。

构建：

```bash
export JAVA_HOME=/home/wuchaoli/jdk/temurin-21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleStandardDebug --no-daemon -Pkotlin.incremental=false
```

安装并启动底部方形 Surface 测试：

```bash
DEVICE=<device_serial>
ADB=/home/wuchaoli/.claude/skills/wsl-android-tools/scripts/win-adb.sh
bash "$ADB" -s "$DEVICE" install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk
bash "$ADB" -s "$DEVICE" logcat -c
bash "$ADB" -s "$DEVICE" shell am force-stop com.rokid.glesse
bash "$ADB" -s "$DEVICE" shell am start -n com.rokid.glesse/com.rokid.glass.hiddenrisk.RawCameraPreviewDebugActivity --es mode surface_bottom_square
```

启动 SDK Demo Surface / NV21 同屏对比：

```bash
bash "$ADB" -s "$DEVICE" shell am start -n com.rokid.glesse/com.rokid.glass.hiddenrisk.RawCameraPreviewDebugActivity --es mode sdk_demo_compare
```

启动正式 Surface 中心 ROI 渲染验证：

```bash
bash "$ADB" -s "$DEVICE" shell am start -n com.rokid.glesse/com.rokid.glass.hiddenrisk.RawCameraPreviewDebugActivity --es mode surface_validated_center
```

抓关键日志：

```bash
bash "$ADB" -s "$DEVICE" logcat -d -v brief | rg "RawCameraPreviewDebug|RokidCameraPreview|RokidFrameSource|InspectionCameraCoord|AndroidRuntime"
```
