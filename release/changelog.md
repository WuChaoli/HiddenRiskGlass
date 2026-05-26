### 2.0.6.2

#### 优化
1. **共享相机视野配置**: 正式识别链路的共享相机 zoom 支持配置化管理，默认使用 `2.0x`，同时保持 Surface 与 NV21 共用中心方形 ROI。
2. **NV21 恢复复用 Session**: 基于 Rokid SDK `2.1.9-E`，相机故障恢复优先复用现有 `CameraShareHelper` session，失败后再回退重建，降低残留 session 风险。
3. **相机诊断补强**: 原始相机诊断页新增 NV21/Surface active 状态、请求与实际配置、支持分辨率及恢复信息展示，便于真机排障。
4. **统一输入词表切换**: 语音命令按设备当前语言原子覆盖词表，并增加页面 owner 交接保护与旧接口回退，避免页面切换期间旧命令残留或误清空。
5. **更新弹窗交互**: APK 更新提示支持前后滑动选择操作项、单击确认，双击或返回取消。

#### 文档与验证
1. **SDK/OTA 基线同步**: 文档与 Rokid 技能基线统一为 SDK `2.1.9-E`、推荐 OTA `1.17.e002-20260509-150201` 及以上，并区分正式 `sharedCameraZoomRatio=2.0` 与 Demo 验证 `zoomLevel=1`。
2. **测试覆盖补充**: 增加相机恢复路径、重启 generation 防护和统一输入词表 owner 交接测试用例。

### 2.0.6.1

#### 新增
1. **加载页版本号显示**: 加载页（`InspectionLoadingActivity`）底部新增版本号文字"本应用由浙江省应科院开发-v2.0.6.1"，版本号通过 `DeviceUtil.getVersionName()` 动态获取，加载中和加载失败状态下始终可见。

### 2.0.6

#### 变更
1. **SDK 升级**: `com.rokid.security:glass3.open.sdk` 从 `2.1.8-E` 升级至 `2.1.9-E`，新版本已修复 Surface 输出异常
2. **统一中心方形 ROI**: 提取 `SharedCameraViewportPolicy` 管理共享相机视野策略，正式 Surface 预览、NV21 检测、上传、扫码、探针链路统一使用同一个中心方形 ROI（`Rect(420,0,1500,1080)`）
3. **移除临时方案**: 清理 `PreviewFramingMode`、`BUSINESS_ROI`、底部方形裁剪等旧 SDK 异常时期的临时兼容逻辑
4. **调试页增强**: `RawCameraPreviewDebugActivity` 新增 SDK Demo 同屏对比模式（`SDK_DEMO_COMPARE`），真机并排展示 Surface / NV21 中心方图用于验证 ROI 一致性；新增 `SURFACE_VALIDATED_CENTER` 正式渲染验证模式
5. **新增组件**: `RokidDemoNv21PreviewView`（NV21 GL 渲染诊断预览）、`SharedCameraViewportPolicy`（共享视野策略）
6. **Demo Surface 增强**: `RokidDemoSurfacePreviewView` 支持中心方形裁剪 uniform，`CameraShareHelper` 改为持久实例
7. **测试覆盖**: 新增 `SharedCameraViewportPolicy` 单元测试
8. **文档更新**: 更新相机预览 Surface 输出经验文档，标记旧方案为历史，记录当前正式策略

### 2.0.5

#### 新增
1. **局域网 APK 热更新**: 实现完整本地更新闭环——Python 本地更新服务器、安卓端版本检查/APK 下载/SHA-256 校验/系统安装器拉起。
2. **菜单页"检查更新"入口**: `AiInspectionMenuActivity` 新增第四个菜单卡片，支持触控和语音（"检查更新"）手动触发更新检查。
3. **启动自动更新检查**: 扫码页（`EnterpriseQrScanActivity`）和菜单页（`AiInspectionMenuActivity`）在 `onResume` 时自动检查更新，加载页不弹更新提示。

#### 优化
1. **菜单页重构**: 4 个固定卡片迁移为 `RecyclerView` + `LinearLayoutManager`（HORIZONTAL）横向滑动，选中框在卡片间移动，仅目标卡片不可见时自动滚动。
2. **更新弹窗时机控制**: 加载页（`InspectionLoadingActivity`）不触发更新检查，仅在扫码页和菜单页弹窗。
3. **Session 级跳过**: 更新弹窗取消后，本次 app 进程期间不再弹出（非持久化，下次启动恢复）。
4. **弹窗防自动消失**: `BaseGlassActivity` 的 `KEEP_SCREEN_ON` 改为子类可控，`AppUpdatePromptActivity` 禁用常亮避免 Glass 自动熄屏。
5. **菜单卡片尺寸调整**: 卡片宽度缩窄至 80dp，RecyclerView 和父布局禁用嵌套滚动和过度滚动，边缘锁定防止整体偏移。

#### 修复
1. 修复菜单确认动作和更新弹窗重复触发的问题。
2. 修复 ViewPager2 `layout_marginHorizontal` 导致扫码后闪退的问题。

### 2.0.4

#### 重构
1. **配置属性缓存**: `AiInspectionActivity` 中 28 个配置属性从每次 `get()` 改为 `by lazy` 一次性加载，避免重复遍历配置链。
2. **服务构造函数优化**: `AiArSseService` / `OnlineHazardDetectionService` 构造函数中多次配置调用合并为 `DEFAULTS` companion object 单次加载。
3. **Handler 通知批处理**: `HeadGestureManager` / `MotionStabilityTracker` / `RokidSdkManager` 中的每监听器独立 post 改为单次 post 批量通知。
4. **扩展函数重命名**: `kt_ext_flow.kt` 中的 `collect` → `collectIn`、`delay` → `delayedLaunch`，消除与 Kotlin stdlib 的导入歧义。
5. **Regex 常量提取**: `AiArSseService` 中重复 `Regex("\\s+")` 编译提取为 companion object 常量。
6. **工具函数去重**: `dpToPx` / `firstNonBlank` / `PreviewFramingMode` 三组跨文件重复定义提取为 `DisplayUtils` / `StringUtils` / `CameraTypes` 共享工具类。
7. **InspectionSession 去重**: `release()` 委托给 `reset()` 消除完全相同的实现体。
8. **OkHttpClient 统一**: 5 处独立 `OkHttpClient` 创建统一为 `HttpClientProvider` 单例，复用连接池。
9. **QuickCameraManager 空安全**: 消除 14 处 `!!` 强制非空断言，缓存 `CameraCharacteristics` 避免 6 处重复查询。
10. **YUV 转换提取**: `QuickCameraManager` / `RokidFrameSource` 中 YUV→Bitmap 转换逻辑提取为 `YuvConversionUtils`。
11. **TTS 状态机**: `AiInspectionActivity` 中 3 个 TTS boolean 标志合并为 `TtsState` 枚举状态机。

### 2.0.3

#### 新增
1. 新增 `/ai/ar` SSE 网络链路 timing 日志，覆盖 DNS、连接、请求头、请求体、连接释放与失败阶段，便于排查网络超时和连接问题。

#### 优化
1. 自动睡眠逻辑调整为佩戴状态驱动，移除基于头部静止和用户活动的睡眠触发逻辑。
2. 自动隐患判断超时调整为 `4000ms`，提升在线识别链路等待容错。
3. 加载页巡检说明文案更新，增加通用操作提示。
4. AI 识患菜单文案调整：`隐患分析` 改为 `实时分析`，`隐患录入` 改为 `隐患拍照`，并同步更新语音指令、页面提示和语音资源。
5. 调整隐患上传成功提示的展示位置和保留逻辑，避免页面动画过程中提示丢失。
6. 设备指引页命中设备后保留提示态约 2 秒并自动进入详情页，用户仍可在提示态手动确认立即拉取详情。

#### 修复
1. 修复企业信息页巡查时间为空时仍显示 mock/fallback 文案的问题，改为空值时隐藏。
2. 修复检查指引/建议页文案前缀展示问题，确保 advice 阶段固定显示建议前缀。
3. 修复设备指引页提示展示时机问题。
4. 删除跳过重大隐患提交逻辑，确保重大隐患按正常链路提交。
5. 修复偶发性隐患无法上传的问题。

### 2.0.2
1. 增加睡眠监测开关，并且默认关闭

### 2.0.1

#### 新增
1. 隐患识别页支持手动触发在线识别与场景深度识别，识别请求补充场景信息，支持按场所码传递 `scene/placeCode`。
2. 隐患录入页新增工贸入口，并优化隐患数量统计与上传内容组装逻辑。
3. 企业信息页新增场所码、最近巡查时间字段，并同步企业扫码与巡检会话中的字段传递。
4. 接入 Rokid 扫码配网主链，Wi-Fi 扫码配网页面接入正式页面流程。
5. 新增本地诊断日志与现场关键链路文件日志，覆盖扫码配网、隐患识别、在线请求、上传等关键链路。
6. 新增自动睡眠状态机与摘镜唤醒链路，支持通过配置控制睡眠监测开关，默认关闭。
7. 新增设备指引与隐患分析相关离线语音提示资源。

#### 优化
1. 隐患识别远程接口切换为新的在线 JSON/SSE 协议，调整 `/ai/general`、`/ai/ar` 等接口请求与响应处理。
2. 在线隐患识别增加标签冷却和触发冷却机制，减少重复识别与重复播报。
3. 优化隐患描述页在自动隐患分析、深度分析、有隐患、无隐患场景下的页面交互与语音提示。
4. 加载页预加载后立即停止摄像头调用，降低持续资源占用。
5. 优化设备指引页和巡检页的相机预览恢复能力。
6. 调整 advice 页面接口与建议检查项协议，并按当前业务流程关闭 advice 页面入口。
7. 更新隐患识别、设备指引、Wi-Fi 连接、日志系统、架构总览等项目文档。

#### 修复
1. 修复隐患识别页无法识别隐患的问题。
2. 修复 `/ai/general` 接口接收处理异常。
3. 修复摄像头启动失败、页面切换后摄像头恢复失败的问题。
4. 修复睡眠恢复后相机无法恢复的问题。

#### 移除
1. 删除 `localHazardDetect`、`demoOnlineonly` 两个非标准构建变体及对应配置资产。
2. 删除杭小应备用上传链路，移除双端点提交协调器及相关测试。
3. 删除检查指引页面开关及相关配置代码。
4. 清理旧 `.codex`、`.claude`、source-command 相关技能资产，并同步项目 `.agents/skills` 技能列表。
