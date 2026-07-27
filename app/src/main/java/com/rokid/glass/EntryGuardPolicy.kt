package com.rokid.glass

/** 入口阶段的纯策略判断，避免页面和协调器各自解释离线模式。 */
internal object EntryGuardPolicy {
    fun requiresWifi(offlineLocal: Boolean): Boolean = !offlineLocal

    fun allowsAutoUpdate(offlineLocal: Boolean): Boolean = !offlineLocal

    fun requiresEnterpriseContext(offlineLocal: Boolean): Boolean = !offlineLocal

    fun sessionNetworkAvailable(
        offlineLocal: Boolean,
        systemNetworkAvailable: Boolean,
    ): Boolean = !offlineLocal && systemNetworkAvailable
}
