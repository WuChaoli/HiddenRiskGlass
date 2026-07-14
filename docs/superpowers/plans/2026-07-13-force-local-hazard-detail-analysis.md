# 强制本地隐患详情分析开关 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 增加默认开启的配置开关，使本地标签规则命中后在有网和无网环境都使用本地隐患详情，不请求 `/ai/deep`。

**Architecture:** 配置字段进入现有 `AiInspectionConfig`/override/merge 链路；纯函数 `LocalHazardDetailRouteDecider` 将开关置于网络判断之前；`AiInspectionActivity` 只负责传入配置值并执行既有本地或在线展示分支。沿用本地详情来源标记，保持现有禁止保存策略。

**Tech Stack:** Kotlin、Android Gradle Plugin、JUnit 4、JSONC assets

## Global Constraints

- 开关名固定为 `forceLocalHazardDetailAnalysis`，代码默认值和基础 JSONC 值均为 `true`。
- 仅影响 `LOCAL_TRIGGER` 标签组合命中后的详情分析。
- 开启且本地详情不可用时返回 `UNAVAILABLE`，不得调用 `/ai/deep`。
- 关闭时保持有网远端、无网本地的原行为。
- 不修改手动深度分析、隐患拍照、设备指引、四类规则、`info.json` 或流式格式。
- 保留工作区内用户已有的未提交改动，只编辑本计划明确列出的文件。

---

### Task 1: 配置开关加载与覆盖

**Files:**
- Modify: `app/src/test/java/com/rokid/glass/config/InspectionConfigRepositoryTest.kt`
- Modify: `app/src/main/java/com/rokid/glass/config/InspectionAppConfig.kt`
- Modify: `app/src/main/java/com/rokid/glass/config/InspectionConfigRepository.kt`
- Modify: `app/src/main/assets/inspection_config.base.jsonc`

**Interfaces:**
- Produces: `AiInspectionConfig.forceLocalHazardDetailAnalysis: Boolean`
- Produces: `AiInspectionConfigOverride.forceLocalHazardDetailAnalysis: Boolean?`

- [x] **Step 1: 写配置失败测试**

在 `InspectionConfigRepositoryTest` 增加三个断言：空配置默认 `true`、base 可配置为 `false`、overlay 的 `true` 可覆盖 base 的 `false`。

```kotlin
@Test
fun forceLocalHazardDetailAnalysisDefaultsToEnabled() {
    val config = InspectionConfigRepository.buildConfig("{}", null)
    assertTrue(config.aiInspection.forceLocalHazardDetailAnalysis)
}

@Test
fun forceLocalHazardDetailAnalysisCanBeDisabled() {
    val config = InspectionConfigRepository.buildConfig(
        """{"aiInspection":{"forceLocalHazardDetailAnalysis":false}}""",
        null,
    )
    assertFalse(config.aiInspection.forceLocalHazardDetailAnalysis)
}

@Test
fun overlayOverridesForceLocalHazardDetailAnalysis() {
    val config = InspectionConfigRepository.buildConfig(
        """{"aiInspection":{"forceLocalHazardDetailAnalysis":false}}""",
        """{"aiInspection":{"forceLocalHazardDetailAnalysis":true}}""",
    )
    assertTrue(config.aiInspection.forceLocalHazardDetailAnalysis)
}
```

- [x] **Step 2: 运行配置测试并确认 RED**

Run: `powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.config.InspectionConfigRepositoryTest"`

Expected: Kotlin 编译失败，提示 `Unresolved reference: forceLocalHazardDetailAnalysis`。

- [x] **Step 3: 实现最小配置链路**

在 `AiInspectionConfig` 增加：

```kotlin
val forceLocalHazardDetailAnalysis: Boolean = true,
```

在 `AiInspectionConfigOverride` 增加：

```kotlin
val forceLocalHazardDetailAnalysis: Boolean? = null,
```

在 repository 的 `merge(AiInspectionConfig, AiInspectionConfigOverride?)` 构造参数中增加：

```kotlin
forceLocalHazardDetailAnalysis =
    override?.forceLocalHazardDetailAnalysis ?: base.forceLocalHazardDetailAnalysis,
```

在基础 JSONC 的 `aiInspection` 节点增加：

```jsonc
// 是否强制使用本地隐患详情。开启后本地标签命中时不调用 /ai/deep。
"forceLocalHazardDetailAnalysis": true,
```

- [x] **Step 4: 运行配置测试并确认 GREEN**

Run: `powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.config.InspectionConfigRepositoryTest"`

Expected: `BUILD SUCCESSFUL`，该测试类全部通过。

### Task 2: 强制本地详情路由

**Files:**
- Modify: `app/src/test/java/com/rokid/glass/hiddenrisk/LocalHazardDetailRouteDeciderTest.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/LocalHazardDetailRouteDecider.kt`
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/AiInspectionActivity.kt`

**Interfaces:**
- Consumes: `AiInspectionConfig.forceLocalHazardDetailAnalysis: Boolean`
- Produces: `LocalHazardDetailRouteDecider.initial(forceLocalAnalysis: Boolean, networkAvailable: Boolean, localFallbackAvailable: Boolean): InitialRoute`

- [x] **Step 1: 写路由失败测试**

把现有 `initial` 测试调用补上 `forceLocalAnalysis = false`，并增加：

```kotlin
@Test
fun forcedLocalUsesLocalWhileOnline() = assertEquals(
    LocalHazardDetailRouteDecider.InitialRoute.LOCAL,
    LocalHazardDetailRouteDecider.initial(true, true, true),
)

@Test
fun forcedLocalUsesLocalWhileOffline() = assertEquals(
    LocalHazardDetailRouteDecider.InitialRoute.LOCAL,
    LocalHazardDetailRouteDecider.initial(true, false, true),
)

@Test
fun forcedLocalNeverUsesRemoteWithoutKnowledge() = assertEquals(
    LocalHazardDetailRouteDecider.InitialRoute.UNAVAILABLE,
    LocalHazardDetailRouteDecider.initial(true, true, false),
)
```

- [x] **Step 2: 运行路由测试并确认 RED**

Run: `powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.LocalHazardDetailRouteDeciderTest"`

Expected: Kotlin 编译失败，提示 `initial` 参数数量不匹配。

- [x] **Step 3: 实现最小路由决策**

将函数改为：

```kotlin
fun initial(
    forceLocalAnalysis: Boolean,
    networkAvailable: Boolean,
    localFallbackAvailable: Boolean,
): InitialRoute = when {
    forceLocalAnalysis && localFallbackAvailable -> InitialRoute.LOCAL
    forceLocalAnalysis -> InitialRoute.UNAVAILABLE
    networkAvailable -> InitialRoute.REMOTE
    localFallbackAvailable -> InitialRoute.LOCAL
    else -> InitialRoute.UNAVAILABLE
}
```

在 `AiInspectionActivity.queueAutoDetectedOnlineHazardPresentation` 的唯一调用点传入：

```kotlin
forceLocalAnalysis = InspectionConfigRepository.get()
    .aiInspection
    .forceLocalHazardDetailAnalysis,
```

- [x] **Step 4: 运行路由测试并确认 GREEN**

Run: `powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest --tests "com.rokid.glass.hiddenrisk.LocalHazardDetailRouteDeciderTest"`

Expected: `BUILD SUCCESSFUL`，强制本地和原有关闭行为测试全部通过。

### Task 3: 回归验证与文档同步

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/hiddenrisk/README.md`
- Modify: `docs/CODEMAPS.md`

**Interfaces:**
- Consumes: Tasks 1-2 的最终配置及路由行为。
- Produces: 与实际默认路由一致的模块说明和跨模块数据流说明。

- [x] **Step 1: 更新现有链路说明**

把文档中“有网复用 `/ai/deep`”更新为：默认开启强制本地详情开关；开启时不论网络均解析 `info.json`，关闭后才恢复有网 `/ai/deep`、无网本地的策略。保留本地详情禁止 `pushHidDanger` 的说明。

- [x] **Step 2: 运行完整 standard 单元测试**

Run: `powershell -ExecutionPolicy Bypass -File scripts/android/gradle.ps1 :app:testStandardDebugUnitTest`

Expected: `BUILD SUCCESSFUL`，0 个失败测试。

- [x] **Step 3: 运行 standard debug 构建**

Run: `powershell -ExecutionPolicy Bypass -File scripts/android/build-debug.ps1`

Expected: `BUILD SUCCESSFUL`，生成 `app/build/outputs/apk/standard/debug/app-standard-debug.apk`。

- [x] **Step 4: 检查差异边界**

Run: `git diff --check`

Expected: 无输出，退出码为 0。随后使用 `git diff -- <本计划文件列表>` 核对每一行变更均服务于本需求，不暂存或提交用户已有的其他改动。
