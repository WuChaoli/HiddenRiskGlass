package com.rokid.glass

import com.rokid.glass.config.BusinessMockConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BusinessMockEnterpriseInfoFactoryTest {

    @Test
    fun enabledMockCreatesEnterpriseInfoWithConfiguredPlaceCode() {
        val info = BusinessMockEnterpriseInfoFactory.create(
            BusinessMockConfig(enabled = true, placeCode = "XFAQ-JXCS-001"),
        )

        assertEquals("XFAQ-JXCS-001", info?.placeCode)
    }

    @Test
    fun disabledMockDoesNotCreateEnterpriseInfo() {
        assertNull(
            BusinessMockEnterpriseInfoFactory.create(
                BusinessMockConfig(enabled = false, placeCode = "XFAQ-JXCS-001"),
            ),
        )
    }
}
