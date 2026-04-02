# AI识患全流程UI - 架构决策记录 (ADR)

## 1. 服务生命周期

**决策**: 页面退出后，检测服务不继续运行

**理由**:
- AR眼镜的相机预览需要Activity支持
- 退出页面意味着用户主动结束巡检
- 避免后台持续消耗电量
- 简化生命周期管理

**影响**:
- Service与Activity绑定，Activity销毁时Service自动停止
- 推理任务在Activity退出时立即取消
- 正在进行的API调用需要优雅取消

---

## 2. 并发处理策略

**决策**: 按队列处理，模型永远只取最新的一张图片推理，并清理所有缓存队列

**理由**:
- 避免多张图片同时推理导致内存溢出
- 用户关注的是最新场景，旧图片推理结果无意义
- 简化并发控制逻辑
- 降低GPU/CPU负载

**实现方式**:
```kotlin
// 伪代码
fun submitImage(image: Bitmap) {
    // 取消正在进行的推理
    cancelCurrentInference()
    // 清理队列
    clearPendingQueue()
    // 提交新图片
    startInference(image)
}
```

---

## 3. 网络重试策略

**决策**: API调用失败时进行有限次重试（最大重试次数：3次）

**理由**:
- 网络波动是常见情况，需要重试机制
- 无限重试会导致资源浪费和用户等待
- 3次重试在成功率和用户体验之间取得平衡

**实现方式**:
```kotlin
const val MAX_RETRY_COUNT = 3
const val RETRY_DELAY_MS = 2000L  // 重试间隔2秒

fun callApiWithRetry(image: Bitmap, retryCount: Int = 0) {
    api.upload(image)
        .onSuccess { handleSuccess() }
        .onFailure { error ->
            if (retryCount < MAX_RETRY_COUNT) {
                delay(RETRY_DELAY_MS)
                callApiWithRetry(image, retryCount + 1)
            } else {
                showError("网络异常，请重试")
            }
        }
}
```

---

## 4. 数据存储策略

**决策**: 当前不需要做本地存储

**理由**:
- MVP阶段优先验证核心流程
- 减少开发工作量
- 避免引入Room等额外依赖
- 隐患数据直接上传到后端

**影响**:
- 网络不可用时无法缓存数据
- 应用重启后历史数据丢失
- 后续版本需要添加本地存储功能

---

## 5. 技术选型

| 组件 | 选型 | 理由 |
|-----|------|------|
| 服务类型 | `LifecycleService` | 方便与Activity生命周期绑定 |
| 网络请求 | `OkHttp + Retrofit` | 成熟稳定，SSE支持好 |
| SSE客户端 | `okhttp-eventsource` | 标准SSE实现，轻量 |
| 数据传递 | `LiveData/Flow` | 官方推荐，响应式 |
| 后台任务 | `Coroutine + ExecutorService` | 已有使用，保持一致 |

---

## 6. 架构分层

```
┌─────────────────────────────────────────────────────────┐
│                    UI Layer (前台)                       │
├─────────────────────────────────────────────────────────┤
│  HiddenRiskProbeActivity    │  HiddenRiskDetailActivity │
│  - 显示相机预览              │  - 显示分析结果            │
│  - 接收手势事件              │  - 流式文字显示            │
│  - 显示状态UI                │  - 确认/取消交互           │
└────────────────────┬────────────────────────────────────┘
                     │ 绑定/解绑
┌────────────────────▼────────────────────────────────────┐
│              Service Layer (后台服务)                     │
├─────────────────────────────────────────────────────────┤
│  HazardDetectionService                                 │
│  ├─ 相机采集管理                                         │
│  ├─ NCNN推理引擎 (HiddenRiskNcnn)                       │
│  ├─ 自动巡检循环 (AutoCaptureLoop)                       │
│  └─ 检测结果回调 → UI                                    │
├─────────────────────────────────────────────────────────┤
│  HazardAnalysisService                                  │
│  ├─ 图片上传任务 (Base64)                                │
│  ├─ SSE连接管理 (流式接收)                               │
│  ├─ Session/Task ID 管理                                 │
│  └─ 分析完成通知 → UI                                    │
├─────────────────────────────────────────────────────────┤
│  HazardSyncService                                      │
│  ├─ 确认保存API调用                                      │
│  └─ 同步状态通知                                         │
└─────────────────────────────────────────────────────────┘
```

---

## 7. 数据流

### 7.1 检测流程
```
相机帧 → HazardDetectionService → NCNN推理 
    ↓ 检测结果
    ├─ 空场景(detectionCount=0): 保持当前界面，继续巡检
    ├─ 无隐患: 回调 onNoHazard()
    └─ 有隐患: 回调 onHazardDetected(image, stats)
         ↓ 用户单击确认
触发分析 → HazardAnalysisService
    ↓ 流式接收
逐字显示 ← HiddenRiskDetailActivity
```

### 7.2 保存流程
```
用户确认 → HazardSyncService → 调用API保存(timestamp, session_id, task_id, is_save)
    ↓ 保存成功
返回巡检 ← HiddenRiskProbeActivity
```

---

## 8. 功能设计

### 8.1 HazardDetectionService (检测服务)

#### 8.1.1 核心功能
```
功能: 自动巡检循环
├── 初始化: SDK → 模型 → 相机
├── 循环: 拍照 → 推理 → 判断
│   ├── 空场景(detectionCount=0): 保持当前界面，继续下一轮巡检
│   ├── 无隐患: 回调 onNoHazard()
│   └── 有隐患: 回调 onHazardDetected(image, stats)
├── 控制: 暂停/恢复/停止
└── 清理: 释放相机、模型资源
```

#### 8.1.2 状态机
```
STATE_IDLE → STATE_LOADING → STATE_READY → STATE_CAPTURING → STATE_INFERRING
   ↓              ↓              ↓              ↓                ↓
STATE_FAILED ← (任何失败)     STATE_EMPTY ← (空场景)    STATE_ALERT ← (有隐患)
                              STATE_SAFE ← (无隐患)
```

#### 8.1.3 回调接口
```kotlin
interface DetectionCallback {
    fun onLoading()                    // 加载中
    fun onReady()                      // 就绪
    fun onCapturing()                  // 拍照中
    fun onEmptyScene()                 // 空场景(无检测结果)
    fun onNoHazard()                   // 无隐患
    fun onHazardDetected(image: Bitmap, stats: InferenceStats)  // 有隐患
    fun onError(message: String)       // 错误
}
```

---

### 8.2 HazardAnalysisService (分析服务)

#### 8.2.1 核心功能
```
功能: 深度分析
├── Session/Task ID 管理:
│   ├── session_id: 本地随机生成(UUID)，一次巡检会话内保持一致
│   └── task_id: 从1开始递增，每次调用分析API时+1
├── 上传图片: Base64编码
│   └── 参数: image(Base64), session_id, task_id
├── 建立SSE连接: EventSource
├── 流式接收: 逐字/逐句解析
│   ├── 缓存接收的文本
│   ├── 回调 onTextChunk(text) 更新UI
│   └── 解析结束标记 [DONE]
├── 解析结果: 仅用于UI显示，不持久化保存
└── 回调: onComplete() / onError(message)
```

#### 8.2.2 Session/Task ID 管理
```kotlin
class SessionManager {
    val sessionId: String = UUID.randomUUID().toString()  // 会话ID，一次巡检内不变
    private var taskIdCounter = AtomicInt(0)               // 任务ID，每次调用+1
    
    fun nextTaskId(): Int = taskIdCounter.incrementAndGet()
    
    fun reset() {  // 新巡检会话时调用
        taskIdCounter.set(0)
    }
}
```

#### 8.2.3 SSE数据格式
```
// 流式文本
data: {"chunk": "检测到消防通道存在"}
data: {"chunk": "杂物堆积，可能影响"}
data: {"chunk": "紧急疏散。"}

// 结束标记
data: [DONE]
```

**注意**: 解析结束后不需要保存type和description，仅用于UI流式显示

#### 8.2.4 请求参数
```kotlin
data class AnalyzeRequest(
    val image: String,        // Base64编码的图片
    val session_id: String,   // 会话ID
    val task_id: Int          // 任务ID(递增)
)
```

#### 8.2.5 回调接口
```kotlin
interface AnalysisCallback {
    fun onConnecting()                 // 连接中
    fun onTextChunk(text: String)      // 流式文本片段
    fun onComplete()                   // 分析完成(收到[DONE])
    fun onError(message: String)       // 错误
    fun onRetry(count: Int)            // 重试通知
}
```

---

### 8.3 HazardSyncService (同步服务)

#### 8.3.1 核心功能
```
功能: 确认保存
├── 调用API: POST /api/hazard/confirm
│   └── 参数:
│       ├── timestamp: 当前时间戳
│       ├── session_id: 与上传分析时一致
│       ├── task_id: 与上传分析时一致
│       └── is_save: true(确认保存) / false(取消保存)
├── 重试机制: 最多3次，间隔2秒
└── 回调: onSuccess() / onFailed(message)
```

#### 8.3.2 请求参数
```kotlin
data class ConfirmRequest(
    val timestamp: Long,       // 当前时间戳(毫秒)
    val session_id: String,    // 会话ID
    val task_id: Int,          // 任务ID
    val is_save: Boolean       // 是否保存
)
```

#### 8.3.3 回调接口
```kotlin
interface SyncCallback {
    fun onSuccess()                    // 保存成功
    fun onFailed(message: String)      // 保存失败
}
```

---

### 8.4 数据模型

#### 8.4.1 InferenceStats (已有)
```java
// NativeInferenceStats.java
// detectionCount, inferenceTimeMs, errorMessage等
```

#### 8.4.2 Session管理
```kotlin
data class SessionInfo(
    val sessionId: String,     // UUID随机生成
    val taskId: Int            // 递增计数器
)
```

---

## 9. 网络层设计

### 9.1 依赖添加
```gradle
// build.gradle
implementation "com.squareup.okhttp3:okhttp:4.12.0"
implementation "com.squareup.okhttp3:logging-interceptor:4.12.0"
implementation "com.squareup.retrofit2:retrofit:2.9.0"
implementation "com.squareup.retrofit2:converter-gson:2.9.0"
implementation "com.launchdarkly:okhttp-eventsource:3.0.0"  // SSE
```

### 9.2 API接口定义
```kotlin
interface HazardApiService {
    @POST("/api/hazard/analyze")
    fun analyze(
        @Body request: AnalyzeRequest
    ): Call<SseConnection>  // SSE连接
    
    @POST("/api/hazard/confirm")
    fun confirmSave(
        @Body request: ConfirmRequest
    ): Call<SyncResponse>
}
```

### 9.3 SSE客户端
```kotlin
class SseClient {
    fun connect(url: String, callback: SseCallback) {
        // 建立EventSource连接
        // 接收data事件
        // 解析JSON
        // 回调通知
    }
    
    fun disconnect() {
        // 关闭连接
    }
}
```

---

## 10. 模拟SSE服务端设计

### 10.1 功能
```
功能: 模拟后端API
├── 接收Base64图片 + session_id + task_id
├── 返回模拟SSE流
│   ├── 逐字输出分析文本
│   └── 输出[DONE]结束标记
└── 接收确认保存请求(timestamp, session_id, task_id, is_save)
```

### 10.2 实现方式
```kotlin
// Android端模拟（不依赖外部服务）
class MockSseClient {
    fun simulateAnalysis(callback: AnalysisCallback) {
        // 模拟流式输出
        val text = "检测到消防通道存在杂物堆积，可能影响人员疏散。"
        text.forEach { char ->
            callback.onTextChunk(char.toString())
            delay(50)
        }
        callback.onComplete()
    }
}
```

---

## 11. 交互流程图

```
用户操作              Service              UI回调
   │                    │                    │
   │───单击确认─────────→│                    │
   │                    │───上传图片(Base64)──→│
   │                    │   session_id, task_id│
   │                    │←──SSE连接建立───────│
   │                    │───onConnecting()───→│ 显示"连接中"
   │                    │───onTextChunk()────→│ 逐字显示
   │                    │───onComplete()─────→│ 显示完成
   │←──显示分析结果──────│                    │
   │                    │                    │
   │───单击保存─────────→│                    │
   │                    │───confirmSave()────→│
   │                    │   timestamp,        │
   │                    │   session_id,       │
   │                    │   task_id, is_save  │
   │                    │←──onSuccess()───────│ 显示"已同步"
   │←──返回巡检──────────│                    │
```

---

## 12. 错误处理

| 场景 | 处理方式 |
|-----|---------|
| 网络不可用 | 显示"网络异常"，允许重试 |
| API超时 | 30秒超时，显示"请求超时" |
| SSE断开 | 自动重连1次，失败则报错 |
| 图片上传失败 | 重试3次，失败则取消分析 |
| 推理失败 | 记录日志，继续下一次巡检 |
| 空场景 | 保持当前界面，继续巡检 |

---

**记录日期**: 2026-04-02
**最后更新**: 2026-04-02
**状态**: 已确认
**相关文档**: `prd.md`, `流程图.mmd`
