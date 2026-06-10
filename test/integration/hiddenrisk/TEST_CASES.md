# TEST_CASES: integration/hiddenrisk

> 本模块集成测试索引。每条用例对应 `evidence/` 下的一个日期文件夹。

## 用例清单

| 编号 | 用例名称 | 目标 | 状态 | 最新证据 |
|-----|---------|------|------|---------|
| INTEG-HIDRISK-001 | 隐患识别链路时序分析 | 分析 hiddenrisk 模块关键链路耗时和调用顺序 | ✅ 已通过 | `evidence/2026-05-07_hiddenrisk_logcat_timing/` |

## 用例详情

### INTEG-HIDRISK-001: 隐患识别链路时序分析

- **触发条件**: 启动 AI 巡检，观察从帧捕获到结果展示的完整链路
- **预期结果**: 各阶段耗时符合预期，无异常阻塞
- **验证方式**: logcat 过滤 + dumpsys 分析
- **关联代码**: `InspectionSession`, `AutoHazardPipelineDecider`, `AiArSseService`
- **回归风险**: 高（推理链路核心路径）
