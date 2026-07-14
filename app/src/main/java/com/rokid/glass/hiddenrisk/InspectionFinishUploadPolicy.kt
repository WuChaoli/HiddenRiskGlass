package com.rokid.glass.hiddenrisk

/** 结束接口只取决于确认结束时的即时网络状态。 */
object InspectionFinishUploadPolicy {
    fun canEnqueue(networkAvailable: Boolean): Boolean = networkAvailable
}
