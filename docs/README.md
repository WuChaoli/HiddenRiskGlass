# glassdemo 文档导航

`docs/` 现在是当前产品行为的唯一正文真相源。阅读时优先按“正式主链总览 -> 业务模块 -> 公共能力”的顺序进入，不再区分旧的 README、页面说明、跳转说明等薄文档。

## 阅读入口

- [总体旅程图](C:\Users\wuchaoli\Desktop\codespace\glassdemo\docs\总体旅程图\README.md)
  - 看正式主链全景、入口分流、返回路径和附录页边界
- [公共能力](C:\Users\wuchaoli\Desktop\codespace\glassdemo\docs\公共能力\README.md)
  - 看会话、统一输入、导航分层、识别链路等跨功能真相源
- [功能模块/WiFi连接.md](C:\Users\wuchaoli\Desktop\codespace\glassdemo\docs\功能模块\WiFi连接.md)
- [功能模块/任务关联.md](C:\Users\wuchaoli\Desktop\codespace\glassdemo\docs\功能模块\任务关联.md)
- [功能模块/主菜单.md](C:\Users\wuchaoli\Desktop\codespace\glassdemo\docs\功能模块\主菜单.md)
- [功能模块/隐患识别.md](C:\Users\wuchaoli\Desktop\codespace\glassdemo\docs\功能模块\隐患识别.md)
- [功能模块/设备指引.md](C:\Users\wuchaoli\Desktop\codespace\glassdemo\docs\功能模块\设备指引.md)
- [功能模块/隐患录入.md](C:\Users\wuchaoli\Desktop\codespace\glassdemo\docs\功能模块\隐患录入.md)
- [功能模块/结束巡查.md](C:\Users\wuchaoli\Desktop\codespace\glassdemo\docs\功能模块\结束巡查.md)

## 当前组织规则

- 每个业务模块只保留一个主文档：`docs/功能模块/模块名.md`
- 所有业务截图统一放在：`docs/功能模块/screenshots/模块名/`
- 跨功能规则不再独立作为业务模块，统一收敛到 [公共能力](C:\Users\wuchaoli\Desktop\codespace\glassdemo\docs\公共能力\README.md)
- 正式主链和附录页边界统一收敛到 [总体旅程图/总体旅程图.md](C:\Users\wuchaoli\Desktop\codespace\glassdemo\docs\总体旅程图\总体旅程图.md)
- 同一段规则只保留一个正文真相源，旅程图和公共能力文档只做补充，不重复业务模块正文

## 正式主链与附录页边界

### 正式主链页面

- `InspectionLoadingActivity`
- `WifiQrScanActivity`
- `EnterpriseQrScanActivity`
- `EnterpriseInfoActivity`
- `AiInspectionMenuActivity`
- `AiInspectionActivity`
- `DeviceGuideActivity`
- `HazardRecordActivity`
- `InspectionEndReportActivity`

### 附录 / 调试页面

- `UnifiedInputDebugActivity`
- `HiddenRiskProbeActivity`
- `LightshotActivity`
- `HomeActivity`
- `InspectionModeActivity`

这些页面可以作为调试、验证或历史参考入口，但不作为当前产品基线真相源。

## 建议阅读顺序

1. 先看 [总体旅程图/总体旅程图.md](C:\Users\wuchaoli\Desktop\codespace\glassdemo\docs\总体旅程图\总体旅程图.md)
2. 再进入目标业务模块主文档
3. 需要落代码时，优先看模块文档中的“代码真相源”
4. 需要理解跨功能约束时，再看 [公共能力](C:\Users\wuchaoli\Desktop\codespace\glassdemo\docs\公共能力\README.md)
