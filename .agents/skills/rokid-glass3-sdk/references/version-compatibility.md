# Rokid SDK / OTA / 共享预览兼容参考

## 当前基线

这是当前主技能的版本真相源。

- 当前仓库 SDK 基线：`com.rokid.security:glass3.open.sdk:2.1.9-E`
- 当前推荐 OTA 基线：`1.17.e002-20260509-150201` 及以上
- 来源：`app/build.gradle`
- 当前技能判断优先级：代码现状 > 官方 changelog > 历史摘录

处理版本敏感任务时，不要只引用旧技能文案或单个 changelog 切片。

## 审计流程

每次涉及 Rokid 版本或兼容性时，都按这个顺序核对：

1. `app/build.gradle` 当前依赖版本
2. 官方 changelog 对应目标 SDK 版本
3. 该 changelog 对应的推荐 OTA 基线
4. 本仓库实际 call site 是否命中受影响接口
5. 共享预览和统一输入是否依赖了该版本新增的语义

## 当前仓库依赖的共享预览语义

当前代码不仅依赖“能开相机”，还依赖以下语义持续成立：

- `initNv21ExportWithConfig(...)` 与 `initSurfaceWithConfig(...)` 可并存于同一 helper 生命周期
- Surface 路径会给出 `onSurfaceShareConfigChanged(...)`
- NV21 路径会给出 `onNv21ExportResolutionChanged(...)`
- `transformMatrix` 可用于判断横竖轴是否交换
- `width/height/appliedPreviewFps/videoStabilizationEnabled` 变化能反映系统当前真实预览配置
- 正式 NV21 故障恢复通过 `restartNv21ExportWithConfig(...)` 优先复用现有 helper/session；Surface 只诊断 active 状态，本阶段不变更页面恢复生命周期
- `isNv21Active()`、`isSurfaceActive()` 和 `getSupportedPreviewSizes()` 纳入调试页诊断

## `2.1.9-E` 能力采用范围

- 已采用：配置变更回调、NV21 session 复用重启、NV21/Surface active 和支持分辨率诊断
- 已采用：按设备当前离线语言键覆盖统一输入词表，并保留逐条注册兼容回退
- 暂不采用：`restartSurfaceWithConfig()` 的业务恢复迁移、`LeqiInterceptor`、灯光/网络类型/应用可见性/时间服务接口

如果系统或 OTA 行为和这些假设不一致，常见表现是：

- 预览有首帧回调，但一直没有 `first preview draw`
- 预览能画，但裁剪区域错误、方向反了、ROI 漂移
- NV21 与 Surface 的宽高或 fps 信息不同步

## 旧 OTA / 新 OTA 分流诊断

这里的“旧 / 新”指需要验证的系统语义，不是让你凭印象给设备贴标签。

### 更像预览绑定 / GL 问题

特征：

- `shared surface first frame available` 已出现
- 没有 `first preview draw`
- 或长期出现 `shared surface texture id still 0 after frame update`

优先动作：

- 先检查 `RokidCameraPreviewView` 绑定、重建、draw check
- 不要先重启帧流

### 更像系统版本差异导致的裁剪 / 方向语义变化

特征：

- `first preview draw` 已出现
- `preview crop updated ... swapped=... matrix=...` 与实际画面不一致
- NV21 检测和 Surface 预览取景不一致

优先动作：

- 对照 `transformMatrix`
- 看 `axisSwapped` 判断是否符合当前系统
- 检查 `onSurfaceShareConfigChanged(...)` / `onNv21ExportResolutionChanged(...)` 的宽高更新是否正常

### 更像 SDK / OTA 兼容矩阵问题

特征：

- 关键 config callback 不触发
- `width/height/appliedPreviewFps` 长期为空或异常
- 同一页面在不同系统版本表现差异显著

优先动作：

- 先记录实机 OTA 版本
- 再比对官方 changelog 推荐矩阵
- 最后才决定是否 bump SDK 或做兼容分支

## 设备侧日志关键字

先抓这些 tag / 关键字，再决定是否改代码：

- `RokidCameraPreview`
- `RokidFrameSource`
- `InspectionCameraCoordinator`
- `shared surface first frame available`
- `shared surface texture id still 0 after frame update`
- `first preview draw`
- `surface share config changed`
- `nv21 export resolution changed`
- `preview crop updated`
- `auto_sleep_warning`
- `resumeFromAutoSleep`
- `pause owner=`
- `acquire owner=`
- `updatePreview owner=`
- `preview_ready owner=`

## 历史切片，不是当前基线

以下内容来自旧技能保留的 `v2.1.2 (2025-12-15)` changelog 摘录，只用于历史对照，不代表当前仓库基线：

- 当时 glass SDK 基线：`com.rokid.security:glass3.open.sdk:2.1.2-E`
- 当时推荐 OTA：`1.10.e001-20251215-150202` 及以上
- 该切片强调的新增能力包括：
  - `GlassSdk.getGlassCollectService()`
  - `GlassSdk.getGlassOfflineTtsService()`
  - `IMediaServer.setMediaStateLister(...)`
  - `IMediaServer.removeMediaStateLister(...)`
  - `IOfflineRecServer.submitRecognizedFaceInfo(...)`

使用方式：

- 如果用户明确提到 `v2.1.2` 或对应 changelog anchor，再把这些条目映射到本仓库 call site
- 如果用户问“当前仓库是不是最新 / 该不该升级”，不要把这段历史切片当成答案本身
