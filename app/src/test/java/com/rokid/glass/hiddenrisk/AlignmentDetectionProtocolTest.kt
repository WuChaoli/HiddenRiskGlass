package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlignmentDetectionProtocolTest {

    @Test
    fun `request payload uses fixed task stream text and scene fields`() {
        val payload = AlignmentDetectionProtocol.buildRequestJson("abc123")
        val json = com.google.gson.JsonParser.parseString(payload).asJsonObject

        assertEquals("task_001", json.get("task_id").asString)
        assertEquals(true, json.get("stream").asBoolean)
        assertEquals("abc123", json.get("image").asString)
        assertEquals("", json.get("text").asString)
        assertEquals("XFAQ-JXCS-001", json.get("scene").asString)
    }

    @Test
    fun `response parser preserves every valid bbox label and score`() {
        val response = AlignmentDetectionProtocol.parseResponse(
            """
            {
              "code": 0,
              "msg": "success",
              "task_id": "task_001",
              "content": true,
              "inference_result": [
                {"label":"燃气灶","bbox":[32.5,293.25,900.75,1100.5],"score":0.90648,"inter":0},
                {"label":"灭火器","bbox":[10,20,110,220],"score":0.8,"inter":0}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(2, response.detections.size)
        assertEquals("燃气灶", response.detections[0].label)
        assertEquals(32.5f, response.detections[0].left, 0f)
        assertEquals(293.25f, response.detections[0].top, 0f)
        assertEquals(900.75f, response.detections[0].right, 0f)
        assertEquals(1100.5f, response.detections[0].bottom, 0f)
        assertEquals(0.90648f, response.detections[0].score, 0.00001f)
    }

    @Test(expected = IllegalStateException::class)
    fun `response parser rejects nonzero service code`() {
        AlignmentDetectionProtocol.parseResponse(
            """{"code":500,"msg":"failed","content":false,"inference_result":[]}""",
        )
    }

    @Test
    fun `malformed bbox entries are ignored without discarding valid entries`() {
        val response = AlignmentDetectionProtocol.parseResponse(
            """
            {
              "code":0,
              "content":true,
              "inference_result":[
                {"label":"bad","bbox":[1,2,3],"score":0.1},
                {"label":"valid","bbox":[10,20,110,220],"score":0.8}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(listOf("valid"), response.detections.map { it.label })
    }

    @Test
    fun `bbox from submitted portrait image maps to top left screen coordinates`() {
        val mapped = AlignmentDetectionMapper.mapToScreen(
            detection = AlignmentDetection(
                label = "燃气灶",
                score = 0.9f,
                left = 96f,
                top = 128f,
                right = 864f,
                bottom = 1152f,
            ),
            imageWidth = 960,
            imageHeight = 1280,
            screenWidth = 480,
            screenHeight = 640,
        )

        assertEquals(48f, mapped.left, 0.001f)
        assertEquals(64f, mapped.top, 0.001f)
        assertEquals(432f, mapped.right, 0.001f)
        assertEquals(576f, mapped.bottom, 0.001f)
    }

    @Test
    fun `bbox outside submitted image is clipped to screen bounds`() {
        val mapped = AlignmentDetectionMapper.mapToScreen(
            detection = AlignmentDetection("燃气灶", 0.9f, -10f, 200f, 1200f, 1435f),
            imageWidth = 960,
            imageHeight = 1280,
            screenWidth = 480,
            screenHeight = 640,
        )

        assertEquals(0f, mapped.left, 0f)
        assertEquals(100f, mapped.top, 0f)
        assertEquals(480f, mapped.right, 0f)
        assertEquals(640f, mapped.bottom, 0f)
    }

    @Test
    fun `next request waits for previous response even after cadence elapsed`() {
        val delay = AlignmentDetectionCadence.nextDelayMs(
            nowMs = 1_500L,
            lastStartedMs = 500L,
            requestInFlight = true,
        )

        assertNull(delay)
    }

    @Test
    fun `next request starts only after five hundred millisecond cadence`() {
        assertEquals(
            200L,
            AlignmentDetectionCadence.nextDelayMs(
                nowMs = 1_300L,
                lastStartedMs = 1_000L,
                requestInFlight = false,
            ),
        )
        assertEquals(
            0L,
            AlignmentDetectionCadence.nextDelayMs(
                nowMs = 1_500L,
                lastStartedMs = 1_000L,
                requestInFlight = false,
            ),
        )
    }

    @Test
    fun `inference image height always follows exact three by four ratio`() {
        assertEquals(AlignmentInferenceImageSize(960, 1280), AlignmentInferenceImageSize.fromWidth(960))
        assertEquals(AlignmentInferenceImageSize(1080, 1440), AlignmentInferenceImageSize.fromWidth(1080))
    }
}
