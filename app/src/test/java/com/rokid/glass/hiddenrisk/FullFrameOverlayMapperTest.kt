package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullFrameOverlayMapperTest {

    @Test
    fun `request bbox restores to source coordinates before projection`() {
        val result = FullFrameOverlayMapper.map(
            responseDetections = listOf(AlignmentDetection("目标", 0.9f, 96f, 128f, 480f, 640f)),
            requestSize = FrameSize(960, 1280),
            sourceSize = FrameSize(3024, 4032),
            overlaySize = FrameSize(480, 640),
            calibration = DetectionOverlayAlignmentState(1f).calibrationState(),
        )

        assertEquals(302.4f, result.sourceDetections.single().left, 0.01f)
        assertEquals(403.2f, result.sourceDetections.single().top, 0.01f)
    }

    @Test
    fun `bbox outside projection crop is omitted`() {
        val result = FullFrameOverlayMapper.map(
            responseDetections = listOf(AlignmentDetection("窗口外", 0.8f, 0f, 0f, 50f, 50f)),
            requestSize = FrameSize(960, 1280),
            sourceSize = FrameSize(3024, 4032),
            overlaySize = FrameSize(480, 640),
            calibration = DetectionOverlayAlignmentState(1f).calibrationState(),
        )

        assertTrue(result.detections.isEmpty())
    }

    @Test
    fun `direct request coordinates produce same overlay as restoring through full sensor`() {
        val detection = AlignmentDetection("目标", 0.9f, 400f, 600f, 600f, 900f)
        val calibration = DetectionOverlayAlignmentState(1f).calibrationState()

        val direct = FullFrameOverlayMapper.map(
            listOf(detection),
            FrameSize(960, 1280),
            FrameSize(960, 1280),
            FrameSize(480, 640),
            calibration,
        )
        val restored = FullFrameOverlayMapper.map(
            listOf(detection),
            FrameSize(960, 1280),
            FrameSize(3024, 4032),
            FrameSize(480, 640),
            calibration,
        )

        assertEquals(restored.detections.single().left, direct.detections.single().left, 0.01f)
        assertEquals(restored.detections.single().top, direct.detections.single().top, 0.01f)
        assertEquals(restored.detections.single().right, direct.detections.single().right, 0.01f)
        assertEquals(restored.detections.single().bottom, direct.detections.single().bottom, 0.01f)
    }

    @Test
    fun `1200 by 1600 request maps directly without an intermediate sensor size`() {
        val calibration = DetectionOverlayAlignmentState(1f).calibrationState()
        val crop = calibration.normalizedCameraCrop()
        val detection = AlignmentDetection(
            "目标",
            0.9f,
            crop.left * 1200f,
            crop.top * 1600f,
            (crop.left + crop.width) * 1200f,
            (crop.top + crop.height) * 1600f,
        )

        val result = FullFrameOverlayMapper.map(
            listOf(detection),
            FrameSize(1200, 1600),
            FrameSize(1200, 1600),
            FrameSize(480, 640),
            calibration,
        )

        assertEquals(0f, result.detections.single().left, 0.01f)
        assertEquals(0f, result.detections.single().top, 0.01f)
        assertEquals(480f, result.detections.single().right, 0.01f)
        assertEquals(640f, result.detections.single().bottom, 0.01f)
    }

    @Test
    fun `partially intersecting bbox is clipped then mapped to overlay bounds`() {
        val calibration = DetectionOverlayAlignmentState(1f).calibrationState()
        val crop = calibration.normalizedCameraCrop()
        val sourceLeft = crop.left * 3024f
        val sourceTop = crop.top * 4032f
        val sourceRight = (crop.left + crop.width / 2f) * 3024f
        val sourceBottom = (crop.top + crop.height / 2f) * 4032f
        val result = FullFrameOverlayMapper.map(
            responseDetections = listOf(
                AlignmentDetection(
                    "部分相交",
                    0.9f,
                    (sourceLeft - 100f) / 3.15f,
                    sourceTop / 3.15f,
                    sourceRight / 3.15f,
                    sourceBottom / 3.15f,
                ),
            ),
            requestSize = FrameSize(960, 1280),
            sourceSize = FrameSize(3024, 4032),
            overlaySize = FrameSize(480, 640),
            calibration = calibration,
        )

        assertEquals(0f, result.detections.single().left, 0.001f)
        assertEquals(240f, result.detections.single().right, 0.01f)
        assertEquals(0f, result.detections.single().top, 0.001f)
        assertEquals(320f, result.detections.single().bottom, 0.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non portrait source is rejected`() {
        FullFrameOverlayMapper.map(
            emptyList(),
            FrameSize(960, 1280),
            FrameSize(1920, 1080),
            FrameSize(480, 640),
            AlignmentCalibrationState(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non portrait request is rejected`() {
        FullFrameOverlayMapper.map(
            emptyList(),
            FrameSize(1280, 720),
            FrameSize(3024, 4032),
            FrameSize(480, 640),
            AlignmentCalibrationState(),
        )
    }

    @Test
    fun `calibration controls never change fixed distance`() {
        val initial = FullFrameOverlayCalibrationState()
        val adjusted = initial.selectNextControl().adjust(AdjustmentDirection.INCREASE)

        assertEquals(1f, initial.distanceMeters, 0f)
        assertEquals(1f, adjusted.distanceMeters, 0f)
        assertEquals(0f, initial.previewAlpha, 0f)
        assertEquals(0.5f, initial.togglePreview().previewAlpha, 0f)
    }
}
