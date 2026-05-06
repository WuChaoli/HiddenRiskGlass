## Purpose

记录“设备指引”在当前代码中的真实状态：它是一个被保留的占位能力，存在入口但尚未形成独立页面和业务闭环。

## Requirements

### Requirement: Keep device-guide as an explicit placeholder capability
系统 MUST 把“设备指引”记录为当前未完成能力，而不是误写成已经存在的正式功能页面。

#### Scenario: Menu page exposes placeholder entry
- **WHEN** 用户在 `AiInspectionMenuActivity` 触发“设备指引”
- **THEN** 页面只应更新底部提示为 `common_feature_in_development`
- **AND** 不应发生页面跳转

#### Scenario: Hazard record page exposes placeholder voice entry
- **WHEN** 用户在 `HazardRecordActivity` 触发语音“设备指引”
- **THEN** 页面只应更新提示为 `common_feature_in_development`
- **AND** 不应启动新页面或分析链路

### Requirement: Describe current control boundary
设备指引规格 MUST 说明当前只有入口和提示，没有独立交互闭环。

#### Scenario: Controls are limited to existing host pages
- **WHEN** 文档描述控制逻辑
- **THEN** 应说明“设备指引”没有自己的 Activity 或独立输入映射
- **AND** 当前控制逻辑仅依赖宿主页面已有触控或语音入口

#### Scenario: Head gesture is not separately defined
- **WHEN** 文档描述陀螺仪/头部动作
- **THEN** 必须说明设备指引能力没有单独声明头部动作触发器
- **AND** 当前也不应被视为启用头部动作的功能

### Requirement: Mark the capability as non-closed-loop
规格 MUST 把设备指引标为未闭环功能，帮助后续实现时正确识别范围。

#### Scenario: Capability remains out of completed product chain
- **WHEN** 开发者查看设备指引规格
- **THEN** 应明确知道该能力尚未形成页面跳转闭环
- **AND** 它只能作为菜单占位与未来扩展保留位
