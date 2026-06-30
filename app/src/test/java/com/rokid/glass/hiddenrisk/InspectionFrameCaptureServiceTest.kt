package com.rokid.glass.hiddenrisk

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectionFrameCaptureServiceTest {

    @Test
    fun selectBestFramePayload_returnsNullWhenNoFrame() {
        var now = 1_000L
        val service = newService(
            frames = emptyList(),
            clock = { now },
            sleep = { now += it },
        )

        assertNull(service.selectBestFramePayload(lastTimestampExclusive = Long.MIN_VALUE))
    }

    @Test
    fun selectBestFramePayload_dropsStaleFrame() {
        var now = 5_000L
        val service = newService(
            frames = listOf(newFrame(timestamp = 1L, receivedAtElapsedMs = 3_000L)),
            staleFrameThresholdMs = 1_000L,
            clock = { now },
            sleep = { now += it },
        )

        assertNull(service.selectBestFramePayload(lastTimestampExclusive = Long.MIN_VALUE))
    }

    @Test
    fun selectBestFramePayload_usesSharpestFrameAndKeepsMetadata() {
        var now = 1_000L
        val flatFrame = newFrame(
            timestamp = 1L,
            receivedAtElapsedMs = 900L,
            data = nv21Frame(fill = 80),
        )
        val sharpFrame = newFrame(
            timestamp = 2L,
            receivedAtElapsedMs = 920L,
            data = sharpNv21Frame(),
        )
        val service = newService(
            frames = listOf(flatFrame, sharpFrame),
            maxFrames = 2,
            clock = { now },
            sleep = { now += it },
            encoder = { nv21, _, _, _, _ -> byteArrayOf(nv21[0]) },
        )

        val payload = service.selectBestFramePayload(lastTimestampExclusive = Long.MIN_VALUE)

        assertEquals(2L, payload?.timestamp)
        assertEquals(640, payload?.width)
        assertEquals(960, payload?.sourceWidth)
        assertEquals(10, payload?.cropRect?.left)
        assertEquals(20, payload?.cropRect?.top)
        assertEquals(650, payload?.cropRect?.right)
        assertEquals(660, payload?.cropRect?.bottom)
        assertTrue((payload?.sharpnessScore ?: 0.0) > 0.0)
        assertEquals(1, payload?.jpegBytes?.size)
    }

    @Test
    fun requestPayload_forDeepAnalysisIncludesImageAndStreamFlag() {
        val json = com.google.gson.Gson().toJson(
            AiArSseService.RequestPayload(
                task_id = "record-1",
                image = "base64-image",
            ),
        )

        assertTrue(json.contains("\"stream\":true"))
        assertTrue(json.contains("\"image\":\"base64-image\""))
    }

    @Test
    fun requestPayload_forIdentifyItemHazardIncludesImageAndStreamFlag() {
        val json = com.google.gson.Gson().toJson(
            AiArSseService.RequestPayload(
                task_id = "record-2",
                image = "base64-image",
            ),
        )

        assertTrue(json.contains("\"stream\":true"))
        assertTrue(json.contains("\"image\":\"base64-image\""))
    }

    @Test
    fun requestPayload_forInspectionGuideIncludesTextAndStreamFlag() {
        val json = com.google.gson.Gson().toJson(
            AiArSseService.RequestPayload(
                task_id = "record-3",
                text = "guide-text",
            ),
        )

        assertTrue(json.contains("\"stream\":true"))
        assertTrue(json.contains("\"text\":\"guide-text\""))
    }

    @Test
    fun parseHazardDetectionBody_extractsLabelFromJsonResponse() {
        val result = AiArSseService.parseHazardDetectionBody(
            body = """
                {
                  "code": 0,
                  "msg": "success",
                  "task_id": "1",
                  "content": true,
                  "inference_result": [
                    {
                      "label": "煤炉",
                      "bbox": [331.56, 783.49, 648.17, 1184.67],
                      "score": 0.909,
                      "area_r": 0.058,
                      "inter": 0
                    }
                  ],
                  "cost": 0.276
                }
            """.trimIndent(),
            requireInferenceResults = true,
            preferSse = false,
        )

        assertTrue(result.hasHazard)
        assertEquals(listOf("煤炉"), result.labels)
    }

    @Test
    fun parseHazardDetectionBody_dedupesLabelsAndIgnoresBlankLabels() {
        val result = AiArSseService.parseHazardDetectionBody(
            body = """
                {
                  "code": 0,
                  "content": true,
                  "inference_result": [
                    { "label": "煤炉" },
                    { "label": " " },
                    { "label": "煤炉" },
                    { "label": "配电箱" }
                  ]
                }
            """.trimIndent(),
            requireInferenceResults = true,
            preferSse = false,
        )

        assertTrue(result.hasHazard)
        assertEquals(listOf("煤炉", "配电箱"), result.labels)
    }

    @Test
    fun parseHazardDetectionBody_extractsLabelsFromSseResponse() {
        val result = AiArSseService.parseHazardDetectionBody(
            body = """
                data: {"code":0,"content":true,"inference_result":[{"label":"煤炉"}]}
                data: [DONE]
            """.trimIndent(),
            requireInferenceResults = true,
            preferSse = true,
        )

        assertTrue(result.hasHazard)
        assertEquals(listOf("煤炉"), result.labels)
    }

    @Test
    fun parseHazardDetectionBody_allowsGeneralHazardWithoutInferenceLabels() {
        val result = AiArSseService.parseHazardDetectionBody(
            body = """{"code":0,"content":true,"msg":"success"}""",
            requireInferenceResults = false,
            preferSse = false,
        )

        assertTrue(result.hasHazard)
        assertEquals(emptyList<String>(), result.labels)
    }

    @Test
    fun decideOnlineLabelCooldown_allowsLabelsThatAreNotCooling() {
        val result = AiInspectionActivity.decideOnlineLabelCooldown(
            labels = listOf("煤炉"),
            isCooling = { false },
        )

        assertFalse(result.shouldSuppress)
        assertEquals(listOf("煤炉"), result.activeLabels)
    }

    @Test
    fun decideOnlineLabelCooldown_suppressesWhenAllLabelsAreCooling() {
        val result = AiInspectionActivity.decideOnlineLabelCooldown(
            labels = listOf("煤炉", "配电箱"),
            isCooling = { true },
        )

        assertTrue(result.shouldSuppress)
        assertEquals(emptyList<String>(), result.activeLabels)
    }

    @Test
    fun decideOnlineLabelCooldown_keepsOnlyLabelsThatAreNotCooling() {
        val result = AiInspectionActivity.decideOnlineLabelCooldown(
            labels = listOf("煤炉", "配电箱"),
            isCooling = { it == "煤炉" },
        )

        assertFalse(result.shouldSuppress)
        assertEquals(listOf("配电箱"), result.activeLabels)
    }

    private fun newService(
        frames: List<InspectionFrameCaptureService.SourceSquareFrame>,
        staleFrameThresholdMs: Long = 1_500L,
        maxFrames: Int = 3,
        clock: () -> Long = { 1_000L },
        sleep: (Long) -> Unit = {},
        encoder: InspectionFrameCaptureService.JpegEncoder = InspectionFrameCaptureService.JpegEncoder { _, _, _, _, _ ->
            byteArrayOf(1, 2, 3)
        },
    ): InspectionFrameCaptureService {
        var index = 0
        return InspectionFrameCaptureService(
            frameProvider = object : InspectionFrameCaptureService.FrameProvider {
                override fun copyLatestSquareFrame(): InspectionFrameCaptureService.SourceSquareFrame? {
                    return frames.getOrNull(index++)
                }
            },
            jpegEncoder = encoder,
            staleFrameThresholdMs = staleFrameThresholdMs,
            selectWindowMs = 100L,
            selectMaxFrames = maxFrames,
            selectPollIntervalMs = 10L,
            jpegQuality = 90,
            clockElapsedMs = clock,
            sleepMs = sleep,
            logger = { _, _ -> },
            warningLogger = { _ -> },
        )
    }

    private fun newFrame(
        timestamp: Long,
        receivedAtElapsedMs: Long,
        data: ByteArray = sharpNv21Frame(),
    ): InspectionFrameCaptureService.SourceSquareFrame {
        return InspectionFrameCaptureService.SourceSquareFrame(
            data = data,
            width = 640,
            height = 640,
            sourceWidth = 960,
            sourceHeight = 720,
            cropRect = Rect().apply {
                left = 10
                top = 20
                right = 650
                bottom = 660
            },
            timestamp = timestamp,
            receivedAtElapsedMs = receivedAtElapsedMs,
        )
    }

    private fun nv21Frame(fill: Int): ByteArray {
        return ByteArray(640 * 640 * 3 / 2) { fill.toByte() }
    }

    private fun sharpNv21Frame(): ByteArray {
        val frame = nv21Frame(fill = 64)
        for (y in 0 until 640) {
            for (x in 0 until 640) {
                frame[y * 640 + x] = if (((x / 8) + (y / 8)) % 2 == 0) 16.toByte() else 220.toByte()
            }
        }
        return frame
    }
}
