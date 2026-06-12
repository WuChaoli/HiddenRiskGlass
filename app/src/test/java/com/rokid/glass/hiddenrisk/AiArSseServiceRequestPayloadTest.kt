package com.rokid.glass.hiddenrisk

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiArSseServiceRequestPayloadTest {

    @Test
    fun requestPayload_forItemDetectionIncludesImageAndStreamFlag() {
        val json = Gson().toJson(
            AiArSseService.RequestPayload(
                task_id = "task-item",
                image = "base64-item",
            ),
        )

        assertTrue(json.contains("\"stream\":true"))
        assertTrue(json.contains("\"image\":\"base64-item\""))
        assertFalse(json.contains("\"text\""))
    }

    @Test
    fun requestPayload_forAdviceIncludesTextAndOmitsImage() {
        val json = Gson().toJson(
            AiArSseService.RequestPayload(
                task_id = "task-1",
                text = "隐患描述：测试隐患\n整改建议：测试建议",
            ),
        )

        assertTrue(json.contains("\"stream\":true"))
        assertTrue(json.contains("\"text\":\"隐患描述：测试隐患\\n整改建议：测试建议\""))
        assertFalse(json.contains("\"image\""))
    }

    @Test
    fun detectionRouteContext_hasScene_returnsAllEndpoints() {
        val ctx = DetectionRouteContext(
            autoUrl = "http://example.test/ai/auto",
            generalUrl = "http://example.test/ai/general",
            deepUrl = "http://example.test/ai/deep",
            gmUrl = "http://example.test/ai/gm",
            enterpriseInfo = com.rokid.glass.workflow.InspectionWorkflowSession.EnterpriseInfo(
                companyName = "test",
                siteName = "test",
                inspectorName = "test",
                qrContent = "test",
                placeCode = "PLACE-001",
            ),
        )

        assertEquals("http://example.test/ai/auto", ctx.itemDetectionEndpoint())
        assertEquals("http://example.test/ai/general", ctx.sceneDetectionEndpoint())
        assertEquals("http://example.test/ai/deep", ctx.deepAnalysisEndpoint())
        assertEquals("PLACE-001", ctx.sceneParam())
    }

    @Test
    fun detectionRouteContext_noScene_skipsDetectionAndFallsBackDeepToGm() {
        val ctx = DetectionRouteContext(
            autoUrl = "http://example.test/ai/auto",
            generalUrl = "http://example.test/ai/general",
            deepUrl = "http://example.test/ai/deep",
            gmUrl = "http://example.test/ai/gm",
            enterpriseInfo = com.rokid.glass.workflow.InspectionWorkflowSession.EnterpriseInfo(
                companyName = "test",
                siteName = "test",
                inspectorName = "test",
                qrContent = "test",
                placeCode = "",
            ),
        )

        assertNull(ctx.itemDetectionEndpoint())
        assertNull(ctx.sceneDetectionEndpoint())
        assertEquals("http://example.test/ai/gm", ctx.deepAnalysisEndpoint())
        assertNull(ctx.sceneParam())
    }

    @Test
    fun detectionRouteContext_nullEnterpriseInfo_skipsDetection() {
        val ctx = DetectionRouteContext(
            autoUrl = "http://example.test/ai/auto",
            generalUrl = "http://example.test/ai/general",
            deepUrl = "http://example.test/ai/deep",
            gmUrl = "http://example.test/ai/gm",
            enterpriseInfo = null,
        )

        assertNull(ctx.itemDetectionEndpoint())
        assertNull(ctx.sceneDetectionEndpoint())
        assertEquals("http://example.test/ai/gm", ctx.deepAnalysisEndpoint())
    }

    @Test
    fun detectionRouteContext_blankPlaceCode_treatedAsMissing() {
        val ctx = DetectionRouteContext(
            autoUrl = "http://example.test/ai/auto",
            generalUrl = "http://example.test/ai/general",
            deepUrl = "http://example.test/ai/deep",
            gmUrl = "http://example.test/ai/gm",
            enterpriseInfo = com.rokid.glass.workflow.InspectionWorkflowSession.EnterpriseInfo(
                companyName = "test",
                siteName = "test",
                inspectorName = "test",
                qrContent = "test",
                placeCode = "   ",
            ),
        )

        assertNull(ctx.itemDetectionEndpoint())
        assertNull(ctx.sceneDetectionEndpoint())
        assertEquals("http://example.test/ai/gm", ctx.deepAnalysisEndpoint())
    }

    @Test
    fun isDoneEvent_acceptsPlainDoneSentinel() {
        assertTrue(AiArSseService.isDoneEvent(type = null, normalizedData = "[DONE]"))
    }

    @Test
    fun isDoneEvent_acceptsDoneEventWithJsonArrayPayload() {
        assertTrue(AiArSseService.isDoneEvent(type = "done", normalizedData = "[\"DONE\"]"))
    }

    @Test
    fun isDoneEvent_acceptsDoneEventWithEmptyPayload() {
        assertTrue(AiArSseService.isDoneEvent(type = "done", normalizedData = ""))
    }

    @Test
    fun isDoneEvent_doesNotMatchNormalMessagePayload() {
        val data = "{\"task_id\":\"task-1\",\"content\":\"否\"}"

        assertFalse(AiArSseService.isDoneEvent(type = "message", normalizedData = data))

        val aggregator = AiArEventAggregator()
        aggregator.append(data)
        assertEquals("否", aggregator.fullText())
    }

    @Test
    fun isDoneEvent_doesNotHideUnexpectedJsonArrayPayload() {
        assertFalse(AiArSseService.isDoneEvent(type = "message", normalizedData = "[\"unexpected\"]"))
    }

    @Test
    fun hasHazardFromIdentifyResponse_itemRequiresInferenceResults() {
        val response = AiArSseService.IdentifyResponse(
            code = 0,
            content = true,
            inference_result = emptyList(),
        )

        assertFalse(
            AiArSseService.hasHazardFromIdentifyResponse(
                parsed = response,
                requireInferenceResults = true,
            ),
        )
    }

    @Test
    fun hasHazardFromIdentifyResponse_sceneUsesContentOnly() {
        val response = AiArSseService.IdentifyResponse(
            code = 0,
            content = true,
            inference_result = emptyList(),
        )

        assertTrue(
            AiArSseService.hasHazardFromIdentifyResponse(
                parsed = response,
                requireInferenceResults = false,
            ),
        )
    }

    @Test
    fun hasHazardFromIdentifyResponse_falseContentNeverTriggers() {
        val response = AiArSseService.IdentifyResponse(
            code = 0,
            content = false,
            inference_result = listOf(AiArSseService.InferenceResultItem(label = "煤炉")),
        )

        assertFalse(
            AiArSseService.hasHazardFromIdentifyResponse(
                parsed = response,
                requireInferenceResults = false,
            ),
        )
    }

    @Test
    fun parseHazardDetectionBody_prefersSseContentYesForScene() {
        val body = """
            id: 1
            event: message
            data: {"task_id":"task-scene","content":"是"}

            event: done
            data: [DONE]
        """.trimIndent()

        val parsed = AiArSseService.parseHazardDetectionBody(
            body = body,
            requireInferenceResults = false,
            preferSse = true,
        )

        assertTrue(parsed.hasHazard)
        assertEquals(0, parsed.inferenceCount)
        assertTrue(parsed.rawText.contains("\"content\":\"是\""))
    }

    @Test
    fun parseHazardDetectionBody_prefersSseContentNoForScene() {
        val body = """
            id: 1
            event: message
            data: {"task_id":"task-scene","content":"否"}

            event: done
            data: [DONE]
        """.trimIndent()

        val parsed = AiArSseService.parseHazardDetectionBody(
            body = body,
            requireInferenceResults = false,
            preferSse = true,
        )

        assertFalse(parsed.hasHazard)
        assertEquals(0, parsed.inferenceCount)
    }
}
