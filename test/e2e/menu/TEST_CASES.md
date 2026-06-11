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
