package com.rokid.glass

import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.config.NetworkAccessMode

/**
 * 巡检链路特性开关。
 * 关闭企业巡检链路后，直接进入本地隐患分析，并禁用所有上传动作。
 */
object InspectionFeatureFlags {
    fun isEnterpriseInspectionFlowEnabled(): Boolean {
        return InspectionConfigRepository.get().featureFlags.enableEnterpriseInspectionFlow
    }

    fun isOfflineLocalMode(): Boolean {
        return InspectionConfigRepository.get().featureFlags.networkAccessMode == NetworkAccessMode.OFFLINE_LOCAL
    }

    fun isBusinessNetworkAllowed(): Boolean = !isOfflineLocalMode()

    fun isWifiEntryGuardRequired(): Boolean = !isOfflineLocalMode()
}
