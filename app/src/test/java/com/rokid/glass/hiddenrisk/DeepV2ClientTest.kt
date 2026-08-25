package com.rokid.glass.hiddenrisk

import com.google.gson.JsonParser
import com.rokid.glass.config.AiArApiConfig
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DeepV2ClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `request posts v2 json to configured endpoint`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(successResponse()))
        val callback = RecordingCallback()

        newClient().request(
            requestId = 41L,
            imageBytes = byteArrayOf(1, 2, 3),
            scene = "PLACE-001",
            callback = callback,
        )

        assertTrue(callback.await())
        val request = server.takeRequest()
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("POST", request.method)
        assertEquals("/ai/deep/v2", request.path)
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals("task-fixed", json["task_id"].asString)
        assertEquals("PLACE-001", json["scene"].asString)
        assertEquals("AQID", json["image"].asString)
        assertEquals(41L, callback.successRequestId)
    }

    @Test
    fun `general deep request selects endpoint and validates response type`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(successResponse("general_deep_v2")))
        val callback = RecordingCallback()

        newRoutedClient().request(
            requestId = 45L,
            route = StructuredHazardV2Route(
                StructuredHazardV2Endpoint.GENERAL_DEEP_V2,
                "PLACE-001",
            ),
            imageBytes = byteArrayOf(1),
            callback = callback,
        )

        assertTrue(callback.await())
        assertEquals("/ai/general_deep/v2", server.takeRequest().path)
        assertEquals(45L, callback.successRequestId)
    }

    @Test
    fun `gm request omits scene`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(successResponse("gm_v2")))
        val callback = RecordingCallback()

        newRoutedClient().request(
            requestId = 46L,
            route = StructuredHazardV2Route(StructuredHazardV2Endpoint.GM_V2, null),
            imageBytes = byteArrayOf(1),
            callback = callback,
        )

        assertTrue(callback.await())
        val request = server.takeRequest()
        assertEquals("/ai/gm/v2", request.path)
        assertFalse(JsonParser.parseString(request.body.readUtf8()).asJsonObject.has("scene"))
    }

    @Test
    fun `http error is classified`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        val callback = RecordingCallback()

        newClient().request(42L, byteArrayOf(1), "PLACE-001", callback)

        assertTrue(callback.await())
        assertEquals(DeepV2ClientError.Http(503), callback.error)
    }

    @Test
    fun `protocol error is classified`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        val callback = RecordingCallback()

        newClient().request(43L, byteArrayOf(1), "PLACE-001", callback)

        assertTrue(callback.await())
        assertTrue(callback.error is DeepV2ClientError.Protocol)
    }

    @Test
    fun `cancel suppresses terminal callback`() {
        server.enqueue(
            MockResponse()
                .setHeadersDelay(600, TimeUnit.MILLISECONDS)
                .setResponseCode(200)
                .setBody(successResponse()),
        )
        val callback = RecordingCallback()

        val handle = newClient().request(44L, byteArrayOf(1), "PLACE-001", callback)
        handle.cancel()

        assertFalse(callback.await(900))
    }

    private fun newClient(): DeepV2Client {
        return DeepV2Client(
            apiConfig = AiArApiConfig(
                url = server.url("/ai/deep/v2").toString(),
                connectTimeoutMs = 2_000L,
                readTimeoutMs = 2_000L,
                writeTimeoutMs = 2_000L,
                detectTimeoutMs = 2_000L,
            ),
            httpClient = OkHttpClient(),
            taskIdFactory = { "task-fixed" },
            base64Encoder = Base64.getEncoder()::encodeToString,
            callbackExecutor = Runnable::run,
        )
    }

    private fun newRoutedClient(): DeepV2Client {
        return DeepV2Client(
            apiConfigProvider = { endpoint ->
                val path = when (endpoint) {
                    StructuredHazardV2Endpoint.DEEP_V2 -> "/ai/deep/v2"
                    StructuredHazardV2Endpoint.GENERAL_DEEP_V2 -> "/ai/general_deep/v2"
                    StructuredHazardV2Endpoint.GM_V2 -> "/ai/gm/v2"
                }
                AiArApiConfig(
                    url = server.url(path).toString(),
                    connectTimeoutMs = 2_000L,
                    readTimeoutMs = 2_000L,
                    writeTimeoutMs = 2_000L,
                    detectTimeoutMs = 2_000L,
                )
            },
            httpClient = OkHttpClient(),
            taskIdFactory = { "task-fixed" },
            base64Encoder = Base64.getEncoder()::encodeToString,
            callbackExecutor = Runnable::run,
        )
    }

    private class RecordingCallback : DeepV2Client.Callback {
        private val latch = CountDownLatch(1)
        var successRequestId: Long? = null
        var error: DeepV2ClientError? = null

        override fun onSuccess(requestId: Long, response: DeepV2Response) {
            successRequestId = requestId
            latch.countDown()
        }

        override fun onFailure(requestId: Long, error: DeepV2ClientError) {
            this.error = error
            latch.countDown()
        }

        fun await(timeoutMs: Long = 2_000L): Boolean {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun successResponse(type: String = "deep_v2") = """
        {
          "code": 0,
          "msg": "success",
          "task_id": "task-fixed",
          "type": "$type",
          "detections": [],
          "hazards": [],
          "check_items": [],
          "time": 0.1
        }
    """.trimIndent()
}
