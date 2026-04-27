package com.rokid.glass

/**
 * 巡检链路特性开关。
 * 关闭企业巡检链路后，直接进入本地隐患分析，并禁用所有上传动作。
 */
object InspectionFeatureFlags {
    const val ENABLE_ENTERPRISE_INSPECTION_FLOW = true

    fun isEnterpriseInspectionFlowEnabled(): Boolean {
        return ENABLE_ENTERPRISE_INSPECTION_FLOW
    }
}
