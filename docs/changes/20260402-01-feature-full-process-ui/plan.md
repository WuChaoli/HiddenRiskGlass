# AI识患全流程UI - 开发计划

## 项目概述

实现从"AI识患"入口到隐患检测、深度分析、同步保存的完整流程，包括前端UI交互和后台服务层。

**参考文档**:
- `adr.md` - 架构决策记录
- `prd.md` - 产品需求文档
- `流程图.mmd` - Mermaid流程图

---

## Phase 0: 环境准备与依赖添加

### 目标
添加项目所需的网络库依赖，为后续开发做准备。

### 任务清单

#### 0.1 添加网络依赖
- [ ] 修改 `app/build.gradle`，添加以下依赖：
  ```gradle
  implementation "com.squareup.okhttp3:okhttp:4.12.0"
  implementation "com.squareup.okhttp3:logging-interceptor:4.12.0"
  implementation "com.squareup.retrofit2:retrofit:2.9.0"
  implementation "com.squareup.retrofit2:converter-gson:2.9.0"
  ```
- [ ] 同步Gradle，验证依赖下载成功

**预估工时**: 15分钟
**优先级**: P0 (阻塞后续开发)

### 🚪 出口门禁 (Exit Gate)
- [ ] `./gradlew :app:dependencies` 验证依赖已正确解析
- [ ] `./gradlew :app:assembleDebug` 构建成功，无编译错误
- [ ] 应用可正常安装到设备并启动

---

## Phase 1: 后台服务层 - 检测服务

### 目标
重构 `HiddenRiskProbeActivity`，提取检测逻辑到独立服务层，支持空场景/无隐患/有隐患三种分支。

### 任务清单

#### 1.1 创建 SessionManager
- [ ] 新建 `com/rokid/glass/hiddenrisk/SessionManager.kt`
- [ ] 实现 session_id 生成 (UUID随机)
- [ ] 实现 task_id 递增计数器
- [ ] 实现 reset() 方法

**文件**: `app/src/main/java/com/rokid/glass/hiddenrisk/SessionManager.kt`

**预估工时**: 30分钟

#### 1.2 创建 DetectionCallback 接口
- [ ] 新建 `com/rokid/glass/hiddenrisk/DetectionCallback.kt`
- [ ] 定义回调方法：
  - `onLoading()`
  - `onReady()`
  - `onCapturing()`
  - `onEmptyScene()` - 空场景
  - `onNoHazard()` - 无隐患
  - `onHazardDetected(image, stats)` - 有隐患
  - `onError(message)`

**文件**: `app/src/main/java/com/rokid/glass/hiddenrisk/DetectionCallback.kt`

**预估工时**: 20分钟

#### 1.3 重构 HiddenRiskProbeActivity
- [ ] 提取推理逻辑到独立方法
- [ ] 添加空场景判断分支 (detectionCount == 0)
- [ ] 修改 `applyHazardDecision()` 方法，支持三种分支
- [ ] 添加 `DetectionCallback` 回调机制
- [ ] 修改 `showSafeUi()` 支持用户交互（单击继续/双击退出）
- [ ] 修改 `showAlertUi()` 支持用户交互（单击详情/双击跳过）
- [ ] 添加手势处理：双击返回Home

**文件**: `app/src/main/java/com/rokid/glass/hiddenrisk/HiddenRiskProbeActivity.kt`

**预估工时**: 2小时

#### 1.4 更新布局文件
- [ ] 修改 `activity_hidden_risk_probe.xml`
- [ ] 添加提示文字控件（"单击继续/双击退出"等）
- [ ] 优化UI显示逻辑

**文件**: `app/src/main/res/layout/activity_hidden_risk_probe.xml`

**预估工时**: 30分钟

### 🚪 出口门禁 (Exit Gate)
- [ ] 三种分支（空场景/无隐患/有隐患）在日志中正确输出
- [ ] 空场景时界面保持，不显示结果提示
- [ ] 无隐患时显示"是否继续巡检"，单击/双击响应正确
- [ ] 有隐患时显示警告，单击/双击响应正确
- [ ] 双击返回HomeActivity，无内存泄漏
- [ ] `./gradlew :app:assembleDebug` 构建成功

---

## Phase 2: 后台服务层 - 分析服务

### 目标
实现图片上传和SSE流式接收功能，支持模拟模式。

### 任务清单

#### 2.1 创建数据模型
- [ ] 新建 `com/rokid/glass/hiddenrisk/AnalyzeRequest.kt`
  ```kotlin
  data class AnalyzeRequest(
      val image: String,
      val session_id: String,
      val task_id: Int
  )
  ```
- [ ] 新建 `com/rokid/glass/hiddenrisk/ConfirmRequest.kt`
  ```kotlin
  data class ConfirmRequest(
      val timestamp: Long,
      val session_id: String,
      val task_id: Int,
      val is_save: Boolean
  )
  ```
- [ ] 新建 `com/rokid/glass/hiddenrisk/SyncResponse.kt`

**文件**:
- `app/src/main/java/com/rokid/glass/hiddenrisk/AnalyzeRequest.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/ConfirmRequest.kt`
- `app/src/main/java/com/rokid/glass/hiddenrisk/SyncResponse.kt`

**预估工时**: 30分钟

#### 2.2 创建 AnalysisCallback 接口
- [ ] 新建 `com/rokid/glass/hiddenrisk/AnalysisCallback.kt`
- [ ] 定义回调方法：
  - `onConnecting()`
  - `onTextChunk(text: String)`
  - `onComplete()`
  - `onError(message: String)`
  - `onRetry(count: Int)`

**文件**: `app/src/main/java/com/rokid/glass/hiddenrisk/AnalysisCallback.kt`

**预估工时**: 20分钟

#### 2.3 创建 SseClient
- [ ] 新建 `com/rokid/glass/network/SseClient.kt`
- [ ] 实现 SSE 连接建立
- [ ] 实现 data 事件解析
- [ ] 实现 JSON 解析
- [ ] 实现回调通知
- [ ] 实现连接关闭

**文件**: `app/src/main/java/com/rokid/glass/network/SseClient.kt`

**预估工时**: 1.5小时

#### 2.4 创建 HazardAnalysisService
- [ ] 新建 `com/rokid/glass/hiddenrisk/HazardAnalysisService.kt`
- [ ] 实现图片转Base64
- [ ] 实现API调用（上传+分析）
- [ ] 实现SSE流式接收
- [ ] 实现重试机制（最多3次）
- [ ] 实现 SessionManager 集成
- [ ] 实现回调通知

**文件**: `app/src/main/java/com/rokid/glass/hiddenrisk/HazardAnalysisService.kt`

**预估工时**: 2小时

#### 2.5 创建 MockSseClient (模拟模式)
- [ ] 新建 `com/rokid/glass/hiddenrisk/MockSseClient.kt`
- [ ] 实现模拟流式输出
- [ ] 模拟分析文本逐字输出
- [ ] 模拟 [DONE] 结束标记
- [ ] 支持配置延迟时间

**文件**: `app/src/main/java/com/rokid/glass/hiddenrisk/MockSseClient.kt`

**预估工时**: 1小时

### 🚪 出口门禁 (Exit Gate)
- [ ] `SessionManager` 单元测试通过（session_id唯一性、task_id递增）
- [ ] `SseClient` 能正确解析SSE数据格式
- [ ] `HazardAnalysisService` 使用Mock模式可输出完整流式文本
- [ ] 重试机制验证：模拟3次失败后正确报错
- [ ] `./gradlew :app:testDebugUnitTest` 测试通过
- [ ] `./gradlew :app:assembleDebug` 构建成功

---

## Phase 3: 后台服务层 - 同步服务

### 目标
实现确认保存API调用功能。

### 任务清单

#### 3.1 创建 SyncCallback 接口
- [ ] 新建 `com/rokid/glass/hiddenrisk/SyncCallback.kt`
- [ ] 定义回调方法：
  - `onSuccess()`
  - `onFailed(message: String)`

**文件**: `app/src/main/java/com/rokid/glass/hiddenrisk/SyncCallback.kt`

**预估工时**: 15分钟

#### 3.2 创建 HazardSyncService
- [ ] 新建 `com/rokid/glass/hiddenrisk/HazardSyncService.kt`
- [ ] 实现确认保存API调用
- [ ] 实现重试机制（最多3次，间隔2秒）
- [ ] 实现 SessionManager 集成
- [ ] 实现回调通知

**文件**: `app/src/main/java/com/rokid/glass/hiddenrisk/HazardSyncService.kt`

**预估工时**: 1.5小时

### 🚪 出口门禁 (Exit Gate)
- [ ] `SyncCallback` 正确回调 onSuccess/onFailed
- [ ] 请求参数格式正确（timestamp, session_id, task_id, is_save）
- [ ] 重试机制验证：模拟失败后重试，最多3次
- [ ] `./gradlew :app:assembleDebug` 构建成功

---

## Phase 4: 前端UI - 隐患详情界面

### 目标
创建 `HiddenRiskDetailActivity`，实现流式文字显示和用户交互。

### 任务清单

#### 4.1 创建布局文件
- [ ] 新建 `activity_hidden_risk_detail.xml`
- [ ] 添加流式文字显示区域 (ScrollView + TextView)
- [ ] 添加进度提示 ("正在深度分析...")
- [ ] 添加操作提示 ("单击保存/双击跳过")
- [ ] 添加同步结果提示区域

**文件**: `app/src/main/res/layout/activity_hidden_risk_detail.xml`

**预估工时**: 45分钟

#### 4.2 创建 HiddenRiskDetailActivity
- [ ] 新建 `com/rokid/glass/hiddenrisk/HiddenRiskDetailActivity.kt`
- [ ] 实现流式文字显示逻辑
- [ ] 实现手势处理：
  - 单击 → 确认保存
  - 双击 → 取消保存，返回巡检
- [ ] 集成 `HazardAnalysisService`
- [ ] 集成 `HazardSyncService`
- [ ] 实现分析完成后的UI切换
- [ ] 实现保存成功后的UI切换
- [ ] 实现错误提示和重试

**文件**: `app/src/main/java/com/rokid/glass/hiddenrisk/HiddenRiskDetailActivity.kt`

**预估工时**: 2.5小时

### 🚪 出口门禁 (Exit Gate)
- [ ] 流式文字逐字显示，无卡顿、无闪烁
- [ ] 分析过程中显示进度提示
- [ ] 分析完成后显示操作提示
- [ ] 单击保存 → 调用SyncService → 显示"已同步"
- [ ] 双击跳过 → 返回巡检，无残留状态
- [ ] 网络异常时显示错误提示，支持重试
- [ ] Activity生命周期正确处理（onDestroy清理资源）
- [ ] `./gradlew :app:assembleDebug` 构建成功

---

## Phase 5: 前端UI - 菜单入口

### 目标
在首页添加"AI识患"菜单入口。

### 任务清单

#### 5.1 添加菜单类型
- [ ] 修改 `MenuConfigType.java`
- [ ] 添加 `MENU_HIDDEN_RISK = "menu_hidden_risk"`

**文件**: `app/src/main/java/com/rokid/glass/annotation/MenuConfigType.java`

**预估工时**: 10分钟

#### 5.2 添加菜单项
- [ ] 修改 `HomeActivity.kt` - `initIconData()` 方法
- [ ] 添加"AI识患"菜单项
- [ ] 添加图标资源 (如缺少需创建占位图标)

**文件**: `app/src/main/java/com/rokid/glass/HomeActivity.kt`

**预估工时**: 20分钟

#### 5.3 添加跳转逻辑
- [ ] 修改 `HomeActivity.kt` - `jump2NextScene()` 方法
- [ ] 添加 `MENU_HIDDEN_RISK` 的 case
- [ ] 跳转到 `HiddenRiskProbeActivity`

**文件**: `app/src/main/java/com/rokid/glass/HomeActivity.kt`

**预估工时**: 15分钟

### 🚪 出口门禁 (Exit Gate)
- [ ] 首页菜单显示"AI识患"选项
- [ ] 点击"AI识患"正确跳转到 `HiddenRiskProbeActivity`
- [ ] 图标显示正常（无占位符或空白）
- [ ] `./gradlew :app:assembleDebug` 构建成功

---

## Phase 6: 集成测试与调试

### 目标
测试完整流程，修复发现的问题。

### 任务清单

#### 6.1 单元测试
- [ ] 测试 SessionManager (session_id生成、task_id递增)
- [ ] 测试数据模型序列化/反序列化
- [ ] 测试 Base64 编码/解码

**预估工时**: 1小时

#### 6.2 集成测试
- [ ] 测试完整流程：
  1. 首页 → 点击"AI识患"
  2. 加载界面 → 自动巡检
  3. 空场景 → 保持界面继续巡检
  4. 无隐患 → 显示"是否继续" → 单击继续/双击退出
  5. 有隐患 → 显示警告 → 单击详情/双击跳过
  6. 详情界面 → 流式文字显示 → 单击保存/双击跳过
  7. 保存成功 → 显示"已同步" → 单击继续/双击退出

**预估工时**: 2小时

#### 6.3 模拟模式测试
- [ ] 使用 `MockSseClient` 测试完整流程
- [ ] 验证流式文字显示效果
- [ ] 验证手势交互逻辑
- [ ] 验证错误处理和重试

**预估工时**: 1.5小时

### 🚪 出口门禁 (Exit Gate)
- [ ] 完整流程端到端测试通过（见6.2）
- [ ] 无内存泄漏（LeakCanary或手动验证）
- [ ] 无ANR（主线程无阻塞操作）
- [ ] 无崩溃日志（Logcat无FATAL异常）
- [ ] 网络异常场景处理正确
- [ ] 所有手势交互符合预期
- [ ] `./gradlew :app:assembleDebug` 构建成功
- [ ] 应用可正常安装到设备并完整运行

---

## 文件清单

### 新建文件
| 文件路径 | 说明 | Phase |
|---------|------|-------|
| `hiddenrisk/SessionManager.kt` | Session/Task ID管理 | 1.1 |
| `hiddenrisk/DetectionCallback.kt` | 检测回调接口 | 1.2 |
| `hiddenrisk/AnalysisCallback.kt` | 分析回调接口 | 2.2 |
| `hiddenrisk/SyncCallback.kt` | 同步回调接口 | 3.1 |
| `hiddenrisk/AnalyzeRequest.kt` | 分析请求数据模型 | 2.1 |
| `hiddenrisk/ConfirmRequest.kt` | 确认保存请求数据模型 | 2.1 |
| `hiddenrisk/SyncResponse.kt` | 同步响应数据模型 | 2.1 |
| `hiddenrisk/HazardAnalysisService.kt` | 分析服务 | 2.4 |
| `hiddenrisk/HazardSyncService.kt` | 同步服务 | 3.2 |
| `hiddenrisk/MockSseClient.kt` | 模拟SSE客户端 | 2.5 |
| `network/SseClient.kt` | SSE客户端 | 2.3 |
| `hiddenrisk/HiddenRiskDetailActivity.kt` | 隐患详情Activity | 4.2 |
| `layout/activity_hidden_risk_detail.xml` | 详情界面布局 | 4.1 |

### 修改文件
| 文件路径 | 说明 | Phase |
|---------|------|-------|
| `app/build.gradle` | 添加网络依赖 | 0.1 |
| `hiddenrisk/HiddenRiskProbeActivity.kt` | 重构检测逻辑 | 1.3 |
| `layout/activity_hidden_risk_probe.xml` | 更新布局 | 1.4 |
| `annotation/MenuConfigType.java` | 添加菜单类型 | 5.1 |
| `HomeActivity.kt` | 添加菜单入口 | 5.2, 5.3 |

---

## 开发顺序建议

```
Phase 0 (依赖) 
    ↓
Phase 1 (检测服务) ← 基础
    ↓
Phase 2 (分析服务) ← 核心
    ↓
Phase 3 (同步服务) ← 辅助
    ↓
Phase 4 (详情UI)   ← 前端
    ↓
Phase 5 (菜单入口) ← 入口
    ↓
Phase 6 (集成测试) ← 验证
```

**建议**: Phase 2 和 Phase 3 可以并行开发，Phase 4 可以在 Phase 2 完成后开始。

---

## 总预估工时

| Phase | 预估工时 |
|-------|---------|
| Phase 0 | 15分钟 |
| Phase 1 | 3小时20分钟 |
| Phase 2 | 5小时25分钟 |
| Phase 3 | 1小时45分钟 |
| Phase 4 | 3小时35分钟 |
| Phase 5 | 45分钟 |
| Phase 6 | 4小时30分钟 |
| **总计** | **约19.5小时** |

---

## 风险与注意事项

### 技术风险
1. **SSE兼容性**: OkHttp的SSE支持可能需要额外配置
2. **内存管理**: Base64编码大图可能导致OOM，需要限制图片尺寸
3. **网络超时**: AR眼镜网络环境可能不稳定，需要合理设置超时

### 开发风险
1. **依赖冲突**: 新增网络库可能与现有依赖冲突
2. **生命周期管理**: Service与Activity绑定需要仔细处理
3. **手势冲突**: 双击事件可能与系统手势冲突

### 缓解措施
1. 使用 `MockSseClient` 先行验证流程
2. 添加图片压缩逻辑，限制上传尺寸
3. 设置合理的超时和重试策略
4. 充分测试生命周期边界情况

---

**创建日期**: 2026-04-02
**状态**: 待执行
**相关文档**: `adr.md`, `prd.md`, `流程图.mmd`
