# 配置化应用可见性白名单实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过 JSONC 配置控制眼镜端系统应用可见性，支持 `FULL`（全显）和 `MINIMAL`（仅隐患巡检+扫一扫）两种模式。

**Architecture:** 在现有 `InspectionConfigRepository` JSONC 配置系统中新增 `appVisibility` 段，`AppVisibilityConfigFactory` 根据配置模式决定向 Rokid SDK 下发的内置应用白名单。standard flavor 默认 `MINIMAL`，base 默认 `FULL` 保持兼容。

**Tech Stack:** Kotlin, Gson, JSONC, JUnit4, Rokid Glass3 SDK

---

## 文件清单

| 文件 | 动作 | 说明 |
|------|------|------|
| `app/src/main/java/com/rokid/glass/config/InspectionAppConfig.kt` | 修改 | 新增 `AppVisibilityMode` 枚举、`AppVisibilityConfig`、`AppVisibilityConfigOverride`，追加到 `InspectionAppConfig` / `InspectionAppConfigOverride` |
| `app/src/main/java/com/rokid/glass/config/InspectionConfigRepository.kt` | 修改 | 新增 `appVisibility` merge 逻辑 |
| `app/src/main/java/com/rokid/glass/hiddenrisk/AppVisibilityConfigFactory.kt` | 修改 | `create()` 改为接收 `InspectionAppConfig`，根据 `mode` 决定内置应用列表 |
| `app/src/main/java/com/rokid/glass/hiddenrisk/RokidSdkManager.kt` | 修改 | `configureAppVisibilityOnce()` 传入当前配置 |
| `app/src/main/assets/inspection_config.base.jsonc` | 修改 | 新增 `appVisibility` 段，默认 `FULL` |
| `app/src/main/assets/inspection_config.standard.jsonc` | 修改 | 覆盖 `mode` 为 `MINIMAL` |
| `app/src/test/java/com/rokid/glass/config/InspectionConfigRepositoryTest.kt` | 修改 | 新增 `appVisibility` 解析测试 |
| `app/src/test/java/com/rokid/glass/hiddenrisk/AppVisibilityConfigFactoryTest.kt` | 修改 | 更新测试以适配新的 `create(config)` 签名 |

---

### Task 1: 新增配置模型

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/config/InspectionAppConfig.kt`

- [ ] **Step 1: 在文件末尾（`InspectionAppConfigOverride` 之前）新增枚举和数据类**

```kotlin
/**
 * 应用可见性模式。
 * 控制眼镜系统应用列表中显示哪些内置应用。
 */
enum class AppVisibilityMode {
    /** 显示所有内置应用 + 第三方应用（调试用）。 */
    FULL,
    /** 仅显示隐患巡检 + 扫一扫（生产用）。 */
    MINIMAL,
}

/**
 * 应用可见性配置。
 */
data class AppVisibilityConfig(
    val mode: AppVisibilityMode = AppVisibilityMode.FULL,
)

/**
 * JSONC 解析用的 nullable override 模型。
 */
data class AppVisibilityConfigOverride(
    val mode: AppVisibilityMode? = null,
)
```

- [ ] **Step 2: 在 `InspectionAppConfig` 中追加 `appVisibility` 字段**

修改前：
```kotlin
data class InspectionAppConfig(
    val featureFlags: FeatureFlagsConfig = FeatureFlagsConfig(),
    val enterpriseScan: EnterpriseScanConfig = EnterpriseScanConfig(),
    val enterpriseInfo: EnterpriseInfoConfig = EnterpriseInfoConfig(),
    val aiInspection: AiInspectionConfig = AiInspectionConfig(),
    val network: NetworkConfig = NetworkConfig(),
)
```

修改后：
```kotlin
data class InspectionAppConfig(
    val featureFlags: FeatureFlagsConfig = FeatureFlagsConfig(),
    val enterpriseScan: EnterpriseScanConfig = EnterpriseScanConfig(),
    val enterpriseInfo: EnterpriseInfoConfig = EnterpriseInfoConfig(),
    val aiInspection: AiInspectionConfig = AiInspectionConfig(),
    val network: NetworkConfig = NetworkConfig(),
    val appVisibility: AppVisibilityConfig = AppVisibilityConfig(),
)
```

- [ ] **Step 3: 在 `InspectionAppConfigOverride` 中追加 `appVisibility` 字段**

修改前：
```kotlin
data class InspectionAppConfigOverride(
    val featureFlags: FeatureFlagsConfigOverride? = null,
    val enterpriseScan: EnterpriseScanConfigOverride? = null,
    val enterpriseInfo: EnterpriseInfoConfigOverride? = null,
    val aiInspection: AiInspectionConfigOverride? = null,
    val network: NetworkConfigOverride? = null,
)
```

修改后：
```kotlin
data class InspectionAppConfigOverride(
    val featureFlags: FeatureFlagsConfigOverride? = null,
    val enterpriseScan: EnterpriseScanConfigOverride? = null,
    val enterpriseInfo: EnterpriseInfoConfigOverride? = null,
    val aiInspection: AiInspectionConfigOverride? = null,
    val network: NetworkConfigOverride? = null,
    val appVisibility: AppVisibilityConfigOverride? = null,
)
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rokid/glass/config/InspectionAppConfig.kt
git commit -m "feat: 新增 AppVisibilityMode 枚举和 AppVisibilityConfig 配置模型

- AppVisibilityMode: FULL / MINIMAL 两种模式
- AppVisibilityConfig / AppVisibilityConfigOverride 数据类
- 追加到 InspectionAppConfig 和 InspectionAppConfigOverride

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: 配置仓库追加 merge 逻辑

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/config/InspectionConfigRepository.kt`

- [ ] **Step 1: 新增 `appVisibility` 的 merge 方法**

在文件中找到 `private fun merge(` 方法群，在最后一个 `merge` 方法之后（`MayHazardVerifyApiConfig` 的 merge 之后）新增：

```kotlin
    private fun merge(
        base: AppVisibilityConfig,
        override: AppVisibilityConfigOverride?,
    ): AppVisibilityConfig {
        return AppVisibilityConfig(
            mode = override?.mode ?: base.mode,
        )
    }
```

- [ ] **Step 2: 在 `InspectionAppConfig` 的 merge 中追加 `appVisibility`**

找到 `merge(base: InspectionAppConfig, override: InspectionAppConfigOverride)` 方法：

修改前：
```kotlin
    private fun merge(
        base: InspectionAppConfig,
        override: InspectionAppConfigOverride,
    ): InspectionAppConfig {
        return InspectionAppConfig(
            featureFlags = merge(base.featureFlags, override.featureFlags),
            enterpriseScan = merge(base.enterpriseScan, override.enterpriseScan),
            enterpriseInfo = merge(base.enterpriseInfo, override.enterpriseInfo),
            aiInspection = merge(base.aiInspection, override.aiInspection),
            network = merge(base.network, override.network),
        )
    }
```

修改后：
```kotlin
    private fun merge(
        base: InspectionAppConfig,
        override: InspectionAppConfigOverride,
    ): InspectionAppConfig {
        return InspectionAppConfig(
            featureFlags = merge(base.featureFlags, override.featureFlags),
            enterpriseScan = merge(base.enterpriseScan, override.enterpriseScan),
            enterpriseInfo = merge(base.enterpriseInfo, override.enterpriseInfo),
            aiInspection = merge(base.aiInspection, override.aiInspection),
            network = merge(base.network, override.network),
            appVisibility = merge(base.appVisibility, override.appVisibility),
        )
    }
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/rokid/glass/config/InspectionConfigRepository.kt
git commit -m "feat: InspectionConfigRepository 支持 appVisibility 配置合并

- 新增 AppVisibilityConfig merge 方法
- 在 InspectionAppConfig merge 中追加 appVisibility 字段

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: 应用可见性工厂改造

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AppVisibilityConfigFactory.kt`
- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/AppVisibilityConfigFactoryTest.kt`

- [ ] **Step 1: 修改 `AppVisibilityConfigFactory.create()` 方法签名和实现**

修改前：
```kotlin
    fun create(): GlassAppConfig = GlassAppConfig(
        supportedBuiltInApps,
        listOf(
            ThirdPartyApp(INSPECTION_APP_PACKAGE, INSPECTION_APP_NAME),
            ThirdPartyApp(SCANNER_APP_PACKAGE, SCANNER_APP_NAME),
        ),
    )
```

修改后：
```kotlin
    fun create(config: InspectionAppConfig = com.rokid.glass.config.InspectionAppConfig()): GlassAppConfig {
        val builtInApps = when (config.appVisibility.mode) {
            com.rokid.glass.config.AppVisibilityMode.FULL -> supportedBuiltInApps
            com.rokid.glass.config.AppVisibilityMode.MINIMAL -> emptyList()
        }
        return GlassAppConfig(
            builtInApps,
            listOf(
                ThirdPartyApp(INSPECTION_APP_PACKAGE, INSPECTION_APP_NAME),
                ThirdPartyApp(SCANNER_APP_PACKAGE, SCANNER_APP_NAME),
            ),
        )
    }
```

> **注意**: 由于 `AppVisibilityConfigFactory` 在 `hiddenrisk` 包，而 `InspectionAppConfig` 在 `config` 包，需要使用全限定名或添加 import。考虑到这个文件目前没有 import `config` 包，建议使用全限定名避免循环 import 风险。

- [ ] **Step 2: 更新单元测试**

修改 `AppVisibilityConfigFactoryTest.kt`，将测试分为两个：

```kotlin
package com.rokid.glass.hiddenrisk

import com.rokid.glass.config.AppVisibilityConfig
import com.rokid.glass.config.AppVisibilityMode
import com.rokid.glass.config.InspectionAppConfig
import com.rokid.security.glass3.sdk.base.data.device.bean.GlassAppType
import org.junit.Assert.assertEquals
import org.junit.Test

class AppVisibilityConfigFactoryTest {

    @Test
    fun `create with FULL mode shows all built-in apps`() {
        val config = AppVisibilityConfigFactory.create(
            InspectionAppConfig(
                appVisibility = AppVisibilityConfig(mode = AppVisibilityMode.FULL),
            ),
        )

        assertEquals(
            listOf(
                GlassAppType.AI_WORK_ASSISTANT,
                GlassAppType.AI_CHAT,
                GlassAppType.AI_INSPECTION,
                GlassAppType.OFFLINE_FACE,
                GlassAppType.OFFLINE_PLATE,
                GlassAppType.TAKE_PHOTO,
                GlassAppType.HG_IDENTIFICATION,
            ),
            config.appList,
        )
        assertEquals(
            listOf(
                AppVisibilityConfigFactory.INSPECTION_APP_PACKAGE,
                AppVisibilityConfigFactory.SCANNER_APP_PACKAGE,
            ),
            requireNotNull(config.thirdApps).map { it.packageName },
        )
    }

    @Test
    fun `create with MINIMAL mode hides all built-in apps`() {
        val config = AppVisibilityConfigFactory.create(
            InspectionAppConfig(
                appVisibility = AppVisibilityConfig(mode = AppVisibilityMode.MINIMAL),
            ),
        )

        assertEquals(emptyList<Any>(), config.appList)
        assertEquals(
            listOf(
                AppVisibilityConfigFactory.INSPECTION_APP_PACKAGE,
                AppVisibilityConfigFactory.SCANNER_APP_PACKAGE,
            ),
            requireNotNull(config.thirdApps).map { it.packageName },
        )
    }

    @Test
    fun `create with default config uses FULL mode`() {
        val config = AppVisibilityConfigFactory.create()

        // 默认配置下应该显示所有内置应用
        assertEquals(7, config.appList?.size ?: 0)
    }
}
```

- [ ] **Step 3: 运行工厂单元测试确认通过**

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.AppVisibilityConfigFactoryTest"
```

Expected: 3 tests PASS

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/AppVisibilityConfigFactory.kt
-git add app/src/test/java/com/rokid/glass/hiddenrisk/AppVisibilityConfigFactoryTest.kt
git commit -m "feat: AppVisibilityConfigFactory 支持根据配置模式过滤内置应用

- create() 改为接收 InspectionAppConfig 参数
- FULL 模式显示所有内置应用，MINIMAL 模式隐藏全部
- 更新单元测试覆盖两种模式

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: SDK Manager 接入配置

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/RokidSdkManager.kt`

- [ ] **Step 1: 修改 `configureAppVisibilityOnce()` 中配置对象的获取**

找到 `configureAppVisibilityOnce()` 方法：

修改前：
```kotlin
        val config = AppVisibilityConfigFactory.create()
```

修改后：
```kotlin
        val config = AppVisibilityConfigFactory.create(
            com.rokid.glass.config.InspectionConfigRepository.get(),
        )
```

> **注意**: 同样使用全限定名引用 `InspectionConfigRepository`，因为 `RokidSdkManager` 在 `hiddenrisk` 包。

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/rokid/glass/hiddenrisk/RokidSdkManager.kt
git commit -m "feat: RokidSdkManager 从配置仓库读取应用可见性配置

- configureAppVisibilityOnce() 传入 InspectionConfigRepository.get()

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: JSONC 配置文件更新

**Files:**
- Modify: `app/src/main/assets/inspection_config.base.jsonc`
- Modify: `app/src/main/assets/inspection_config.standard.jsonc`

- [ ] **Step 1: 在 `inspection_config.base.jsonc` 末尾新增 `appVisibility` 段**

在文件最后一个 `}` 之前（`network` 段结束后，文件末尾的 `}` 之前），插入：

```jsonc
,

  /* 应用可见性配置。
   * 控制眼镜系统应用列表中显示哪些内置应用。
   */
  "appVisibility": {
    // 应用可见性模式。可选值：FULL（显示所有内置应用）| MINIMAL（仅隐患巡检+扫一扫）。
    "mode": "FULL"
  }
```

> **注意**: 注意前面的逗号，确保 JSON 语法正确。

- [ ] **Step 2: 修改 `inspection_config.standard.jsonc` 覆盖为 MINIMAL**

修改前（当前内容）：
```json
{
  /* standard flavor 默认继承 base 配置。
   * 如后续需要为正式版单独覆盖字段，可在此处新增。
   */
}
```

修改后：
```json
{
  /* standard flavor 默认继承 base 配置。
   * 如后续需要为正式版单独覆盖字段，可在此处新增。
   */
  "appVisibility": {
    "mode": "MINIMAL"
  }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/assets/inspection_config.base.jsonc
-git add app/src/main/assets/inspection_config.standard.jsonc
git commit -m "feat: JSONC 配置文件新增 appVisibility 段

- base.jsonc 默认 FULL（向后兼容）
- standard.jsonc 覆盖为 MINIMAL（生产默认值）

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: 配置仓库单元测试

**Files:**
- Modify: `app/src/test/java/com/rokid/glass/config/InspectionConfigRepositoryTest.kt`

- [ ] **Step 1: 新增 `appVisibility` 默认值测试**

在 `InspectionConfigRepositoryTest` 中新增测试：

```kotlin
    @Test
    fun `app visibility defaults to FULL`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = null,
            overlayJsonc = null,
        )

        assertEquals(AppVisibilityMode.FULL, config.appVisibility.mode)
    }
```

- [ ] **Step 2: 新增 `appVisibility` JSONC 覆盖测试**

```kotlin
    @Test
    fun `app visibility can be overridden to MINIMAL from jsonc`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = """
                {
                  "appVisibility": {
                    "mode": "MINIMAL"
                  }
                }
            """.trimIndent(),
            overlayJsonc = null,
        )

        assertEquals(AppVisibilityMode.MINIMAL, config.appVisibility.mode)
    }
```

- [ ] **Step 3: 新增 overlay 覆盖测试**

```kotlin
    @Test
    fun `app visibility can be overridden by overlay`() {
        val config = InspectionConfigRepository.buildConfig(
            baseJsonc = """
                {
                  "appVisibility": {
                    "mode": "FULL"
                  }
                }
            """.trimIndent(),
            overlayJsonc = """
                {
                  "appVisibility": {
                    "mode": "MINIMAL"
                  }
                }
            """.trimIndent(),
        )

        assertEquals(AppVisibilityMode.MINIMAL, config.appVisibility.mode)
    }
```

- [ ] **Step 4: 运行配置仓库单元测试确认通过**

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.config.InspectionConfigRepositoryTest"
```

Expected: 所有测试 PASS（原有 9 个 + 新增 3 个 = 12 个）

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/rokid/glass/config/InspectionConfigRepositoryTest.kt
git commit -m "test: 新增 appVisibility 配置解析单元测试

- 验证默认值 FULL
- 验证 JSONC 覆盖为 MINIMAL
- 验证 overlay 优先级

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: 全量单元测试验证

- [ ] **Step 1: 运行所有相关单元测试**

```bash
./gradlew :app:testStandardDebugUnitTest --tests "com.rokid.glass.config.InspectionConfigRepositoryTest" --tests "com.rokid.glass.hiddenrisk.AppVisibilityConfigFactoryTest"
```

Expected: 全部 PASS

- [ ] **Step 2: 运行整个 standardDebug 单元测试套件（确保无回归）**

```bash
./gradlew :app:testStandardDebugUnitTest
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit（如测试全部通过）**

```bash
git commit --allow-empty -m "chore: 配置化应用可见性白名单功能开发完成

- 支持 FULL / MINIMAL 两种模式
- standard flavor 默认 MINIMAL
- 全部单元测试通过

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review Checklist

### 1. Spec Coverage

| 设计文档要求 | 对应 Task |
|-------------|----------|
| 新增 `AppVisibilityMode` 枚举 | Task 1 |
| 新增 `AppVisibilityConfig` / `AppVisibilityConfigOverride` | Task 1 |
| 追加到 `InspectionAppConfig` / `InspectionAppConfigOverride` | Task 1 |
| `InspectionConfigRepository` merge 逻辑 | Task 2 |
| `AppVisibilityConfigFactory.create(config)` 改造 | Task 3 |
| `RokidSdkManager` 接入 | Task 4 |
| `inspection_config.base.jsonc` 新增段 | Task 5 |
| `inspection_config.standard.jsonc` 覆盖 | Task 5 |
| 单元测试 | Task 3, Task 6, Task 7 |

**无遗漏。**

### 2. Placeholder Scan

- [x] 无 "TBD" / "TODO"
- [x] 无 "implement later" / "fill in details"
- [x] 无 "add appropriate error handling" 等模糊描述
- [x] 每个代码步骤都包含完整代码块
- [x] 每个测试步骤都包含完整断言

### 3. Type Consistency

- [x] `AppVisibilityMode` 枚举值：`FULL`, `MINIMAL` — 全计划一致
- [x] `AppVisibilityConfig.mode` 类型 — 全计划一致
- [x] `create(config: InspectionAppConfig)` 签名 — Task 3 与 Task 4 调用一致
- [x] JSONC 中 `"mode": "FULL"` / `"mode": "MINIMAL"` — 与枚举名称一致

---

## 执行选项

**Plan complete and saved to `docs/superpowers/plans/2026-06-10-app-visibility.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
