# Test Evidence & Cases

本项目测试资产按「测试类型 → 功能模块」两级组织。

## 目录导航

| 测试类型 | 路径 | 说明 | 自动化入口 |
|---------|------|------|-----------|
| 单元测试 | [unit/](unit/) | JVM 单测，无 Android 运行时依赖 | `./gradlew :app:testStandardDebugUnitTest` |
| 集成测试 | [integration/](integration/) | 真机组件协作验证 | adb + 人工确认 |
| 端到端测试 | [e2e/](e2e/) | 完整用户场景链路 | adb + 人工确认 |

## 各模块用例索引

| 模块 | unit | integration | e2e |
|------|------|-------------|-----|
| hiddenrisk | [TEST_CASES](unit/hiddenrisk/TEST_CASES.md) | [TEST_CASES](integration/hiddenrisk/TEST_CASES.md) | -- |
| menu | -- | -- | [TEST_CASES](e2e/menu/TEST_CASES.md) |
| updater | [TEST_CASES](unit/updater/TEST_CASES.md) | [TEST_CASES](integration/updater/TEST_CASES.md) | -- |
| app | -- | [TEST_CASES](integration/app/TEST_CASES.md) | [TEST_CASES](e2e/app/TEST_CASES.md) |
| component | [TEST_CASES](unit/component/TEST_CASES.md) | -- | -- |
| workflow | [TEST_CASES](unit/workflow/TEST_CASES.md) | -- | -- |
| camera | [TEST_CASES](unit/camera/TEST_CASES.md) | -- | -- |
| input | [TEST_CASES](unit/input/TEST_CASES.md) | -- | -- |
| config | [TEST_CASES](unit/config/TEST_CASES.md) | -- | -- |
| utils | [TEST_CASES](unit/utils/TEST_CASES.md) | -- | -- |

## 新增测试流程

1. 判定测试类型（unit / integration / e2e）
2. 判定目标模块
3. 在对应路径创建证据文件夹：`evidence/YYYY-MM-DD_<描述>/`
4. 更新对应 `TEST_CASES.md`，按编号规则分配用例编号
5. 如有必要，更新本 README 的模块索引

## 历史归档

改造前的按日期分类证据备份见 [archived/README.legacy.md](archived/README.legacy.md)。

## 工具

- [image_to_base64.py](tools/image_to_base64.py) — 截图转 base64 用于文档内嵌
