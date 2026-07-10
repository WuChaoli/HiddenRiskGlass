# 菜单语音指令自动注册机制（卡片 + 页面通用动作）

**日期**: 2026-06-11
**状态**: 设计完成

## 背景

当前菜单页面中存在两类重复的手动语音注册工作：

1. **卡片语音指令**：每张卡片的语音指令需在 `buildInputActions()` 中手动注册，与卡片定义分离。添加新卡片需同时修改 3 处代码
2. **页面通用动作**：每个页面的确认（"确认"/"确定"/"继续"→单击）和取消（"取消"/"返回"→双击/返回）语音指令在各页面中重复手写

## 目标

- 定义 `VoiceActionItem` 接口，卡片实现后自动获得语音指令
- 页面通用动作（确认/取消）自动注册，各页面只需提供回调
- Activity 切换时自动注销旧语音指令，无需手动管理

## 设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 卡片语音注册范围 | 仅卡片 | 非卡片指令保持手动注册 |
| 卡片语音触发行为 | 等于点击卡片 | 语音和触摸走同一执行路径 |
| 拼音来源 | strings.xml 配对资源 | 零依赖、编译期可检查 |
| 卡片接口类型 | `interface` | Kotlin 惯例，数据类可实现多接口 |
| 卡片执行机制 | Lambda 注入 | 消除 `when(index)` 分支 |
| 页面确认触发集 | CLICK + 确认/确定/继续 | 覆盖导航确认、对话框确认、流程继续三种语义 |
| 页面取消触发集 | BACK + DOUBLE_CLICK + 取消/返回 | 覆盖所有返回/退出场景 |
| 页面通用动作 | `buildPageCommonActions()` | 统一到 `UnifiedInputSession` companion object |

## 架构

### 卡片层：VoiceActionItem 接口

```
VoiceActionItem (input 包, 新增)
  ├─ labelResId: Int       ← 语音指令文字资源 ID
  ├─ pinyinResId: Int      ← 拼音资源 ID
  └─ execute(): Unit       ← 触发后执行的动作

MenuCardData (adapter 包, 修改)
  implements VoiceActionItem
  ├─ iconResId: Int
  ├─ labelResId: Int       ← 实现 VoiceActionItem
  ├─ pinyinResId: Int      ← 新增
  ├─ iconChar: String?
  └─ onClick: (() -> Unit)? ← 新增, Activity 注入
```

### 页面层：UnifiedInputSession 扩展

```
UnifiedInputSession companion object (修改)
  ├─ PAGE_CONFIRM_TRIGGERS: List<InputTrigger>   ← 新增常量
  │    Touch(CLICK) + Voice(确认/确定/继续)
  ├─ PAGE_CANCEL_TRIGGERS: List<InputTrigger>    ← 新增常量
  │    Touch(BACK) + Touch(DOUBLE_CLICK) + Voice(取消/返回)
  └─ buildPageCommonActions(onConfirm, onCancel) ← 新增方法
       → List<InputActionSpec>(Confirm, Cancel)
```

## 数据流

```
Activity.onCreate
  └─ menuAdapter = MenuCardAdapter(cards = listOf(
       MenuCardData(labelResId, pinyinResId, onClick = { navigate() })
     ))

Activity.onResume
  └─ inputSession.attach()
       └─ updateActions(buildInputActions())
            ├─ buildPageCommonActions(onConfirm, onCancel) ← 页面级确认/取消自动注册
            ├─ buildCardVoiceActions(menuAdapter.cards)     ← 卡片语音自动注册
            └─ 非卡片语音指令（手动添加）

用户说"实时分析"
  └─ GlassSdk 语音回调
       └─ dispatchTrigger(Voice("实时分析", "shi shi fen xi"))
            └─ card.execute() → onClick() → startActivity(AiInspectionActivity)

用户说"返回"
  └─ dispatchTrigger(Voice("返回", "fan hui"))
       └─ onCancel() → showExitConfirmDialog() / finish()

Activity.onPause
  └─ inputSession.detach()  ← 自动清除语音词汇表
```

## 文件影响

| 文件 | 操作 | 说明 |
|------|------|------|
| `input/VoiceActionItem.kt` | 新增 | 卡片语音指令接口 |
| `input/UnifiedInput.kt` | 修改 | 新增 `PAGE_CONFIRM/CANCEL_TRIGGERS` 常量 + `buildPageCommonActions()` |
| `adapter/MenuCardAdapter.kt` | 修改 | `MenuCardData` 实现 `VoiceActionItem`，新增 `pinyinResId`、`onClick` |
| `MainMenuActivity.kt` | 修改 | 卡片注入 lambda + 拼音；`buildInputActions` 使用两个 build 方法 |
| `AiInspectionMenuActivity.kt` | 修改 | 同上 |
| `res/values/strings.xml` | 修改 | 新增 6 个卡片拼音资源 |

## 不需要改动

- `VoiceVocabularyOwnerState` — 生命周期不变
- 所有非卡片语音指令 — 保持手动注册
- 布局 XML 文件 — 不变

## 验证方式

1. 编译通过：`./gradlew :app:assembleStandardDebug`
2. 卡片语音：主菜单和巡检菜单说出卡片文字，确认正确跳转
3. 页面通用语音：任意页面说"确认"/"返回"等，确认触发正确的确认/取消行为
4. 页面切换隔离：从主菜单进入巡检菜单后，主页面的卡片语音应无反应
5. 回归：非卡片语音指令（退出应用、结束巡查等）功能正常
