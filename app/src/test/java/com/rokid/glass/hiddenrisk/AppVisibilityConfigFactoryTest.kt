package com.rokid.glass.hiddenrisk

import com.rokid.security.glass3.sdk.base.data.device.bean.GlassAppType
import org.junit.Assert.assertEquals
import org.junit.Test

class AppVisibilityConfigFactoryTest {
    @Test
    fun create_hidesAllBuiltInAppsAndKeepsOnlyInspectionAndScannerApps() {
        val config = AppVisibilityConfigFactory.create()

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
        assertEquals(
            listOf(
                AppVisibilityConfigFactory.INSPECTION_APP_NAME,
                AppVisibilityConfigFactory.SCANNER_APP_NAME,
            ),
            requireNotNull(config.thirdApps).map { it.appName },
        )
    }
}
