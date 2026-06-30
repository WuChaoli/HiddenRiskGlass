package com.rokid.glass.component

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class InspectionPromptLayoutVisibilityTest {

    @Test
    fun `ai inspection prompt views are hidden by default`() {
        val root = parseLayoutRoot("src/main/res/layout/activity_ai_inspection.xml")

        assertViewVisibility(root, "operationGuideDetecting", "gone")
        assertViewVisibility(root, "operationGuideStream", "gone")
    }

    @Test
    fun `inspection end report prompt views are hidden by default`() {
        val root = parseLayoutRoot("src/main/res/layout/activity_inspection_end_report.xml")

        assertViewVisibility(root, "operationGuideEnd", "gone")
        assertViewVisibility(root, "bottomPromptEnd", "gone")
    }

    @Test
    fun `menu prompt views are hidden by default`() {
        val aiMenuRoot = parseLayoutRoot("src/main/res/layout/activity_ai_inspection_menu.xml")
        val inspectionModeRoot = parseLayoutRoot("src/main/res/layout/activity_inspection_mode.xml")

        assertViewVisibility(aiMenuRoot, "layoutBottomVoiceHint", "gone")
        assertViewVisibility(inspectionModeRoot, "tvBottomHint", "gone")
    }

    @Test
    fun `scan bottom hints are hidden by default`() {
        val enterpriseRoot = parseLayoutRoot("src/main/res/layout/activity_enterprise_qr_scan.xml")
        val enterpriseInfoRoot = parseLayoutRoot("src/main/res/layout/activity_enterprise_info.xml")

        assertViewVisibility(enterpriseRoot, "bottomHints", "gone")
        assertViewVisibility(enterpriseInfoRoot, "bottomHints", "gone")
    }

    private fun assertViewVisibility(
        root: Element,
        id: String,
        expectedVisibility: String,
    ) {
        val view = findElementById(root, id)
        requireNotNull(view) { "view not found: $id" }
        assertEquals(expectedVisibility, view.getAttribute("android:visibility"))
    }

    private fun findElementById(
        element: Element,
        id: String,
    ): Element? {
        if (element.getAttribute("android:id") == "@+id/$id") {
            return element
        }
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType != Node.ELEMENT_NODE) {
                continue
            }
            val found = findElementById(child as Element, id)
            if (found != null) {
                return found
            }
        }
        return null
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
