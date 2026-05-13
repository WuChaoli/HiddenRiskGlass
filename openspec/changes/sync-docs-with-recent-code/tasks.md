## 1. 隐患识别链路文档同步

- [x] 1.1 更新 `docs/公共能力/隐患识别链路.md`：ctype=1→3、onlineDetectIntervalMs 1000→500ms、detectTimeoutMs 3000→1500ms
- [x] 1.2 补充在线检测并发池描述（上限 5），替换 single-flight 描述
- [x] 1.3 移除 `decideOnlineLoopAdvance` 中 `requestInFlight` 参数的引用

## 2. 隐患识别功能模块文档同步

- [x] 2.1 更新 `docs/功能模块/隐患识别.md` 链路 A 描述：ctype、间隔、超时、并发池
- [x] 2.2 更新关键日志锚点：`ctype=1`→`ctype=3`、新增 `activePoolSize=` 锚点
- [x] 2.3 更新开发检查清单过时项（ctype=1→3、single-flight→并发池）

## 3. 任务关联文档同步

- [x] 3.1 更新 `docs/功能模块/任务关联.md`：EnterpriseInfo 新增 placeCode、lastInspectionDate 字段
- [x] 3.2 补充 `enterprise_info_recent_inspection_time_prefix` 字符串资源记录
- [x] 3.3 更新企业信息页 UI 描述包含"最近巡查时间"

## 4. 配置变体清理记录

- [x] 4.1 确认 `docs/` 中不再引用已删除的 `localHazardDetect.jsonc`、`demoOnlineonly.jsonc` 变体配置
