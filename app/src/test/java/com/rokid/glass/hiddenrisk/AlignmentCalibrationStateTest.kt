package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Test

class AlignmentCalibrationStateTest {

    @Test
    fun `right eye is the default dominant eye`() {
        assertEquals(DominantEye.RIGHT, parseDominantEye(null))
        assertEquals(DominantEye.RIGHT, parseDominantEye("unknown"))
    }

    @Test
    fun `left and right eye presets use equal opposite horizontal offsets`() {
        val left = AlignmentCalibrationPreset.forEye(DominantEye.LEFT)
        val right = AlignmentCalibrationPreset.forEye(DominantEye.RIGHT)

        assertEquals(0.79049903f, left.scale, 0f)
        assertEquals(72f, left.offsetX, 0f)
        assertEquals(-234f, left.offsetY, 0f)
        assertEquals(0.79049903f, right.scale, 0f)
        assertEquals(-72f, right.offsetX, 0f)
        assertEquals(-234f, right.offsetY, 0f)
    }

    @Test
    fun `dominant eye parser accepts adb values without case sensitivity`() {
        assertEquals(DominantEye.LEFT, parseDominantEye("left"))
        assertEquals(DominantEye.RIGHT, parseDominantEye("RIGHT"))
    }

    @Test
    fun `alignment preview is hidden by default while inference stays active`() {
        assertEquals(0f, AlignmentCalibrationState().alpha, 0f)
    }

    @Test
    fun `default calibration maps the theoretical centered camera crop`() {
        val crop = AlignmentCalibrationState().normalizedCameraCrop()

        assertEquals(0.40444f, crop.left, 0.00001f)
        assertEquals(0.40444f, crop.top, 0.00001f)
        assertEquals(0.19113f, crop.width, 0.00001f)
        assertEquals(0.19113f, crop.height, 0.00001f)
    }

    @Test
    fun `click cycles horizontal vertical and scale controls`() {
        val horizontal = AlignmentCalibrationState()
        val vertical = horizontal.selectNextControl()
        val scale = vertical.selectNextControl()
        val wrapped = scale.selectNextControl()

        assertEquals(AlignmentControl.OFFSET_X, horizontal.control)
        assertEquals(AlignmentControl.OFFSET_Y, vertical.control)
        assertEquals(AlignmentControl.SCALE, scale.control)
        assertEquals(AlignmentControl.OFFSET_X, wrapped.control)
    }

    @Test
    fun `swipes adjust only the selected calibration value`() {
        val horizontal = AlignmentCalibrationState().adjust(AdjustmentDirection.DECREASE)
        val vertical = horizontal.selectNextControl().adjust(AdjustmentDirection.INCREASE)
        val scale = vertical.selectNextControl().adjust(AdjustmentDirection.INCREASE)

        assertEquals(-2f, scale.offsetX, 0f)
        assertEquals(2f, scale.offsetY, 0f)
        assertEquals(0.835499f, scale.scale, 0.000001f)
    }

    @Test
    fun `positive screen offset moves source crop in the opposite direction`() {
        val centered = AlignmentCalibrationState().normalizedCameraCrop()
        val shifted = AlignmentCalibrationState(offsetX = 20f, offsetY = 20f).normalizedCameraCrop()

        assertEquals(centered.left - 20f / (3024f * 0.830499f), shifted.left, 0.000001f)
        assertEquals(centered.top - 20f / (4032f * 0.830499f), shifted.top, 0.000001f)
    }

    @Test
    fun `portrait 9 by 16 surface is cropped to 3 by 4 without stretching`() {
        val crop = AlignmentCalibrationState().normalizedSurfaceCrop(
            surfaceWidth = 1080,
            surfaceHeight = 1920,
        )

        assertEquals(0.40444f, crop.left, 0.00001f)
        assertEquals(0.42833f, crop.top, 0.00001f)
        assertEquals(0.19113f, crop.width, 0.00001f)
        assertEquals(0.14334f, crop.height, 0.00001f)
        assertEquals(0.75f, (crop.width * 1080f) / (crop.height * 1920f), 0.0001f)
    }

    @Test
    fun `native 3 by 4 surface keeps the theoretical crop`() {
        val expected = AlignmentCalibrationState().normalizedCameraCrop()
        val actual = AlignmentCalibrationState().normalizedSurfaceCrop(
            surfaceWidth = 3024,
            surfaceHeight = 4032,
        )

        assertEquals(expected, actual)
    }

    @Test
    fun `sdk internal axis swap keeps requested portrait texture coordinates`() {
        val textureSize = selectAlignmentTextureSize(
            requestedWidth = 3024,
            requestedHeight = 4032,
            reportedWidth = 4032,
            reportedHeight = 3024,
        )

        assertEquals(3024 to 4032, textureSize)
    }
}
