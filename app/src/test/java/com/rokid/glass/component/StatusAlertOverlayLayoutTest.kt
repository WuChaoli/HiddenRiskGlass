package com.rokid.glass.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StatusAlertOverlayLayoutTest {

    @Test
    fun `overlay root visibility is not hardcoded to gone`() {
        val root = parseLayoutRoot("src/main/res/layout/view_status_alert_overlay.xml")

        assertEquals("FrameLayout", root.tagName)
        assertFalse(root.hasAttribute("android:visibility"))
    }

    private fun parseLayoutRoot(relativePath: String): Element {
        val projectRoot = File(System.getProperty("user.dir"))
        val layoutFile = File(projectRoot, relativePath)
        require(layoutFile.isFile) { "layout file not found: ${layoutFile.absolutePath}" }

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
        }
        val builder = factory.newDocumentBuilder()
        return builder.parse(layoutFile).documentElement
    }
}
