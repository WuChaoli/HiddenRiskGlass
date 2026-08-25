package com.rokid.glass.network

import com.rokid.glass.config.InspectionConfigRepository
import java.io.IOException
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class InspectionNetworkAccessPolicyTest {

    @After
    fun tearDown() {
        InspectionConfigRepository.reloadForTest("{}")
    }

    @Test
    fun onlineModeAllowsRequest() {
        InspectionNetworkAccessPolicy.ensureAllowed(
            requestUrl = "http://example.test/ai/deep",
            allowed = true,
        )
    }

    @Test
    fun offlineLocalBlocksRequestBeforeNetwork() {
        val error = runCatching {
            InspectionNetworkAccessPolicy.ensureAllowed(
                requestUrl = "http://example.test/ai/deep",
                allowed = false,
            )
        }.exceptionOrNull()

        assertEquals(
            "offline_local_blocked:http://example.test/ai/deep",
            (error as IOException).message,
        )
    }

    @Test
    fun offlineLocalBlocksEveryStructuredHazardEndpoint() {
        listOf(
            "http://example.test/ai/deep/v2",
            "http://example.test/ai/general_deep/v2",
            "http://example.test/ai/gm/v2",
            "http://example.test/ai/sug_checks",
        ).forEach { url ->
            val error = runCatching {
                InspectionNetworkAccessPolicy.ensureAllowed(requestUrl = url, allowed = false)
            }.exceptionOrNull()

            assertEquals("offline_local_blocked:$url", (error as IOException).message)
        }
    }

    @Test
    fun sharedClientBlocksOfflineRequestWithStableError() {
        InspectionConfigRepository.reloadForTest(
            baseJsonc = "{}",
            overlayJsonc = """{"featureFlags":{"networkAccessMode":"OFFLINE_LOCAL"}}""",
        )
        val request = Request.Builder()
            .url("http://127.0.0.1:1/should-not-connect")
            .build()

        val error = runCatching {
            HttpClientProvider.inspectionClient.newCall(request).execute()
        }.exceptionOrNull()

        assertEquals(
            "offline_local_blocked:http://127.0.0.1:1/should-not-connect",
            (error as IOException).message,
        )
    }
}
