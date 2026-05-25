# Rokid 新系统相机预览 Surface 输出经验

日期：2026-05-25

## 背景

Rokid Glass 系统更新后，业务预览页出现画面被压缩、只在 View 下半部分显示、视野异常等问题。初始判断曾集中在业务 ROI 裁切和 `RokidCameraPreviewView` 的 Surface/NV21 坐标映射上，但后续测试证明新系统的 Surface 输出语义已经与原有假设不同。

本经验用于后续再次排查相机预览形变、黑边、裁切、Surface 与 NV21 不一致等问题。

## 当前已验证现象

眼镜端显示基线仍为：

- 屏幕：`480x640`
- Surface 预览回调：`1080x1920`
- NV21 帧：`1920x1080`
- Surface transform matrix 摘要：`[1.0, 0.0, 0.0, -1.0, 0.0, 1.0]`
- SDK zoom：`1.0`

测试页 `RawCameraPreviewDebugActivity` 中已经验证：

- `SURFACE_RAW` 无拉伸，旋转正确。
- `SURFACE_RAW` 使用全屏 `480x640` View 时，完整显示 SDK Surface 内容，不做裁剪。
- `SURFACE_RAW` 的 Surface 原始内容本身是上方黑区、下方有效画面，不是业务 View 遮挡导致。
- `NV21_RAW` 无形变。
- `NV21_SQUARE_BASELINE` 使用 `1920x1080` 中心方形裁剪，裁剪框为 `Rect(420,0,1500,1080)`，显示正常。
- 将 NV21 方形 ROI 直接映射到 Surface 纹理坐标的多个候选方案，都不能稳定得到正确画面。

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

## 当前结论

新系统中不能再假设：

- Surface 的 `1080x1920` 内容等价于一个完整竖向相机画面。
- NV21 的 `1920x1080` ROI 坐标可以直接映射到 Surface 纹理坐标。
- 业务预览只要沿用旧的 `BUSINESS_ROI` 裁剪逻辑就能与识别帧一致。

更接近真实情况的是：

- NV21 帧仍然是完整横向相机数据，适合作为识别、上传、算法裁剪的基准。
- Surface 预览是 SDK 已处理过的竖向纹理，当前新系统把有效预览画面放在底部方形区域，上方为黑区。
- Surface 预览优先解决“人眼看到不变形”，不应强行要求与 NV21 ROI 坐标完全一致。

## 已验证的临时方案

在 `RokidCameraPreviewView` 中新增 `SURFACE_BOTTOM_SQUARE` 渲染模式：

- 读取 Surface 配置 `1080x1920`
- 取底部 `1080x1080` 方形纹理
- 使用方形 View 显示
- 不使用 NV21 ROI 坐标映射

核心裁剪语义：

```text
left = 0.0
top = 1.0 - min(surfaceWidth, surfaceHeight) / surfaceHeight
width = 1.0
height = min(surfaceWidth, surfaceHeight) / surfaceHeight
```

当 Surface 是 `1080x1920` 时，得到：

```text
crop=[0.0,0.4375,1.0,0.5625]
```

测试页显示结果已经验证为方形且无明显纵向压缩。

## 后续修改准则

1. 业务预览若优先追求“画面不变形”，可以先切到 `SURFACE_BOTTOM_SQUARE`。
2. 识别、上传、扫码等算法链路继续以 NV21 帧和 `calculateScanCropRect(...)` 为准。
3. 不要把 NV21 方形 ROI 坐标直接套到 Surface crop，除非后续拿到 SDK 明确的坐标转换规则。
4. 如果 Rokid 后续 OTA 再改变 Surface 布局，优先重新打开 `RawCameraPreviewDebugActivity`，先验证：
   - `SURFACE_RAW`
   - `NV21_RAW`
   - `NV21_SQUARE_BASELINE`
   - `SURFACE_BOTTOM_SQUARE`
5. 任何“画面被裁掉/被压扁”的判断，都先同时记录：
   - View 尺寸
   - Surface 回调尺寸
   - NV21 帧尺寸
   - transform matrix
   - crop 参数
   - vertex scale

## 常用验证命令

构建：

```bash
export JAVA_HOME=/home/wuchaoli/jdk/temurin-21
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:assembleStandardDebug --no-daemon -Pkotlin.incremental=false
```

安装并启动底部方形 Surface 测试：

```bash
DEVICE=1901092548003897
ADB=/home/wuchaoli/.claude/skills/wsl-android-tools/scripts/win-adb.sh
bash "$ADB" -s "$DEVICE" install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk
bash "$ADB" -s "$DEVICE" logcat -c
bash "$ADB" -s "$DEVICE" shell am force-stop com.rokid.glesse
bash "$ADB" -s "$DEVICE" shell am start -n com.rokid.glesse/com.rokid.glass.hiddenrisk.RawCameraPreviewDebugActivity --es mode surface_bottom_square
```

抓关键日志：

```bash
bash "$ADB" -s "$DEVICE" logcat -d -v brief | rg "RawCameraPreviewDebug|RokidCameraPreview|RokidFrameSource|InspectionCameraCoord|AndroidRuntime"
```
