package com.rokid.glass

import com.rokid.glass.hiddenrisk.AiInspectionActivity
import com.rokid.glass.hiddenrisk.DeviceGuideActivity
import com.rokid.glass.hiddenrisk.HazardRecordActivity

/**
 * 结束巡检页取消后的返回目标。
 * 统一收敛成首页级别，不恢复离开前的中间态。
 */
enum class InspectionEndReportReturnDestination(
    val intentValue: String,
    val targetActivityClass: Class<*>,
) {
    AI_MENU_HOME(
        intentValue = "ai_menu_home",
        targetActivityClass = AiInspectionMenuActivity::class.java,
    ),
    HAZARD_ANALYSIS_HOME(
        intentValue = "hazard_analysis_home",
        targetActivityClass = AiInspectionActivity::class.java,
    ),
    DEVICE_GUIDE_HOME(
        intentValue = "device_guide_home",
        targetActivityClass = DeviceGuideActivity::class.java,
    ),
    HAZARD_RECORD_HOME(
        intentValue = "hazard_record_home",
        targetActivityClass = HazardRecordActivity::class.java,
    );

    companion object {
        fun fromIntentValue(rawValue: String?): InspectionEndReportReturnDestination {
            return entries.firstOrNull { it.intentValue == rawValue } ?: HAZARD_ANALYSIS_HOME
        }
    }
}
