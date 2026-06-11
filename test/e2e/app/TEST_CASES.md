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
