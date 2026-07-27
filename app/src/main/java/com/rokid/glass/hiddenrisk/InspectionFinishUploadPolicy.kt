package com.rokid.glass.hiddenrisk

/** 结束接口同时受即时网络状态和变体业务联网策略约束。 */
object InspectionFinishUploadPolicy {
    fun canEnqueue(
        networkAvailable: Boolean,
        businessNetworkAllowed: Boolean,
    ): Boolean = networkAvailable && businessNetworkAllowed
}
