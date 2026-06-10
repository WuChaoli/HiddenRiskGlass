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
        )
}
