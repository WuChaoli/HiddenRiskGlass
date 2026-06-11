package com.rokid.glass.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassScannerLauncherTest {
    @Test
    fun isCameraError_detectsCameraKeywords() {
        assertTrue(GlassScannerLauncher.isCameraError("Higher-priority client using camera"))
        assertTrue(GlassScannerLauncher.isCameraError("CameraAccessException"))
        assertTrue(GlassScannerLauncher.isCameraError("ServiceSpecificException"))
        assertFalse(GlassScannerLauncher.isCameraError("Invalid QR code content"))
        assertFalse(GlassScannerLauncher.isCameraError(""))
    }
}
