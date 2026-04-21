# UnifiedInput 设计与接入

## 目标

统一收敛三类输入：

- 语音
- 触控
- 陀螺仪

页面不再分别维护 `VoiceAction`、`onGlassKeyEvent` 分支和 `HeadGestureManager.Listener`，而是只声明：

- 有哪些业务动作
- 每个动作由哪些输入源触发
- 当前动作是否启用

## 当前落地范围

当前已落地：

- `com.rokid.glass.hiddenrisk.UnifiedInputDebugActivity`
- `com.rokid.glass.hiddenrisk.LightshotActivity`
- `com.rokid.glass.hiddenrisk.InspectionLoadingActivity`
- `com.rokid.glass.hiddenrisk.AiInspectionActivity`

菜单入口已挂到：

- `InspectionModeActivity`

其中：

- 调试页用于验证统一输入注册层闭环是否稳定
- `LightshotActivity` 是第一个正式迁移页面，用来验证真实业务页改造方式
- `InspectionLoadingActivity` 已接入“点头开始巡检”
- `AiInspectionActivity` 已接入“点头确认 / 摇头取消”确认流

## 核心结构

统一输入层位于：

- `app/src/main/java/com/rokid/glass/input/UnifiedInput.kt`

核心对象：

- `UnifiedInputSession`
  - 页面持有的统一输入会话
  - 负责 attach / detach / updateActions / release
- `InputActionSpec`
  - 页面声明的动作项
  - 包含动作 id、触发源、启用条件、回调
- `InputTrigger`
  - 三类触发源统一描述
  - `Voice`
  - `Touch`
  - `HeadGesture`
- `InputEvent`
  - 统一分发给页面的事件对象

## 当前手势约定

在需要用户做“确认 / 取消”选择的页面，统一约定为：

- `NOD` = 主确认动作
- `SHAKE` = 次动作 / 取消动作

当前只在确认节点启用陀螺仪：

- `InspectionLoadingActivity`
  - 完成态：点头开始巡检
- `AiInspectionActivity`
  - `STREAM_RESPONSE`：点头确认同步、摇头取消同步
  - `SYNC_SUCCESS`：点头继续巡检、摇头退出巡检

以下场景不接陀螺仪：

- `DETECTING` 持续检测态
- 加载失败重试态
- 导航和连续操作场景

## 页面接入方式

典型接法：

1. 创建 `UnifiedInputSession`
2. 在页面里声明 `buildInputActions()`
3. `onResume()` 调 `attach()`
4. `onPause()` 调 `detach()`
5. 页面状态变化时调用 `updateActions()`
6. `onGlassKeyEvent()` 里把按键转发给 `dispatchTouch()`

当前调试页示例可直接参考：

- `app/src/main/java/com/rokid/glass/hiddenrisk/UnifiedInputDebugActivity.kt`

## 当前调试页验证内容

### FULL 配置

- 触控
  - 单击 -> Confirm
  - 前滑 -> Previous
  - 后滑 -> Next
  - 返回 / 双击 -> Exit
- 语音
  - “确认” -> Confirm
  - “上一个” -> Previous
  - “下一个” -> Next
  - “退出” -> Exit
- 陀螺仪
  - `NOD` -> DebugNod
  - `SHAKE` -> DebugShake

### GESTURE_ONLY 配置

- 保留：
  - Confirm
  - Exit
  - DebugNod
- 关闭：
  - Next
  - Previous
  - DebugShake

通过 Confirm 在两套配置间切换，用来验证 `updateActions()` 是否会正确替换语音注册和 gesture 映射。

## 验证建议

真机调试时重点看三件事：

1. 任一输入源命中后，页面顶部“最近一次命中”是否更新
2. 切到 `GESTURE_ONLY` 后，被禁用动作是否真的不再响应
3. 页面切后台 / 退出后，是否残留旧语音命令或头部动作监听

建议关注日志关键字：

- `统一输入语音注册成功`
- `统一输入头部动作监听已启动`
- `统一输入头部动作监听已停止`

## 后续迁移顺序

当前剩余正式页面可按下面顺序继续迁移：

1. `InspectionModeActivity`
   - 仍保留旧的菜单语音注册与触控分发
2. 其他仍有独立 `VoiceAction` / `onGlassKeyEvent` 逻辑的页面

## 当前限制

- 触控底座目前仍由现有 `BaseGlassActivity` 负责翻译，统一输入层只做动作分发
- 两套 `BaseGlassActivity` / `GlassKeyEvent` 还没有合并，本阶段只做兼容接入
- 本地无法完成 Gradle 编译校验时，先保证：
  - 资源引用闭环
  - Manifest 注册闭环
  - Kotlin/布局静态巡检通过
