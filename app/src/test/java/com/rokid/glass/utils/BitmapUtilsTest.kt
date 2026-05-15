package com.rokid.glass.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BitmapUtilsTest {

    @Test
    fun resizeNv21Nearest_downscalesYAndUvPlanes() {
        val source = byteArrayOf(
            0, 1, 2, 3,
            4, 5, 6, 7,
            8, 9, 10, 11,
            12, 13, 14, 15,
            100, 101, 102, 103,
            104, 105, 106, 107,
        )

        val resized = BitmapUtils.resizeNv21Nearest(
            nv21 = source,
            width = 4,
            height = 4,
            targetWidth = 2,
            targetHeight = 2,
        )

        assertArrayEquals(
            byteArrayOf(
                0, 2,
                8, 10,
                100, 101,
            ),
            resized,
        )
    }

    @Test
    fun resizeNv21Nearest_rejectsOddDimensionsForCompatibilityFallback() {
        val resized = BitmapUtils.resizeNv21Nearest(
            nv21 = ByteArray(9),
            width = 3,
            height = 2,
            targetWidth = 2,
            targetHeight = 2,
        )

        assertNull(resized)
    }
}
