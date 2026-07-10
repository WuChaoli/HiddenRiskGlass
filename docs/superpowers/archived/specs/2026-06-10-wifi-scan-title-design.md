# WiFi 扫码页标题与取景框配置设计

## 背景

`AiInspectionMenuActivity` 在无 WiFi 时会自动拉起 SDK 扫码页引导用户扫描 WiFi 二维码。当前使用的是无参 `GlassScanConfig()`，未配置标题和取景框比例。

## 目标

- 扫码页顶部标题显示：**"请扫描 Wi-Fi 二维码"**
- 取景框比例调整为：**0.8f**（占短边 80%，便于对准）
- 配置逻辑抽离到 utils，不干扰菜单页业务逻辑

## 方案

采用**工厂模式**，在 utils 包中新建 `WifiScanConfigFactory` 专门负责创建 WiFi 扫码配置。

## 变更文件

| 文件 | 操作 |
|------|------|
| `app/src/main/java/com/rokid/glass/utils/WifiScanConfigFactory.kt` | 新增 |
| `app/src/main/java/com/rokid/glass/AiInspectionMenuActivity.kt` | 修改（一行替换） |

## 实现细节

### 1. WifiScanConfigFactory.kt

```kotlin
package com.rokid.glass.utils

import android.content.Context
import com.rokid.glesse.R
import com.rokid.security.glass3.qrcode.model.GlassScanConfig

object WifiScanConfigFactory {
    @JvmStatic
    fun create(context: Context): GlassScanConfig =
        GlassScanConfig(
            customTitle = context.getString(R.string.ai_entry_wifi_required_message),
            viewfinderFrameRatio = 0.8f,
        )
}
```

**设计要点**：
- `object` + `@JvmStatic`：与现有工具类（如 `SystemStateUtils`）风格一致
- 复用 `R.string.ai_entry_wifi_required_message`：与现有文案保持一致
- `viewfinderFrameRatio = 0.8f`：比默认 0.62f 更大，方便用户对准二维码

### 2. AiInspectionMenuActivity.kt 调用点

将 `launchWifiScanner()` 中的：

```kotlin
GlassScanner.launch(
    this,
    GlassScanConfig(),
    ...
)
```

替换为：

```kotlin
GlassScanner.launch(
    this,
    WifiScanConfigFactory.create(this),
    ...
)
```

## 验收标准

- [ ] 扫码页顶部显示标题 "请扫描 Wi-Fi 二维码"
- [ ] 取景框比原来更大（0.8f vs 默认 0.62f）
- [ ] 无 WiFi 时自动扫码流程正常工作
- [ ] 扫码成功/失败/取消回调逻辑不受影响
