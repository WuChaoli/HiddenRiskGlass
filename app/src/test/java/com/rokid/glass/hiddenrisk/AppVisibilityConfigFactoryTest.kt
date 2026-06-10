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
