## Purpose

描述 `AiInspectionMenuActivity` 当前提供的三项正式能力与跳转逻辑，明确它作为巡检主链功能分发页的职责。

## Requirements

### Requirement: Present three formal AI inspection menu entries
`AiInspectionMenuActivity` MUST 提供隐患分析、设备指引、隐患录入三项正式能力，并根据当前选中项执行对应动作。

#### Scenario: Confirm selected menu item
- **WHEN** 用户在菜单页执行确认
- **THEN** 当前选中项应被触发
- **AND** “隐患分析”应进入分析链路
- **AND** “设备指引”应进入 `DeviceGuideActivity` 或在会话未初始化时进入 `InspectionLoadingActivity`
- **AND** “隐患录入”应进入 `HazardRecordActivity`

#### Scenario: Hazard analysis and device guide entries respect inspection session
- **WHEN** 用户选择“隐患分析”或“设备指引”
- **THEN** 若 `InspectionSession.isInitialized` 为 true，应直接进入目标首页
- **AND** 否则应先进入 `InspectionLoadingActivity`

### Requirement: Support touch navigation and voice direct access
菜单页 MUST 支持当前代码中的触控滑动与语音直达控制。

#### Scenario: Touch previous and next change selection
- **WHEN** 用户触发 `BEHIND` 或 `FRONT`
- **THEN** 菜单选中项应在当前列表内移动
- **AND** 不应越过首尾边界

#### Scenario: Touch confirm triggers current item
- **WHEN** 用户触发 `CLICK`
- **THEN** 应执行当前选中项的业务动作

#### Scenario: Voice commands jump to menu items
- **WHEN** 用户说出“隐患分析”“设备指引”或“隐患录入”
- **THEN** 页面应直接触发对应菜单项

#### Scenario: Head gesture is not part of menu controls
- **WHEN** 文档描述菜单控制逻辑
- **THEN** 必须说明菜单页没有声明头部动作触发器
- **AND** 正式输入仅依赖触控和语音
