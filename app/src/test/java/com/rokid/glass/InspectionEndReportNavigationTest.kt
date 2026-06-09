package com.rokid.glass

import com.rokid.glass.hiddenrisk.AiInspectionActivity
import com.rokid.glass.hiddenrisk.DeviceGuideActivity
import com.rokid.glass.hiddenrisk.HazardRecordActivity
import org.junit.Assert.assertEquals
import org.junit.Test

class InspectionEndReportNavigationTest {

    @Test
    fun fallbackReturnDestination_defaultsToHazardAnalysisHome() {
        assertEquals(
            InspectionEndReportReturnDestination.HAZARD_ANALYSIS_HOME,
            InspectionEndReportReturnDestination.fromIntentValue(null),
        )
        assertEquals(
            InspectionEndReportReturnDestination.HAZARD_ANALYSIS_HOME,
            InspectionEndReportReturnDestination.fromIntentValue("not-supported"),
        )
    }

    @Test
    fun targetActivityClass_mapsAllSupportedHomes() {
        assertEquals(
            AiInspectionActivity::class.java,
            InspectionEndReportReturnDestination.HAZARD_ANALYSIS_HOME.targetActivityClass,
        )
        assertEquals(
            DeviceGuideActivity::class.java,
            InspectionEndReportReturnDestination.DEVICE_GUIDE_HOME.targetActivityClass,
        )
        assertEquals(
            HazardRecordActivity::class.java,
            InspectionEndReportReturnDestination.HAZARD_RECORD_HOME.targetActivityClass,
        )
    }
}
