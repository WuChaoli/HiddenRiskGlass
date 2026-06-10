# WiFi 扫码页标题与取景框配置 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 WiFi 扫码配置抽离到工具类，设置标题为 "请扫描 Wi-Fi 二维码"，取景框比例为 0.8f。

**架构:** 在 utils 包中新建 `WifiScanConfigFactory` object 工厂类，`AiInspectionMenuActivity` 通过工厂获取配置，保持业务逻辑纯净。

**Tech Stack:** Kotlin, Rokid Glass SDK (GlassScanConfig)

---

### Task 1: 创建 WifiScanConfigFactory.kt

**Files:**
- Create: `app/src/main/java/com/rokid/glass/utils/WifiScanConfigFactory.kt`
- Modify: `app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt` (import + 调用点)

**设计说明**：
- 与现有工具类风格一致（`object` + `@JvmStatic`）
- 复用 `R.string.ai_entry_wifi_required_message`
- `viewfinderFrameRatio = 0.8f` 比默认 0.62f 更大，方便对准

- [ ] **Step 1: 创建工厂类**

```kotlin
package com.rokid.glass.utils

import android.content.Context
import com.rokid.glesse.R
import com.rokid.security.glass3.qrcode.model.GlassScanConfig

/**
 * WiFi 二维码扫码页配置工厂
 * 统一封装 GlassScanConfig 的创建逻辑，便于后续调整取景框、缩放等级等参数
 */
object WifiScanConfigFactory {
    @JvmStatic
    fun create(context: Context): GlassScanConfig =
        GlassScanConfig(
            customTitle = context.getString(R.string.ai_entry_wifi_required_message),
            viewfinderFrameRatio = 0.8f,
        )
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/rokid/glass/utils/WifiScanConfigFactory.kt
git commit -m "feat: add WifiScanConfigFactory for scan page config"
```

---

### Task 2: 修改 AiInspectionMenuActivity.kt 调用点

**Files:**
- Modify: `app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt:201`

- [ ] **Step 1: 替换调用**

将 `launchWifiScanner()` 中的：

```kotlin
GlassScanner.launch(
    this,
    GlassScanConfig(),
    object : GlassScanCallback {
```

替换为：

```kotlin
GlassScanner.launch(
    this,
    WifiScanConfigFactory.create(this),
    object : GlassScanCallback {
```

并添加 import：
```kotlin
import com.rokid.glass.utils.WifiScanConfigFactory
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt
git commit -m "feat: use WifiScanConfigFactory for wifi scan page"
```

---

### Task 3: 构建验证

- [ ] **Step 1: 编译检查**

Run: `bash scripts/android/build-debug.sh`
Expected: 编译成功，无错误

- [ ] **Step 2: 代码审查**

- 确认 `WifiScanConfigFactory` 导入了正确的包
- 确认 `AiInspectionMenuActivity` 正确引用了工厂方法
- 确认 `R.string.ai_entry_wifi_required_message` 存在
