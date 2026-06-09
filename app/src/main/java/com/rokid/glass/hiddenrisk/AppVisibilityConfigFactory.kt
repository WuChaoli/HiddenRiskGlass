package com.rokid.glass.hiddenrisk

import com.rokid.security.glass3.sdk.base.data.device.bean.GlassAppConfig
import com.rokid.security.glass3.sdk.base.data.device.bean.GlassAppType
import com.rokid.security.glass3.sdk.base.data.device.bean.ThirdPartyApp

object AppVisibilityConfigFactory {
    const val INSPECTION_APP_PACKAGE = "com.rokid.glesse"
    const val INSPECTION_APP_NAME = "隐患巡检"
    const val SCANNER_APP_PACKAGE = "com.rokid.glass.scan2"
    const val SCANNER_APP_NAME = "扫一扫"

    val supportedBuiltInApps = listOf(
        GlassAppType.AI_WORK_ASSISTANT,
        GlassAppType.AI_CHAT,
        GlassAppType.AI_INSPECTION,
        GlassAppType.OFFLINE_FACE,
        GlassAppType.OFFLINE_PLATE,
        GlassAppType.TAKE_PHOTO,
        GlassAppType.HG_IDENTIFICATION,
    )

    fun create(): GlassAppConfig = GlassAppConfig(
        supportedBuiltInApps,
        listOf(
            ThirdPartyApp(INSPECTION_APP_PACKAGE, INSPECTION_APP_NAME),
            ThirdPartyApp(SCANNER_APP_PACKAGE, SCANNER_APP_NAME),
        ),
    )
}
