## 1. 规则与本地知识测试

- [x] 1.1 为四类主对象/保护装置组合、保护完整、只有保护装置和多规则同时命中编写 `LocalHazardRuleEvaluator` 失败测试
- [x] 1.2 实现纯 Kotlin `LocalHazardRuleEvaluator`，标准化并去重标签，按固定顺序返回规则命中和缺失配件
- [x] 1.3 为单条、多条、消火栓动态缺失描述及 `info.json` 缺失映射编写本地详情解析失败测试
- [x] 1.4 实现 `LocalHazardDetailResolver`，按稳定规则标识/隐患编号读取知识并生成 `ResolvedHazardContent`
- [x] 1.5 修正 `info.json` 中燃气报警装置与消火栓规则的知识映射，并通过资产 schema 测试

## 2. 本地触发与详情分流

- [ ] 2.1 更新 `LocalTriggerDetectionService` 测试，验证非空无规则标签不再命中、组合规则正确输出 labels 与规则结果
- [ ] 2.2 调整本地 NCNN 触发输出契约，使页面可保留同一帧的规则命中信息用于在线请求或离线回退
- [ ] 2.3 为有网、请求前无网、明确网络连接失败和非网络错误编写详情路由失败测试
- [ ] 2.4 实现详情路由：有网请求 `/ai/deep`，无网直接生成本地详情，明确网络连接失败回退本地详情
- [ ] 2.5 保持 `standard`、`dataBackup` 的 HTTP `/ai/auto` provider 和场景检测链路行为不变

## 3. 统一展示与上传门禁

- [ ] 3.1 接入离线 `ResolvedHazardContent` 到现有模拟流式展示，验证单条和多条内容复用现有滚动、语音及确认状态
- [ ] 3.2 给详情结果增加稳定的远程保存权限，确保权限在详情生成时确定且不随网络恢复改变
- [ ] 3.3 为离线结果确认和确认前恢复网络编写保存门禁测试
- [ ] 3.4 调整 `submitLocalHazardAndShowAdvice()`：离线结果跳过 `pushHidDanger` 和上传成功提示，并继续现有建议/返回流程
- [ ] 3.5 为结束巡检编写网络门禁测试：结束时无网不发起/入队，恢复网络后允许现有 `pushHidDangerEnd` 链路
- [ ] 3.6 在 `InspectionEndReportActivity` 入队前应用即时网络门禁，不用“曾产生离线结果”永久禁用结束接口

## 4. 回归与文档验证

- [ ] 4.1 运行规则、详情解析、路由、保存与结束门禁的定向 JVM 单元测试
- [ ] 4.2 运行 `:app:testLocalTrigerDebugUnitTest` 和 `:app:assembleLocalTrigerDebug`
- [ ] 4.3 运行 `:app:testStandardDebugUnitTest` 和 `:app:assembleStandardDebug`，确认默认在线行为无回归
- [ ] 4.4 更新 `hiddenrisk/README.md` 与 `docs/CODEMAPS.md`，记录在线/离线详情分流和上传边界
- [ ] 4.5 真机验证有网 `/ai/deep`、启动即离线、请求中断网回退、离线隐患不保存及恢复网络后结束巡检
