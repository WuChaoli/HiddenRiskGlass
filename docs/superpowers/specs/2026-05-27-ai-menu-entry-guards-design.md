# AI 菜单入口检查设计

## 背景

当前应用存在两个菜单层级：

- `InspectionModeActivity`：旧巡检模式页，包含 AI 识患、任务检查、闪拍、扫一扫、统一输入调试、Rokid 扫码配网。
- `AiInspectionMenuActivity`：业务菜单页，包含实时分析、设备指引、隐患录入、检查更新。

本次需求指定 `AiInspectionMenuActivity` 作为 App 入口主页，并删除 WiFi 扫码相关页面。企业扫码页继续保留，用于绑定企业信息。

## 目标行为

应用启动后直接进入 `AiInspectionMenuActivity`。每次进入该页面时都按固定顺序执行前置检查：

1. 检查 WiFi 是否已连接。
2. 检查摄像头和检测链路是否已加载。
3. 检查企业信息是否已绑定。

只有三项检查均通过，用户才停留在菜单页并选择实时分析、设备指引、隐患录入或检查更新。

## WiFi 检查

菜单页使用 `SystemStateUtils.getCurrentWifiSsid(this)` 判断当前是否连接 WiFi。

未连接 WiFi 时，不再进入任何 WiFi 扫码或配网页。页面中央显示一个顶层弹窗：

- 文案：`请先连接wifi`
- 按钮：`确定`

用户点击确定，或通过已有统一输入确认动作触发确定后，应用调用 `finishAndRemoveTask()` 退出。

## 摄像头加载检查

WiFi 已连接后，菜单页检查 `InspectionSession.isInitialized`。

如果尚未初始化，跳转 `InspectionLoadingActivity` 执行现有 SDK、相机、模型加载流程。加载完成后返回 `AiInspectionMenuActivity`，不直接进入企业扫码或实时分析页面。这样菜单页始终是入口检查和业务分发中心。

## 企业绑定检查

摄像头加载完成后，菜单页检查企业绑定状态：

- `InspectionWorkflowSession.enterpriseQrPayload != null`
- `InspectionWorkflowSession.enterpriseInfo != null`

任一为空时，跳转 `EnterpriseQrScanActivity`。扫码成功并拉取企业信息后返回 `AiInspectionMenuActivity`。菜单页下一次 `onResume()` 重新执行完整检查。

## 页面删除范围

删除 WiFi 扫码相关页面和入口：

- 删除 `WifiQrScanActivity`
- 删除 `activity_wifi_qr_scan.xml`
- 从 `AndroidManifest.xml` 移除 `WifiQrScanActivity`
- 从旧 `InspectionModeActivity` 或相关字符串中移除 WiFi 扫码入口文案
- 清理仅 WiFi 扫码页面使用的字符串和依赖

保留 `EnterpriseQrScanActivity`，它是企业绑定流程的一部分。

## 导航收口

`InspectionLoadingActivity` 不再在无 WiFi 时跳转 `WifiQrScanActivity`。加载完成后返回菜单页，由菜单页继续执行企业绑定检查。

`AiInspectionMenuActivity` 的功能按钮保留现有职责：

- 实时分析：进入 `AiInspectionActivity`
- 设备指引：进入 `DeviceGuideActivity`
- 隐患录入：进入 `HazardRecordActivity`
- 检查更新：执行现有更新检查逻辑

如果用户从菜单页点击功能时发现加载状态已失效，可以沿用菜单页同一套检查逻辑，先恢复加载再进入功能。

## 错误处理

- 无 WiFi：只显示弹窗并退出，不做自动配网。
- Loading 失败：保留 `InspectionLoadingActivity` 现有错误页和重试/退出行为。
- 企业扫码失败：保留 `EnterpriseQrScanActivity` 现有扫码失败处理。

## 验证

最小验证范围：

1. 构建环境检查：`bash scripts/android/doctor.sh`
2. Debug 构建：`bash scripts/android/build-debug.sh`
3. 未连接 WiFi 启动：进入菜单页后显示“请先连接wifi”，确认后退出。
4. 已连接 WiFi 且未初始化：菜单页跳 loading，加载完成后回菜单页。
5. 已连接 WiFi、已初始化、未绑定企业：菜单页跳企业扫码页。
6. 已连接 WiFi、已初始化、已绑定企业：停留菜单页，四个功能按钮可用。
7. 菜单中不再出现 WiFi 扫码入口。

