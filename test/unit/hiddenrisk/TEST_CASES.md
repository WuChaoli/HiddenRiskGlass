# TEST_CASES: unit/hiddenrisk

> 本模块单元测试索引。测试代码位于 `app/src/test/java/com/rokid/glass/hiddenrisk/`。

## 测试类清单

| 编号 | 测试类 | 测试目标 | 对应证据 |
|-----|--------|---------|---------|
| UNIT-HIDRISK-001 | `AiArEventAggregatorTest` | SSE 事件聚合逻辑 | -- |
| UNIT-HIDRISK-002 | `AutoHazardPresentationCoordinatorTest` | 隐患展示协调器状态机 | -- |
| UNIT-HIDRISK-003 | `InspectionRetryExecutorTest` | 检测重试执行器 | -- |
| UNIT-HIDRISK-004 | `MayHazardDeepVerifyProtocolTest` | 深度验证协议 | -- |
| UNIT-HIDRISK-005 | `AutoHazardPipelineDeciderTest` | 双轨调度决策器 | -- |
| UNIT-HIDRISK-006 | `SimulatedStreamTextChunkerTest` | 流式文本分块 | -- |
| UNIT-HIDRISK-007 | `SuggestionChecksProtocolTest` | 建议检查协议 | -- |
| UNIT-HIDRISK-008 | `AiArHazardDetailParserTest` | 隐患详情解析器 | -- |
| UNIT-HIDRISK-009 | `AiArSseServiceRequestPayloadTest` | SSE 请求载荷构造 | -- |
| UNIT-HIDRISK-010 | `AutoInferenceLoopDeciderTest` | 自动推理循环决策 | -- |
| UNIT-HIDRISK-011 | `InferencePressureMonitorTest` | 推理压力监控 | -- |
| UNIT-HIDRISK-012 | `InspectionFrameCaptureServiceTest` | 帧捕获服务 | -- |
| UNIT-HIDRISK-013 | `LocalHazardInfoAssetSchemaTest` | 本地隐患资源 schema | -- |
| UNIT-HIDRISK-014 | `LocalHazardItemMatcherTest` | 隐患项匹配器 | -- |
| UNIT-HIDRISK-015 | `LocalHazardResultDeduperTest` | 隐患结果去重 | -- |
| UNIT-HIDRISK-016 | `OnlineHazardAdviceFormatterTest` | 在线隐患建议格式化 | -- |
| UNIT-HIDRISK-017 | `ResolvedHazardContentTest` | 解析后隐患内容 | -- |
| UNIT-HIDRISK-018 | `SharedInferenceFrameDeciderTest` | 共享推理帧决策 | -- |
| UNIT-HIDRISK-019 | `InspectionCameraCoordinatorStateMachineTest` | 相机协调器状态机 | -- |
| UNIT-HIDRISK-020 | `InspectionFinishApiProtocolTest` | 巡检结束 API 协议 | -- |
| UNIT-HIDRISK-021 | `LocalHazardPushApiProtocolTest` | 隐患推送 API 协议 | -- |
| UNIT-HIDRISK-022 | `LocalHazardUploadItemBuilderTest` | 隐患上传项构造器 | -- |
| UNIT-HIDRISK-023 | `OnlineHazardDetectionServiceTest` | 在线隐患检测服务 | -- |
| UNIT-HIDRISK-024 | `AppVisibilityConfigFactoryTest` | 应用可见性配置工厂 | -- |
| UNIT-HIDRISK-025 | `AppVisibilityRefreshSchedulerTest` | 应用可见性刷新调度器 | -- |

## 执行方式

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.*"
```
