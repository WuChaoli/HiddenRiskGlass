# Rokid Unified Input 常用模式

## 统一动作建模

先建模动作，再映射输入。

推荐动作粒度：

- `confirm`
- `cancel`
- `exit`
- `retry`
- `previous`
- `next`
- 业务自定义动作，例如 `save_to_phone`、`start_inspection`

不推荐直接按输入源建模：

- `onVoiceStart`
- `onTouchClick`
- `onNod`

这些命名会把页面重新拉回分散监听模式。

## 常见触发器模板

### 主确认动作

```kotlin
UnifiedInputSession.InputActionSpec(
    id = UnifiedInputSession.InputActionId.Confirm,
    label = "确认",
    triggers = buildList {
        add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.CLICK))
        add(UnifiedInputSession.InputTrigger.Voice("确认", "que ren"))
        if (headGestureSupported) {
            add(UnifiedInputSession.InputTrigger.HeadGesture(HeadGestureManager.HeadGestureType.NOD))
        }
    },
    enabled = { shouldAllowConfirm() },
) { event ->
    handleConfirm(event)
}
```

### 取消或退出动作

```kotlin
UnifiedInputSession.InputActionSpec(
    id = UnifiedInputSession.InputActionId.Exit,
    label = "退出",
    triggers = buildList {
        add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.BACK))
        add(UnifiedInputSession.InputTrigger.Touch(UnifiedInputSession.InputKey.DOUBLE_CLICK))
        add(UnifiedInputSession.InputTrigger.Voice("退出", "tui chu"))
        if (headGestureSupported && shouldAllowShakeCancel()) {
            add(UnifiedInputSession.InputTrigger.HeadGesture(HeadGestureManager.HeadGestureType.SHAKE))
        }
    },
    enabled = { shouldAllowExit() },
) { event ->
    handleExit(event)
}
```

## 页面类型建议

### 1. 单步确认页

适用：

- 开始巡检前确认
- 保存到手机前确认
- 提交结果前确认

推荐动作：

- `confirm`
- `cancel` 或 `exit`

推荐映射：

- 单击 + 语音确认 + 点头
- 返回/双击 + 语音退出 + 摇头

### 2. 连续工作页

适用：

- 拍照预览
- 持续检测
- AI 流式响应进行中

推荐动作：

- 主业务动作
- 缩放或上下项
- 退出

推荐映射：

- 触控和语音为主
- 默认不启用陀螺仪

说明：

- 点头/摇头只在明确确认窗口出现时短暂启用
- 不要让用户在持续工作态依赖大幅度头动触发操作

### 3. 结果确认页

适用：

- AI 已返回结果，询问是否同步到手机
- 操作成功后，询问继续下一步还是退出

推荐动作：

- `confirm`
- `cancel`
- 可选 `next`

推荐映射：

- `NOD` 对应主去向
- `SHAKE` 对应取消或次去向

## 迁移旧页面的最小步骤

1. 找到页面里所有 `VoiceAction` 注册点。
2. 找到 `onGlassKeyEvent()` 里的业务分支。
3. 找到 `HeadGestureManager.Listener` 或直接 gesture 回调分支。
4. 合并成 `buildInputActions()`。
5. 把生命周期改成 `attach/detach/release`。
6. 把页面状态切换收敛到 `updateActions()`。
7. 删除页面层重复的语音和 gesture 注册代码。

## 在 glassdemo 中可参考的页面

建议按下面顺序看：

1. `UnifiedInputDebugActivity`
   - 看统一注册层如何覆盖三种输入
   - 看 `updateActions()` 如何热切换动作集合
2. `LightshotActivity`
   - 看真实业务页如何保留触控/语音主操作
3. `InspectionLoadingActivity`
   - 看“点头开始巡检”这类单步确认接法
4. `AiInspectionActivity`
   - 看“点头确认 / 摇头取消”的双分支确认接法

## 什么时候不该用摇头/点头

- 持续检测态
- 列表导航态
- 高频重复动作
- 错误重试页的默认版本
- 用户可能边走边看的场景

核心原则：

- 陀螺仪只用于低频、高价值、强语义的确认动作
- 不用于承担主要导航职责
- 不用于替代所有触控和语音
