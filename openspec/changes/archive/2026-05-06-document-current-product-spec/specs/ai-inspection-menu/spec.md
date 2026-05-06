## ADDED Requirements

### Requirement: Present three AI inspection menu entries
`AiInspectionMenuActivity` MUST 提供隐患分析、设备指引、隐患录入三项菜单能力，并根据当前选中项执行对应动作。

#### Scenario: Confirm selected menu item
- **WHEN** 用户在菜单页执行确认
- **THEN** 当前选中项应被触发
- **AND** “隐患分析”应进入分析链路
- **AND** “设备指引”当前只显示开发中提示
- **AND** “隐患录入”应进入 `HazardRecordActivity`

#### Scenario: Hazard analysis entry respects inspection session
- **WHEN** 用户选择“隐患分析”
- **THEN** 若 `InspectionSession.isInitialized` 为 true，应进入 `AiInspectionActivity`
- **AND** 否则应进入 `InspectionLoadingActivity`

### Requirement: Support touch navigation and voice direct access
菜单页 MUST 支持当前代码中的触控滑动与语音直达控制。

#### Scenario: Touch previous/next changes selection
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

### Requirement: Keep placeholder behavior explicit
菜单页 MUST 把未完成功能明确记录为占位，不伪装为已实现能力。

#### Scenario: Device guide remains placeholder
- **WHEN** 用户触发“设备指引”
- **THEN** 页面只更新底部提示为 `common_feature_in_development`
- **AND** 不应跳转到独立页面或执行其他业务逻辑
