package com.rokid.glass

/** 完全离线模式下无本地替代能力的 UI 功能可见性。 */
internal object OfflineLocalUiPolicy {
    fun showsDeviceGuide(offlineLocal: Boolean): Boolean = !offlineLocal

    fun showsHazardRecord(offlineLocal: Boolean): Boolean = !offlineLocal

    fun manualDeepEnabled(offlineLocal: Boolean): Boolean = !offlineLocal
}
