package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHazardDetailRouteDeciderTest {
    @Test fun onlineUsesRemote() = assertEquals(LocalHazardDetailRouteDecider.InitialRoute.REMOTE, LocalHazardDetailRouteDecider.initial(false, true, true))
    @Test fun offlineUsesLocal() = assertEquals(LocalHazardDetailRouteDecider.InitialRoute.LOCAL, LocalHazardDetailRouteDecider.initial(false, false, true))
    @Test fun offlineWithoutKnowledgeIsUnavailable() = assertEquals(LocalHazardDetailRouteDecider.InitialRoute.UNAVAILABLE, LocalHazardDetailRouteDecider.initial(false, false, false))

    @Test fun forcedLocalUsesLocalWhileOnline() = assertEquals(
        LocalHazardDetailRouteDecider.InitialRoute.LOCAL,
        LocalHazardDetailRouteDecider.initial(true, true, true),
    )

    @Test fun forcedLocalUsesLocalWhileOffline() = assertEquals(
        LocalHazardDetailRouteDecider.InitialRoute.LOCAL,
        LocalHazardDetailRouteDecider.initial(true, false, true),
    )

    @Test fun forcedLocalNeverUsesRemoteWithoutKnowledge() = assertEquals(
        LocalHazardDetailRouteDecider.InitialRoute.UNAVAILABLE,
        LocalHazardDetailRouteDecider.initial(true, true, false),
    )

    @Test fun markedNetworkFailureFallsBack() = assertTrue(LocalHazardDetailRouteDecider.shouldFallback("${AiArSseService.NETWORK_FAILURE_PREFIX}timeout", true))
    @Test fun httpAndParseFailuresDoNotFallback() {
        assertFalse(LocalHazardDetailRouteDecider.shouldFallback("HTTP 401", true))
        assertFalse(LocalHazardDetailRouteDecider.shouldFallback("在线识别结果解析失败", true))
    }
}
