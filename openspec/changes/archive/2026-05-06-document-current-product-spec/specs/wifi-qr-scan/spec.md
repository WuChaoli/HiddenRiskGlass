## ADDED Requirements

### Requirement: Scan and connect Wi-Fi from QR code
`WifiQrScanActivity` MUST 支持从二维码读取 Wi-Fi 信息，尝试系统配网流程，并在连接成功后进入后续企业扫码链路。

#### Scenario: Scan valid Wi-Fi QR and start connection
- **WHEN** 页面处于扫描态并识别到合法 Wi-Fi 二维码
- **THEN** 应停止当前扫描循环
- **AND** 应优先尝试系统私有入口或 `Settings.ACTION_WIFI_ADD_NETWORKS`
- **AND** 若系统入口不可用，应回退到 `WifiNetworkSpecifier` 请求

#### Scenario: Verify connection before finishing
- **WHEN** 系统配网流程返回或回退连接流程运行中
- **THEN** 页面必须校验当前 SSID 是否已切换到目标网络
- **AND** 只有验证成功后，才应展示成功结果并允许进入下一页

#### Scenario: Success jumps to configured next page
- **WHEN** Wi-Fi 连接验证成功
- **THEN** 页面应调用 `InspectionWorkflowSession.updateMode(connected = true)`
- **AND** 若存在 `EXTRA_NEXT_AFTER_SUCCESS`，应跳转到该目标页面

### Requirement: Provide page-specific input controls
`WifiQrScanActivity` MUST 按照当前代码注册触控与语音输入动作。

#### Scenario: Confirm action in scanning state
- **WHEN** 页面处于扫描态
- **THEN** 单击应触发确认动作
- **AND** 语音“确认”“确定”“继续”应触发确认动作
- **AND** 确认动作应重新启动相机扫描管线

#### Scenario: Cancel action exits the flow
- **WHEN** 用户执行返回动作
- **THEN** 触控 `BACK` 与 `DOUBLE_CLICK` 应触发取消
- **AND** 语音“返回”“取消”应触发取消
- **AND** 页面应直接退出应用任务

#### Scenario: Head gesture is not active
- **WHEN** 文档描述控制逻辑
- **THEN** 必须说明该页面未显式声明头部动作触发器
- **AND** 由于 `UnifiedInputSession` 当前关闭头部动作监听，正式控制仅依赖触控和语音

### Requirement: Handle invalid QR, permission denial, and connect failure
`WifiQrScanActivity` MUST 对异常和降级路径给出可恢复或可退出行为。

#### Scenario: Invalid or unsupported QR is rejected
- **WHEN** 扫描结果无法解析为支持的 Wi-Fi 二维码，或安全类型不被支持
- **THEN** 页面应提示无效或不支持
- **AND** 在冷却时间后恢复扫描

#### Scenario: Permission denial blocks scanning
- **WHEN** 相机或附近 Wi-Fi/定位权限未被授予
- **THEN** 页面应停留在当前页并提示权限不足

#### Scenario: Connection verification fails
- **WHEN** 连接验证超时或系统连接失败
- **THEN** 页面应展示失败结果
- **AND** 用户可通过确认动作重新进入扫描态
