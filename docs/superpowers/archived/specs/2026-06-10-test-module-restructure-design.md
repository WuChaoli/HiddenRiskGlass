# Design: test/ 目录两级结构改造

> 状态: 已批准 | 作者: Claude | 日期: 2026-06-10

## 背景

项目根目录 `test/` 目前按日期命名存放真机冒烟测试证据（logcat、截图、dumpsys、README 汇总）。随着测试资产累积，按时间分类导致：
- 同一功能模块的测试分散在不同日期文件夹
- 难以快速定位某模块的历史测试记录
- 单元/集成/E2E 测试边界不清晰

本设计将 `test/` 从「时间分类」改造为「两级功能分类」：一级按测试类型（unit / integration / e2e），二级按功能模块。

## 目标目录结构

```
test/
├── README.md                              # 顶层索引：测试体系总览 + 导航
├── unit/                                  # 单元测试证据/报告
│   ├── README.md                          # unit 层说明：指向 app/src/test/
│   ├── hiddenrisk/
│   │   ├── TEST_CASES.md
│   │   └── evidence/
│   ├── component/
│   │   ├── TEST_CASES.md
│   │   └── evidence/
│   ├── workflow/
│   │   ├── TEST_CASES.md
│   │   └── evidence/
│   ├── updater/
│   │   ├── TEST_CASES.md
│   │   └── evidence/
│   ├── camera/
│   │   ├── TEST_CASES.md
│   │   └── evidence/
│   ├── input/
│   │   ├── TEST_CASES.md
│   │   └── evidence/
│   ├── config/
│   │   ├── TEST_CASES.md
│   │   └── evidence/
│   └── utils/
│       ├── TEST_CASES.md
│       └── evidence/
├── integration/                           # 集成测试（多组件/真机链路）
│   ├── README.md
│   ├── hiddenrisk/
│   │   ├── TEST_CASES.md
│   │   └── evidence/
│   │       └── 2026-05-07_hiddenrisk_logcat_timing/
│   ├── updater/
│   │   ├── TEST_CASES.md
│   │   └── evidence/
│   │       └── 2026-05-19_apk_update_device_smoke/
│   ├── app/
│   │   ├── TEST_CASES.md
│   │   └── evidence/
│   │       └── 2026-05-13_app_file_logger/
│   └── (其他模块...)
├── e2e/                                   # 端到端（完整用户链路）
│   ├── README.md
│   ├── menu/
│   │   ├── TEST_CASES.md
│   │   └── evidence/
│   │       ├── 2026-04-30_descrip_menu_visibility/
│   │       ├── 2026-05-19_menu_scroll_bounds/
│   │       ├── 2026-05-19_menu_update_prompt_and_confirm/
│   │       └── 2026-05-19_menu_confirm_focus_fix/
│   └── app/
│       ├── TEST_CASES.md
│       └── evidence/
│           ├── 2026-06-10_app_visibility/
│           └── 2026-06-10_boot_auto_start/
├── tools/                                 # 测试辅助工具
│   └── image_to_base64.py
└── archived/                              # 历史归档
    └── README.legacy.md                   # 原 test/README.md 备份
```

## 分类规则

### 测试类型判定

| 测试类型 | 判定标准 | 存放内容 |
|---------|---------|---------|
| **unit** | JVM 单测、不依赖 Android 运行时、可 `./gradlew test` 执行 | 单元测试运行报告、覆盖率证据；`TEST_CASES.md` 索引 `app/src/test/` 的测试类 |
| **integration** | 需要真机/模拟器、验证组件间协作、非完整用户链路 | 组件集成验证的证据（logcat、截图、 dumpsys） |
| **e2e** | 完整用户场景、从 Launcher 到功能结束的全链路 | 端到端场景验证的证据 |

### 模块映射

按 `docs/CODEMAPS.md` 的模块划分，evidence 按**被测主体**归属：

| 证据文件夹 | 目标类型 | 目标模块 | 理由 |
|-----------|---------|---------|------|
| `2026-04-30_descrip_menu_visibility` | e2e | menu | 菜单可见性 |
| `2026-05-07_hiddenrisk_logcat_timing` | integration | hiddenrisk | 隐患识别链路时序 |
| `2026-05-13_app_file_logger` | integration | app | 应用级文件日志，跨模块 |
| `2026-05-15_logcat_error_check` | e2e | app | 全局错误扫描 |
| `2026-05-19_apk_update_device_smoke` | integration | updater | APK 热更新链路 |
| `2026-05-19_menu_scroll_bounds` | e2e | menu | 菜单滑动边界 |
| `2026-05-19_menu_update_prompt_and_confirm` | e2e | menu | 菜单更新弹窗+确认 |
| `2026-05-19_menu_confirm_focus_fix` | e2e | menu | 菜单焦点修复 |
| `2026-06-10_app_visibility` | e2e | app | 全局应用可见性 |
| `2026-06-10_boot_auto_start` | e2e | app | 开机自启全链路 |

## 文件规范

### TEST_CASES.md 模板

每个叶子模块下的 `TEST_CASES.md` 统一格式：

```markdown
# TEST_CASES: <测试类型>/<模块名>

> 本模块测试用例索引。每条用例对应 `evidence/` 下的一个日期文件夹或未来的测试执行记录。

## 用例清单

| 编号 | 用例名称 | 目标 | 状态 | 最新证据 |
|-----|---------|------|------|---------|
| <编号> | <名称> | <一句话目标> | <状态> | `evidence/<文件夹>/` |

## 用例详情

### <编号>: <名称>

- **触发条件**: <如何触发>
- **预期结果**: <期望行为>
- **验证方式**: <真机/自动化/人工>
- **关联代码**: <类名/文件>
- **回归风险**: <高/中/低>
```

### 编号规则

```
<类型前缀>-<模块缩写>-<三位序号>

类型前缀: UNIT | INTEG | E2E
模块缩写: MENU | HIDRISK | UPDATER | CAMERA | INPUT | APP | COMP | WORKFLOW | CONFIG | UTILS
示例: E2E-MENU-001, INTEG-HIDRISK-003, UNIT-CONFIG-012
```

### 顶层 README.md

```markdown
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
| updater | -- | [TEST_CASES](integration/updater/TEST_CASES.md) | -- |
| app | -- | [TEST_CASES](integration/app/TEST_CASES.md) | [TEST_CASES](e2e/app/TEST_CASES.md) |
| ... | ... | ... | ... |

## 历史归档

改造前的按日期分类证据备份见 [archived/README.legacy.md](archived/README.legacy.md)。
```

## 迁移计划

### 步骤

1. **备份原 README**：`mkdir -p test/archived && cp test/README.md test/archived/README.legacy.md`
2. **创建目录骨架**：按目标结构预创建所有 `unit/*/evidence/`、`integration/*/evidence/`、`e2e/*/evidence/` 及 `archived/`、`tools/`
3. **移动证据文件夹**：按模块映射表执行 `git mv`，证据内部文件原封不动
4. **移动工具脚本**：`git mv test/image_to_base64.py test/tools/`
5. **生成 TEST_CASES.md**：从 `README.legacy.md` 提取摘要，按模块写入各 `TEST_CASES.md`
6. **生成各层 README.md**：顶层 + unit/integration/e2e 层
7. **验证**：确认无遗留空目录、无丢失文件

### 风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| 移动后 git 历史引用丢失 | 使用 `git mv` 操作，文件历史保留；`README.legacy.md` 备份原摘要 |
| 证据内相对路径失效 | 证据文件夹整体移动，内部无相对路径引用外部文件，不受影响 |
| 遗漏空模块 | 按 `CODEMAPS.md` 的 11 个模块预创建，空模块只留 `TEST_CASES.md` + 空 `evidence/` |

## 验收标准

- [ ] 所有原有日期文件夹已移动到正确位置，无遗留
- [ ] 每个有证据的模块都有 `TEST_CASES.md`，用例编号连续、可追溯到证据文件夹
- [ ] 顶层 `test/README.md` 可正确导航到所有模块用例
- [ ] `test/archived/README.legacy.md` 完整保留原摘要
- [ ] `git status` 显示为 rename/move 而非 delete+add
