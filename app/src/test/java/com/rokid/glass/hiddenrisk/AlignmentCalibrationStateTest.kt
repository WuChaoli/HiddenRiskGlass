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
    fun `detection overlay distance changes by half meter and updates inverse offset`() {
        val initial = DetectionOverlayAlignmentState()
        val nearer = initial.adjustDistance(AdjustmentDirection.DECREASE)
        val farther = initial.adjustDistance(AdjustmentDirection.INCREASE)

        assertEquals(1f, initial.distanceMeters, 0f)
        assertEquals(-7.94f, initial.offsetX, 0.001f)
        assertEquals(0.5f, nearer.distanceMeters, 0f)
        assertEquals(-123.88f, nearer.offsetX, 0.001f)
        assertEquals(1.5f, farther.distanceMeters, 0f)
        assertEquals(30.706f, farther.offsetX, 0.001f)
        assertEquals(0f, initial.calibrationState().alpha, 0f)
    }

    @Test
    fun `detection overlay distance never drops below half meter`() {
        val state = DetectionOverlayAlignmentState(distanceMeters = 0.5f)

        assertEquals(
            0.5f,
            state.adjustDistance(AdjustmentDirection.DECREASE).distanceMeters,
            0f,
        )
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

    @Test
    fun `distance alignment cycles five distance presets`() {
        var state = DistanceAlignmentState()

        assertEquals(0.5f, state.distanceMeters, 0f)
        repeat(4) { state = state.selectNextDistance() }
        assertEquals(3f, state.distanceMeters, 0f)
        assertEquals(0.5f, state.selectNextDistance().distanceMeters, 0f)
    }

    @Test
    fun `distance alignment exposes only x and y controls`() {
        val horizontal = DistanceAlignmentState()
        val vertical = horizontal.selectNextControl()

        assertEquals(DistanceAlignmentControl.OFFSET_X, horizontal.control)
        assertEquals(DistanceAlignmentControl.OFFSET_Y, vertical.control)
        assertEquals(DistanceAlignmentControl.OFFSET_X, vertical.selectNextControl().control)
    }

    @Test
    fun `each distance keeps independent offsets while scale stays calibrated`() {
        val firstDistance = DistanceAlignmentState()
            .adjust(AdjustmentDirection.INCREASE)
        val secondDistance = firstDistance
            .selectNextDistance()
            .selectNextControl()
            .adjust(AdjustmentDirection.DECREASE)

        assertEquals(-70f, secondDistance.selectDistance(0).offsetX, 0f)
        assertEquals(-234f, secondDistance.selectDistance(0).offsetY, 0f)
        assertEquals(-72f, secondDistance.offsetX, 0f)
        assertEquals(-236f, secondDistance.offsetY, 0f)
        assertEquals(AlignmentCalibrationPreset.CALIBRATED_SCALE, secondDistance.scale, 0f)
        assertEquals(0.5f, secondDistance.alpha, 0f)
    }

    @Test
    fun `hardware back event switches distance because glasses reports double click as back`() {
        assertEquals(
            DistanceAlignmentInputAction.NEXT_DISTANCE,
            distanceAlignmentActionForKey(GlassKeyEvent.KEYCODE_BACK),
        )
        assertEquals(
            DistanceAlignmentInputAction.NEXT_DISTANCE,
            distanceAlignmentActionForKey(GlassKeyEvent.KEYCODE_DOUBLE_CLICK),
        )
    }

    @Test
    fun `inverse distance fit computes x while y and scale stay fixed`() {
        val state = InverseDistanceAlignmentState(distanceMeters = 2f, b = 98.88f, k = 72.43f)

        assertEquals(62.665f, state.offsetX, 0.001f)
        assertEquals(-234f, state.offsetY, 0f)
        assertEquals(AlignmentCalibrationPreset.CALIBRATED_SCALE, state.scale, 0f)
        assertEquals(0.5f, state.alpha, 0f)
    }

    @Test
    fun `inverse distance controls cycle distance b and k`() {
        val distance = InverseDistanceAlignmentState()
        val b = distance.selectNextControl()
        val k = b.selectNextControl()

        assertEquals(InverseDistanceAlignmentControl.DISTANCE, distance.control)
        assertEquals(InverseDistanceAlignmentControl.B, b.control)
        assertEquals(InverseDistanceAlignmentControl.K, k.control)
        assertEquals(InverseDistanceAlignmentControl.DISTANCE, k.selectNextControl().control)
    }

    @Test
    fun `inverse distance page adjusts all values by one and never reaches zero distance`() {
        val distance = InverseDistanceAlignmentState(distanceMeters = 1f)
            .adjust(AdjustmentDirection.DECREASE)
        val b = distance.selectNextControl().adjust(AdjustmentDirection.INCREASE)
        val k = b.selectNextControl().adjust(AdjustmentDirection.DECREASE)

        assertEquals(1f, k.distanceMeters, 0f)
        assertEquals(109f, k.b, 0.001f)
        assertEquals(114.94f, k.k, 0.001f)
    }

    @Test
    fun `inverse distance page restores latest b and k for each distance`() {
        val oneMeter = InverseDistanceAlignmentState()
            .selectNextControl()
            .adjust(AdjustmentDirection.INCREASE)
        val twoMeters = oneMeter
            .copy(control = InverseDistanceAlignmentControl.DISTANCE)
            .adjust(AdjustmentDirection.INCREASE)
            .selectNextControl()
            .selectNextControl()
            .adjust(AdjustmentDirection.DECREASE)
        val restored = twoMeters
            .copy(control = InverseDistanceAlignmentControl.DISTANCE)
            .adjust(AdjustmentDirection.DECREASE)

        assertEquals(109f, restored.b, 0.001f)
        assertEquals(115.94f, restored.k, 0.001f)
        assertEquals(108f, twoMeters.b, 0.001f)
        assertEquals(114.94f, twoMeters.k, 0.001f)
    }

    @Test
    fun `inverse distance records export sorted csv with calculated offsets`() {
        val state = InverseDistanceAlignmentState(
            records = mapOf(
                2 to InverseDistanceFitRecord(2, 97f, 70f),
                1 to InverseDistanceFitRecord(1, 98.88f, 72.43f),
            ),
        )

        assertEquals(
            "distance_m,b,k,x,y\n1,98.88,72.43,26.45,-234.00\n2,97.00,70.00,62.00,-234.00\n",
            state.toCsv(),
        )
    }
}
