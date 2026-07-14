package com.rokid.glass.hiddenrisk

/** 在线详情和离线详情的纯逻辑路由。 */
internal object LocalHazardDetailRouteDecider {
    enum class InitialRoute { REMOTE, LOCAL, UNAVAILABLE }

    fun initial(
        forceLocalAnalysis: Boolean,
        networkAvailable: Boolean,
        localFallbackAvailable: Boolean,
    ): InitialRoute = when {
        forceLocalAnalysis && localFallbackAvailable -> InitialRoute.LOCAL
        forceLocalAnalysis -> InitialRoute.UNAVAILABLE
        networkAvailable -> InitialRoute.REMOTE
        localFallbackAvailable -> InitialRoute.LOCAL
        else -> InitialRoute.UNAVAILABLE
    }

    fun shouldFallback(message: String, localFallbackAvailable: Boolean): Boolean {
        return localFallbackAvailable && message.startsWith(AiArSseService.NETWORK_FAILURE_PREFIX)
    }
}
