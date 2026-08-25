package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepV2BBoxGeometryTest {
    @Test
    fun wideAndTallBoxes_keepCornerRatio() {
        val wide = DeepV2BBoxGeometry.compute(RectFModel(0f, 0f, 200f, 80f), 27f, 15f)
        val tall = DeepV2BBoxGeometry.compute(RectFModel(0f, 0f, 80f, 200f), 27f, 15f)

        assertEquals(wide.cornerLength, tall.cornerLength, 0.001f)
        assertEquals(
            wide.cornerRadius / wide.cornerLength,
            tall.cornerRadius / tall.cornerLength,
            0.001f,
        )
    }

    @Test
    fun tinyBox_scalesCornersTogetherAndKeepsSegmentsNonNegative() {
        val shape = DeepV2BBoxGeometry.compute(RectFModel(0f, 0f, 20f, 16f), 27f, 15f)

        assertTrue(shape.cornerLength <= 8f)
        assertEquals(15f / 27f, shape.cornerRadius / shape.cornerLength, 0.001f)
        assertTrue(shape.horizontalSegmentLength >= 0f)
        assertTrue(shape.verticalSegmentLength >= 0f)
    }
}
