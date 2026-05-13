## Why

近期多次代码提交后，`docs/` 中的功能文档与隐患识别链路文档与实际代码存在多处不一致（ctype、并发模型、配置项、新增字段等），需要同步更新以恢复文档作为"代码真相源"的可靠性。

## What Changes

1. **隐患识别链路.md** — 更新 ctype=1→3、onlineDetectIntervalMs 1000→500ms、detectTimeoutMs 3000→1500ms；补充并发池（上限5）描述；移除旧的 single-flight 描述。
2. **隐患识别.md** — 同上，覆盖链路 A 节拍/超时/并发描述、`AutoInferenceLoopDecider` 新增签名、`OnlineHazardDetectionService` 并发模型。
3. **任务关联.md** — 企业信息 `EnterpriseInfo` 新增 `placeCode` 和 `lastInspectionDate` 字段；补充"最近巡查时间"展示。
4. **配置变体清理** — 记录 `localHazardDetect.jsonc` 和 `demoOnlineonly.jsonc` 已删除。
5. **验证检查项更新** — 更新 `隐患识别.md` 开发检查清单中过时的 ctype 和并发假设。

## Capabilities

### New Capabilities
- `doc-sync-online-detection`: 在线隐患检测链路参数对齐（ctype、间隔、超时、并发池）
- `doc-sync-enterprise-info`: 企业信息新增字段对齐（placeCode、lastInspectionDate）

### Modified Capabilities
- （无现有 spec，新变化直接更新 docs/ 正文）

## Impact

- `docs/公共能力/隐患识别链路.md`
- `docs/功能模块/隐患识别.md`
- `docs/功能模块/任务关联.md`
- （必要时更新 `docs/公共能力/架构总览.md` 或 `docs/README.md`）
