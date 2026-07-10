# 配置化应用可见性白名单设计文档

**日期**: 2026-06-10
**主题**: 通过 JSONC 配置控制眼镜端系统应用可见性
**相关模块**: `config/`, `hiddenrisk/`

## 背景

当前项目通过 Rokid Glass3 SDK 的 `IDeviceService.configureAppVisibility()` 接口控制眼镜系统应用可见性。配置数据由 `AppVisibilityConfigFactory` 硬编码生成，包含 7 个内置应用和 2 个第三方应用。

需求：将除隐患巡检（`com.rokid.glesse`）和扫一扫（`com.rokid.glass.scan2`）之外的所有应用隐藏，且支持通过配置系统运行时切换。

## 目标

1. 引入 `appVisibility` 配置段，支持 `FULL` / `MINIMAL` 两种模式
2. `MINIMAL` 模式下仅显示隐患巡检 + 扫一扫，隐藏所有内置应用
3. `FULL` 模式保留向后兼容（调试用）
4. standard flavor 默认使用 `MINIMAL`

## 架构

```
JSONC 配置 (assets/)
    ↓
InspectionConfigRepository ──→ InspectionAppConfig
                                      ↓
                              appVisibility.mode
                                      ↓
AppVisibilityConfigFactory.create(config) ──→ GlassAppConfig
                                      ↓
RokidSdkManager.configureAppVisibilityOnce()
                                      ↓
                      IDeviceService.configureAppVisibility()
```

## 改动清单

### 1. 配置模型 (`config/InspectionAppConfig.kt`)

新增以下数据类：

```kotlin
enum class AppVisibilityMode {
    FULL,    // 显示所有内置应用 + 第三方应用
    MINIMAL, // 仅隐患巡检 + 扫一扫
}

data class AppVisibilityConfig(
    val mode: AppVisibilityMode = AppVisibilityMode.FULL,
)

data class AppVisibilityConfigOverride(
    val mode: AppVisibilityMode? = null,
)
```

并在现有类中追加字段：
- `InspectionAppConfig` → 增加 `val appVisibility: AppVisibilityConfig = AppVisibilityConfig()`
- `InspectionAppConfigOverride` → 增加 `val appVisibility: AppVisibilityConfigOverride? = null`

### 2. 配置合并逻辑 (`config/InspectionConfigRepository.kt`)

新增 merge 方法：

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

并在 `merge(base: InspectionAppConfig, override: InspectionAppConfigOverride)` 中追加 `appVisibility` 字段的合并。

### 3. 应用可见性工厂 (`hiddenrisk/AppVisibilityConfigFactory.kt`)

将 `create()` 方法签名改为接收配置：

```kotlin
fun create(config: InspectionAppConfig): GlassAppConfig {
    val builtInApps = when (config.appVisibility.mode) {
        AppVisibilityMode.FULL -> supportedBuiltInApps
        AppVisibilityMode.MINIMAL -> emptyList()
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

### 4. SDK Manager 接入 (`hiddenrisk/RokidSdkManager.kt`)

修改 `configureAppVisibilityOnce()` 中配置对象的获取方式：

```kotlin
// 修改前
val config = AppVisibilityConfigFactory.create()

// 修改后
val config = AppVisibilityConfigFactory.create(InspectionConfigRepository.get())
```

### 5. JSONC 配置文件

**`assets/inspection_config.base.jsonc`**（新增段）：

```jsonc
  "appVisibility": {
    // 应用可见性模式。可选值：FULL（显示所有内置应用）| MINIMAL（仅隐患巡检+扫一扫）。
    "mode": "FULL"
  }
```

**`assets/inspection_config.standard.jsonc`**（覆盖为生产默认值）：

```jsonc
{
  "appVisibility": {
    "mode": "MINIMAL"
  }
}
```

### 6. 单元测试

更新 `InspectionConfigRepositoryTest`，验证：
- `base.jsonc` 默认解析为 `FULL`
- overlay 可正确覆盖为 `MINIMAL`
- 字段缺失时回退到代码默认值

## 默认值策略

| 配置文件 | `mode` 值 | 用途 |
|---------|----------|------|
| `inspection_config.base.jsonc` | `FULL` | 向后兼容，调试场景 |
| `inspection_config.standard.jsonc` | `MINIMAL` | 正式版生产默认值 |
| 代码 fallback（字段缺失） | `FULL` | 安全回退 |

## 数据流

1. 应用启动时 `MyApplication.onCreate()` 调用 `InspectionConfigRepository.init(context)`
2. `RokidSdkManager` 初始化并绑定 Glass SDK 安全服务
3. SDK `onReady()` 回调触发 `configureAppVisibilityOnce()`
4. 从 `InspectionConfigRepository.get()` 读取当前 `appVisibility.mode`
5. `AppVisibilityConfigFactory.create(config)` 根据 mode 生成 `GlassAppConfig`
6. 调用 `IDeviceService.configureAppVisibility(config, listener)` 下发到眼镜系统

## 错误处理

- `InspectionConfigRepository` 解析 JSONC 失败时，回退到代码默认值（`FULL`）
- `AppVisibilityConfigFactory.create()` 对 `null` config 使用 `InspectionAppConfig()` 默认值
- `RokidSdkManager.configureAppVisibilityOnce()` 已有 `runCatching` 包裹 SDK 调用，失败仅记录日志不影响启动流程

## 兼容性

- 不涉及 JNI 改动
- 不修改 `GlassAppType` 枚举（来自 Rokid SDK）
- `AppVisibilityConfigFactory.create()` 无参旧签名可保留为 `@Deprecated` 或重载，避免破坏现有调用（当前仅 `RokidSdkManager` 一处调用）
