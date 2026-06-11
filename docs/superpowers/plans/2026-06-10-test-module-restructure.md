# test/ 目录两级结构改造 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 test/ 从按日期分类改造为 unit/integration/e2e × 功能模块的两级结构

**Architecture:** 物理移动证据文件夹 + 生成结构化 TEST_CASES.md 索引 + 重写 README 导航体系。零数据丢失，原摘要备份到 archived/。

**Tech Stack:** Bash (git mv, mkdir), Markdown

---

## File Structure

### 将创建的目录
- `test/unit/{hiddenrisk,component,workflow,updater,camera,input,config,utils,network,data}/evidence/`
- `test/integration/{hiddenrisk,updater,app,component,workflow,camera,input,config,utils,network,data}/evidence/`
- `test/e2e/{menu,app,hiddenrisk,updater,component,workflow,camera,input,config,utils,network,data}/evidence/`
- `test/tools/`
- `test/archived/`

### 将创建的文件
- `test/unit/*/TEST_CASES.md` (8 个) — 索引 `app/src/test/` 的单元测试类
- `test/integration/*/TEST_CASES.md` (3 个有证据 + 8 个空结构)
- `test/e2e/*/TEST_CASES.md` (2 个有证据 + 8 个空结构)
- `test/{unit,integration,e2e}/README.md`
- `test/README.md`
- `test/archived/README.legacy.md`

### 将移动的文件
- 10 个日期证据文件夹 → 对应 `evidence/` 子目录
- `test/image_to_base64.py` → `test/tools/image_to_base64.py`

---

## Task 1: 创建目录骨架

**Files:**
- Create: `test/unit/*/` (8 个模块目录)
- Create: `test/integration/*/` (11 个模块目录)
- Create: `test/e2e/*/` (11 个模块目录)
- Create: `test/tools/`
- Create: `test/archived/`

- [ ] **Step 1: 创建所有目录**

Run:
```bash
# unit 层 (8 个模块)
mkdir -p test/unit/{hiddenrisk,component,workflow,updater,camera,input,config,utils}/evidence

# integration 层 (11 个模块，含 app + network + data)
mkdir -p test/integration/{hiddenrisk,updater,app,component,workflow,camera,input,config,utils,network,data}/evidence

# e2e 层 (11 个模块)
mkdir -p test/e2e/{menu,app,hiddenrisk,updater,component,workflow,camera,input,config,utils,network,data}/evidence

# 工具和归档
mkdir -p test/tools test/archived
```

Expected: 所有目录成功创建，无报错。

- [ ] **Step 2: Commit 目录骨架**

```bash
git add test/
git commit -m "chore(test): 创建 unit/integration/e2e × 模块的两级目录骨架

按设计文档预创建所有模块子目录和 evidence/ 文件夹。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: 移动现有证据文件夹

**Files:**
- Move: `test/2026-05-07_hiddenrisk_logcat_timing` → `test/integration/hiddenrisk/evidence/`
- Move: `test/2026-05-19_apk_update_device_smoke` → `test/integration/updater/evidence/`
- Move: `test/2026-05-13_app_file_logger` → `test/integration/app/evidence/`
- Move: `test/2026-05-15_logcat_error_check` → `test/e2e/app/evidence/`
- Move: `test/2026-04-30_descrip_menu_visibility` → `test/e2e/menu/evidence/`
- Move: `test/2026-05-19_menu_scroll_bounds` → `test/e2e/menu/evidence/`
- Move: `test/2026-05-19_menu_update_prompt_and_confirm` → `test/e2e/menu/evidence/`
- Move: `test/2026-05-19_menu_confirm_focus_fix` → `test/e2e/menu/evidence/`
- Move: `test/2026-06-10_app_visibility` → `test/e2e/app/evidence/`
- Move: `test/2026-06-10_boot_auto_start` → `test/e2e/app/evidence/`
- Move: `test/image_to_base64.py` → `test/tools/image_to_base64.py`

- [ ] **Step 1: 使用 git mv 移动证据文件夹**

Run:
```bash
git mv test/2026-05-07_hiddenrisk_logcat_timing       test/integration/hiddenrisk/evidence/
git mv test/2026-05-19_apk_update_device_smoke        test/integration/updater/evidence/
git mv test/2026-05-13_app_file_logger                test/integration/app/evidence/
git mv test/2026-05-15_logcat_error_check             test/e2e/app/evidence/
git mv test/2026-04-30_descrip_menu_visibility        test/e2e/menu/evidence/
git mv test/2026-05-19_menu_scroll_bounds             test/e2e/menu/evidence/
git mv test/2026-05-19_menu_update_prompt_and_confirm test/e2e/menu/evidence/
git mv test/2026-05-19_menu_confirm_focus_fix         test/e2e/menu/evidence/
git mv test/2026-06-10_app_visibility                 test/e2e/app/evidence/
git mv test/2026-06-10_boot_auto_start                test/e2e/app/evidence/
git mv test/image_to_base64.py                        test/tools/
```

Expected: `git status` 显示为 `renamed` (R) 而非 `deleted` + `untracked`。

- [ ] **Step 2: Commit 移动操作**

```bash
git add test/
git commit -m "refactor(test): 按两级结构移动现有证据文件夹

- integration/hiddenrisk: 2026-05-07_hiddenrisk_logcat_timing
- integration/updater: 2026-05-19_apk_update_device_smoke
- integration/app: 2026-05-13_app_file_logger
- e2e/menu: 4 个菜单相关证据
- e2e/app: 2026-06-10_app_visibility, 2026-06-10_boot_auto_start, 2026-05-15_logcat_error_check
- tools: image_to_base64.py

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 3: 备份原 README

**Files:**
- Create: `test/archived/README.legacy.md`
- Delete: `test/README.md` (旧版将在 Task 9 被新版覆盖)

- [ ] **Step 1: 备份原 test/README.md**

Run:
```bash
cp test/README.md test/archived/README.legacy.md
git add test/archived/README.legacy.md
git commit -m "chore(test): 备份原 test/README.md 到 archived/README.legacy.md

保留历史测试摘要，供 TEST_CASES.md 提取用。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 4: 生成 unit/ 层 TEST_CASES.md

**Files:**
- Create: `test/unit/hiddenrisk/TEST_CASES.md`
- Create: `test/unit/component/TEST_CASES.md`
- Create: `test/unit/workflow/TEST_CASES.md`
- Create: `test/unit/updater/TEST_CASES.md`
- Create: `test/unit/camera/TEST_CASES.md`
- Create: `test/unit/input/TEST_CASES.md`
- Create: `test/unit/config/TEST_CASES.md`
- Create: `test/unit/utils/TEST_CASES.md`

- [ ] **Step 1: 生成 unit/hiddenrisk/TEST_CASES.md**

Create `test/unit/hiddenrisk/TEST_CASES.md`:

```markdown
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
```

- [ ] **Step 2: 生成 unit/component/TEST_CASES.md**

Create `test/unit/component/TEST_CASES.md`:

```markdown
# TEST_CASES: unit/component

> 本模块单元测试索引。测试代码位于 `app/src/test/java/com/rokid/glass/component/`。

## 测试类清单

| 编号 | 测试类 | 测试目标 | 对应证据 |
|-----|--------|---------|---------|
| UNIT-COMP-001 | `InspectionPromptLayoutVisibilityTest` | 巡检提示布局可见性 | -- |
| UNIT-COMP-002 | `StatusAlertOverlayLayoutTest` | 状态告警覆盖层布局 | -- |
| UNIT-COMP-003 | `StatusAlertStateMachineTest` | 状态告警状态机 | -- |

## 执行方式

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.component.*"
```
```

- [ ] **Step 3: 生成 unit/workflow/TEST_CASES.md**

Create `test/unit/workflow/TEST_CASES.md`:

```markdown
# TEST_CASES: unit/workflow

> 本模块单元测试索引。测试代码位于 `app/src/test/java/com/rokid/glass/workflow/`。

## 测试类清单

| 编号 | 测试类 | 测试目标 | 对应证据 |
|-----|--------|---------|---------|
| UNIT-WORKFLOW-001 | `InspectionWorkflowSessionTest` | 巡检工作流会话 | -- |

## 执行方式

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.workflow.*"
```
```

- [ ] **Step 4: 生成 unit/updater/TEST_CASES.md**

Create `test/unit/updater/TEST_CASES.md`:

```markdown
# TEST_CASES: unit/updater

> 本模块单元测试索引。测试代码位于 `app/src/test/java/com/rokid/glass/updater/`。

## 测试类清单

| 编号 | 测试类 | 测试目标 | 对应证据 |
|-----|--------|---------|---------|
| UNIT-UPDATER-001 | `AppUpdatePromptSelectionTest` | 更新提示选择逻辑 | -- |

## 执行方式

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.updater.*"
```
```

- [ ] **Step 5: 生成 unit/camera/TEST_CASES.md**

Create `test/unit/camera/TEST_CASES.md`:

```markdown
# TEST_CASES: unit/camera

> 本模块单元测试索引。测试代码位于 `app/src/test/java/com/rokid/glass/camera/`。

## 测试类清单

| 编号 | 测试类 | 测试目标 | 对应证据 |
|-----|--------|---------|---------|
| UNIT-CAMERA-001 | `RokidFrameSourceTest` | Rokid 帧源 | -- |

## 执行方式

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.camera.*"
```
```

- [ ] **Step 6: 生成 unit/input/TEST_CASES.md**

Create `test/unit/input/TEST_CASES.md`:

```markdown
# TEST_CASES: unit/input

> 本模块单元测试索引。测试代码位于 `app/src/test/java/com/rokid/glass/input/`。

## 测试类清单

| 编号 | 测试类 | 测试目标 | 对应证据 |
|-----|--------|---------|---------|
| UNIT-INPUT-001 | `AutoSleepStateMachineTest` | 自动休眠状态机 | -- |
| UNIT-INPUT-002 | `UnifiedInputSessionTriggerTest` | 统一输入会话触发器 | -- |

## 执行方式

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.input.*"
```
```

- [ ] **Step 7: 生成 unit/config/TEST_CASES.md**

Create `test/unit/config/TEST_CASES.md`:

```markdown
# TEST_CASES: unit/config

> 本模块单元测试索引。测试代码位于 `app/src/test/java/com/rokid/glass/config/`。

## 测试类清单

| 编号 | 测试类 | 测试目标 | 对应证据 |
|-----|--------|---------|---------|
| UNIT-CONFIG-001 | `InspectionConfigRepositoryTest` | 巡检配置仓库 | -- |

## 执行方式

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.config.*"
```
```

- [ ] **Step 8: 生成 unit/utils/TEST_CASES.md**

Create `test/unit/utils/TEST_CASES.md`:

```markdown
# TEST_CASES: unit/utils

> 本模块单元测试索引。测试代码位于 `app/src/test/java/com/rokid/glass/utils/`。

## 测试类清单

| 编号 | 测试类 | 测试目标 | 对应证据 |
|-----|--------|---------|---------|
| UNIT-UTILS-001 | `BitmapUtilsTest` | Bitmap 工具 | -- |

## 执行方式

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.utils.*"
```
```

- [ ] **Step 9: Commit unit 层 TEST_CASES.md**

```bash
git add test/unit/
git commit -m "docs(test): 为 unit 层各模块生成 TEST_CASES.md

索引 app/src/test/ 下 40+ 个单元测试类，按模块归类。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 5: 生成 integration/ 层 TEST_CASES.md

**Files:**
- Create: `test/integration/hiddenrisk/TEST_CASES.md`
- Create: `test/integration/updater/TEST_CASES.md`
- Create: `test/integration/app/TEST_CASES.md`
- Create: `test/integration/*/TEST_CASES.md` (其余 8 个模块，空结构)

- [ ] **Step 1: 生成 integration/hiddenrisk/TEST_CASES.md**

Create `test/integration/hiddenrisk/TEST_CASES.md`:

```markdown
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
```

- [ ] **Step 2: 生成 integration/updater/TEST_CASES.md**

Create `test/integration/updater/TEST_CASES.md`:

```markdown
# TEST_CASES: integration/updater

> 本模块集成测试索引。每条用例对应 `evidence/` 下的一个日期文件夹。

## 用例清单

| 编号 | 用例名称 | 目标 | 状态 | 最新证据 |
|-----|---------|------|------|---------|
| INTEG-UPDATER-001 | APK 热更新全链路 | 验证局域网 APK 热更新的服务器、检查、提示、下载安装流程 | ✅ 已通过 | `evidence/2026-05-19_apk_update_device_smoke/` |

## 用例详情

### INTEG-UPDATER-001: APK 热更新全链路

- **触发条件**: 启动 App，触发自动版本检查或手动检查更新
- **预期结果**: 命中新版本 → 提示页 → 下载 → 系统安装器拉起
- **验证方式**: 真机 + 局域网更新服务器 + adb logcat/activity/window 状态确认
- **关联代码**: `AppUpdateChecker`, `AppUpdatePromptActivity`, `AppUpdateDownloadService`
- **回归风险**: 高（涉及文件下载、FileProvider、系统安装器交互）
```

- [ ] **Step 3: 生成 integration/app/TEST_CASES.md**

Create `test/integration/app/TEST_CASES.md`:

```markdown
# TEST_CASES: integration/app

> 本模块集成测试索引。每条用例对应 `evidence/` 下的一个日期文件夹。

## 用例清单

| 编号 | 用例名称 | 目标 | 状态 | 最新证据 |
|-----|---------|------|------|---------|
| INTEG-APP-001 | 应用文件日志验证 | 验证应用文件日志组件正常工作 | ✅ 已通过 | `evidence/2026-05-13_app_file_logger/` |

## 用例详情

### INTEG-APP-001: 应用文件日志验证

- **触发条件**: 应用运行期间产生日志
- **预期结果**: 日志文件正确写入磁盘，格式完整
- **验证方式**: 检查应用私有目录下的日志文件
- **关联代码**: 文件日志组件
- **回归风险**: 低
```

- [ ] **Step 4: 生成其余空模块的 TEST_CASES.md**

对于 `test/integration/{component,workflow,camera,input,config,utils,network,data}/TEST_CASES.md`，分别创建相同结构的空模板：

```markdown
# TEST_CASES: integration/<模块名>

> 本模块集成测试索引。暂无测试用例，等待填充。

## 用例清单

| 编号 | 用例名称 | 目标 | 状态 | 最新证据 |
|-----|---------|------|------|---------|
| -- | -- | -- | -- | -- |

## 用例详情

暂无。
```

- [ ] **Step 5: Commit integration 层**

```bash
git add test/integration/
git commit -m "docs(test): 为 integration 层各模块生成 TEST_CASES.md

- hiddenrisk: 链路时序分析
- updater: APK 热更新全链路
- app: 文件日志验证
- 其余模块: 预留空结构

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 6: 生成 e2e/ 层 TEST_CASES.md

**Files:**
- Create: `test/e2e/menu/TEST_CASES.md`
- Create: `test/e2e/app/TEST_CASES.md`
- Create: `test/e2e/*/TEST_CASES.md` (其余 9 个模块，空结构)

- [ ] **Step 1: 生成 e2e/menu/TEST_CASES.md**

Create `test/e2e/menu/TEST_CASES.md`:

```markdown
# TEST_CASES: e2e/menu

> 本模块 E2E 测试索引。每条用例对应 `evidence/` 下的一个日期文件夹。

## 用例清单

| 编号 | 用例名称 | 目标 | 状态 | 最新证据 |
|-----|---------|------|------|---------|
| E2E-MENU-001 | 菜单滑动边界校准 | 验证选中卡片实际边界滑动，首尾索引不变时不继续滚动 | ✅ 已通过 | `evidence/2026-05-19_menu_scroll_bounds/` |
| E2E-MENU-002 | 更新提示弹窗去重 | 验证自动弹窗取消后生命周期内不再弹出 | ✅ 已通过 | `evidence/2026-05-19_menu_update_prompt_and_confirm/` |
| E2E-MENU-003 | 菜单确认焦点修复 | 验证菜单确认只走统一输入，避免 RecyclerView 子项抢焦点 | ✅ 已通过 | `evidence/2026-05-19_menu_confirm_focus_fix/` |
| E2E-MENU-004 | 描述菜单可见性 | 验证 detecting/descrip 菜单显隐状态正确 | ✅ 已通过 | `evidence/2026-04-30_descrip_menu_visibility/` |

## 用例详情

### E2E-MENU-001: 菜单滑动边界校准

- **触发条件**: 用户在主菜单左右滑动选中卡片
- **预期结果**: 按实际边界校准横向滚动，首尾索引不变时不继续滚动
- **验证方式**: 真机人工确认 + logcat 崩溃扫描
- **关联代码**: `MenuCardAdapter`, `AiInspectionMenuActivity`
- **回归风险**: 中（RecyclerView 滚动逻辑）

### E2E-MENU-002: 更新提示弹窗去重

- **触发条件**: 启动企业扫码页后自动触发更新检查
- **预期结果**: 首次弹出更新提示，返回后再次进入不再自动弹出
- **验证方式**: 真机人工确认 + logcat 验证
- **关联代码**: `AiInspectionMenuActivity`, `AppUpdateChecker`
- **回归风险**: 低

### E2E-MENU-003: 菜单确认焦点修复

- **触发条件**: 用户在菜单上点击确认
- **预期结果**: 菜单确认只走统一输入路径，不会触发中间卡片的 click 回调
- **验证方式**: 真机人工确认 + logcat 崩溃扫描
- **关联代码**: `MenuCardAdapter`, `UnifiedInput`
- **回归风险**: 中（焦点和输入分发）

### E2E-MENU-004: 描述菜单可见性

- **触发条件**: 进入 detecting/descrip 状态
- **预期结果**: 对应菜单正确显示或隐藏
- **验证方式**: 真机截图确认
- **关联代码**: `AiInspectionMenuActivity`
- **回归风险**: 低
```

- [ ] **Step 2: 生成 e2e/app/TEST_CASES.md**

Create `test/e2e/app/TEST_CASES.md`:

```markdown
# TEST_CASES: e2e/app

> 本模块 E2E 测试索引。每条用例对应 `evidence/` 下的一个日期文件夹。

## 用例清单

| 编号 | 用例名称 | 目标 | 状态 | 最新证据 |
|-----|---------|------|------|---------|
| E2E-APP-001 | 应用可见性持久化 | 验证隐藏远程协作及其他业务应用，并在三轮息屏亮屏后保持 | ✅ 已通过 | `evidence/2026-06-10_app_visibility/` |
| E2E-APP-002 | 开机自动启动 | 验证应用写入 persist.vendor.boot.pkg 后重启可自动拉起 | ✅ 已通过 | `evidence/2026-06-10_boot_auto_start/` |
| E2E-APP-003 | 全局错误扫描 | 验证 logcat 中无未处理的 FATAL EXCEPTION | ✅ 已通过 | `evidence/2026-05-15_logcat_error_check/` |

## 用例详情

### E2E-APP-001: 应用可见性持久化

- **触发条件**: 应用启动，配置 GlassAppConfig 隐藏指定应用
- **预期结果**: 目标应用在三轮息屏亮屏后仍保持隐藏，Launcher 未恢复
- **验证方式**: 真机人工确认 + logcat 验证延迟配置回调
- **关联代码**: `MyApplication`, `RokidSdkManager`, `AppVisibilityRefreshScheduler`
- **回归风险**: 高（Launcher 交互、系统属性）

### E2E-APP-002: 开机自动启动

- **触发条件**: 设备重启
- **预期结果**: RokidLauncher 自动拉起本应用，进入既定启动流程
- **验证方式**: 设备重启 + adb 属性回读 + activity 状态确认
- **关联代码**: `MyApplication`, `AiInspectionMenuActivity`, `EnterpriseQrScanActivity`
- **回归风险**: 高（系统启动流程）

### E2E-APP-003: 全局错误扫描

- **触发条件**: 应用运行期间
- **预期结果**: logcat 中无 FATAL EXCEPTION 或未处理崩溃
- **验证方式**: logcat 抓取 + 关键词过滤
- **关联代码**: 全局
- **回归风险**: 中
```

- [ ] **Step 3: 生成其余空模块的 TEST_CASES.md**

对于 `test/e2e/{hiddenrisk,updater,component,workflow,camera,input,config,utils,network,data}/TEST_CASES.md`，分别创建相同结构的空模板：

```markdown
# TEST_CASES: e2e/<模块名>

> 本模块 E2E 测试索引。暂无测试用例，等待填充。

## 用例清单

| 编号 | 用例名称 | 目标 | 状态 | 最新证据 |
|-----|---------|------|------|---------|
| -- | -- | -- | -- | -- |

## 用例详情

暂无。
```

- [ ] **Step 4: Commit e2e 层**

```bash
git add test/e2e/
git commit -m "docs(test): 为 e2e 层各模块生成 TEST_CASES.md

- menu: 4 个用例（滑动边界、更新弹窗、焦点修复、菜单可见性）
- app: 3 个用例（可见性、开机自启、错误扫描）
- 其余模块: 预留空结构

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 7: 生成各层 README.md

**Files:**
- Create: `test/unit/README.md`
- Create: `test/integration/README.md`
- Create: `test/e2e/README.md`

- [ ] **Step 1: 生成 test/unit/README.md**

Create `test/unit/README.md`:

```markdown
# Unit 单元测试

JVM 单测，不依赖 Android 运行时。

## 覆盖模块

- [hiddenrisk/](hiddenrisk/) — 隐患识别核心（25+ 测试类）
- [component/](component/) — UI 组件
- [workflow/](workflow/) — 业务上下文
- [updater/](updater/) — 应用更新
- [camera/](camera/) — 相机帧流
- [input/](input/) — 统一输入
- [config/](config/) — 配置系统
- [utils/](utils/) — 工具库

## 执行方式

```bash
# 全部单元测试
./gradlew :app:testStandardDebugUnitTest

# 指定模块
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.*"
```
```

- [ ] **Step 2: 生成 test/integration/README.md**

Create `test/integration/README.md`:

```markdown
# Integration 集成测试

验证组件间协作、真机链路行为，非完整用户场景。

## 覆盖模块

- [hiddenrisk/](hiddenrisk/) — 隐患识别链路时序
- [updater/](updater/) — APK 热更新全链路
- [app/](app/) — 应用级文件日志
- (其他模块预留)

## 执行方式

1. 构建 debug APK：`bash scripts/android/build-debug.sh`
2. 安装：`bash scripts/android/install-debug.sh -s <serial>`
3. 按各 `TEST_CASES.md` 中的验证步骤执行
4. 证据归档到对应模块的 `evidence/YYYY-MM-DD_<描述>/`
```

- [ ] **Step 3: 生成 test/e2e/README.md**

Create `test/e2e/README.md`:

```markdown
# E2E 端到端测试

验证完整用户场景，从 Launcher 到功能结束的全链路。

## 覆盖模块

- [menu/](menu/) — 主菜单、二级菜单交互
- [app/](app/) — 应用级行为（自启、可见性、全局错误）
- (其他模块预留)

## 执行方式

1. 构建 debug APK：`bash scripts/android/build-debug.sh`
2. 安装：`bash scripts/android/install-debug.sh -s <serial>`
3. 按各 `TEST_CASES.md` 中的验证步骤执行
4. 证据归档到对应模块的 `evidence/YYYY-MM-DD_<描述>/`
```

- [ ] **Step 4: Commit 层 README**

```bash
git add test/unit/README.md test/integration/README.md test/e2e/README.md
git commit -m "docs(test): 为 unit/integration/e2e 各层生成 README.md

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 8: 生成顶层 README.md

**Files:**
- Create: `test/README.md`

- [ ] **Step 1: 生成 test/README.md**

Create `test/README.md`:

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
```

- [ ] **Step 2: Commit 顶层 README**

```bash
git add test/README.md
git commit -m "docs(test): 生成顶层 test/README.md 导航索引

提供测试体系总览、模块索引和新增测试流程规范。

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 9: 清理与验证

- [ ] **Step 1: 检查无遗留空目录**

Run:
```bash
find test -type d -empty
```

Expected: 仅显示新创建的空 `evidence/` 目录（这是预期的）。如果有 `test/2026-*` 残留，手动删除。

- [ ] **Step 2: 验证 git status**

Run:
```bash
git status
```

Expected:
- `test/archived/README.legacy.md` — new file
- `test/README.md` — modified
- `test/unit/`, `test/integration/`, `test/e2e/` 下的新文件
- 不应有任何 `deleted: test/2026-*`（应为 renamed）
- 不应有未跟踪的 `test/2026-*` 文件夹

- [ ] **Step 3: 文件完整性检查**

Run:
```bash
# 确认 10 个证据文件夹已移动
echo "=== integration/hiddenrisk ===" && ls test/integration/hiddenrisk/evidence/
echo "=== integration/updater ===" && ls test/integration/updater/evidence/
echo "=== integration/app ===" && ls test/integration/app/evidence/
echo "=== e2e/menu ===" && ls test/e2e/menu/evidence/
echo "=== e2e/app ===" && ls test/e2e/app/evidence/
```

Expected: 每个目录下显示对应的日期文件夹。

- [ ] **Step 4: 最终验证提交**

```bash
git status
```

确认工作树干净后，如还有未提交更改：

```bash
git add test/
git commit -m "chore(test): 清理空目录并验证结构完整性

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## 验收标准

- [ ] 所有原有日期文件夹已移动到正确位置，无遗留
- [ ] 每个有证据的模块都有 `TEST_CASES.md`，用例编号连续、可追溯到证据文件夹
- [ ] 顶层 `test/README.md` 可正确导航到所有模块用例
- [ ] `test/archived/README.legacy.md` 完整保留原摘要
- [ ] `git status` 中证据文件夹显示为 rename 而非 delete+add
- [ ] 工作树最终状态干净
