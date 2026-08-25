package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredHazardResultSessionTest {
    @Test
    fun `sources preserve their original save policies`() {
        assertEquals(StructuredHazardSavePolicy(true, true), StructuredHazardSource.AUTO_ITEM.savePolicy)
        assertEquals(StructuredHazardSavePolicy(true, true), StructuredHazardSource.MANUAL.savePolicy)
        assertEquals(StructuredHazardSavePolicy(true, true), StructuredHazardSource.SCENE.savePolicy)
        assertEquals(StructuredHazardSavePolicy(true, false), StructuredHazardSource.HAZARD_RECORD.savePolicy)
    }

    @Test
    fun `session exposes target and others page counts without synthetic bbox`() {
        val session = session(
            source = StructuredHazardSource.SCENE,
            presentation = DeepV2Presentation(
                targets = listOf(target(hazards = listOf(hazard("HZ-1"), hazard("HZ-2")))),
                others = DeepV2GlobalHazards(hazards = listOf(hazard("HZ-3")), highestLevel = "一般隐患"),
                uploadHazards = listOf(hazard("HZ-1"), hazard("HZ-2"), hazard("HZ-3")),
                suggestionHazardCode = "HZ-1",
            ),
        )

        assertArrayEquals(intArrayOf(2, 1), session.pageCounts())
        assertEquals(1, session.presentation.targets.size)
        assertEquals("others", session.presentation.others?.labelId)
    }

    @Test
    fun `session copies frozen image and preserves source policy`() {
        val bytes = byteArrayOf(1, 2, 3)
        val session = session(StructuredHazardSource.HAZARD_RECORD, imageBytes = bytes)
        bytes[0] = 9

        assertArrayEquals(byteArrayOf(1, 2, 3), session.imagePayload.jpegBytes)
        assertFalse(session.source.savePolicy.requestSuggestionChecks)
        assertTrue(session.source.savePolicy.upload)
    }

    @Test
    fun `session converts all valid upload hazards`() {
        val session = session(
            source = StructuredHazardSource.MANUAL,
            presentation = DeepV2Presentation(
                targets = emptyList(),
                others = DeepV2GlobalHazards(
                    hazards = listOf(hazard("HZ-1"), hazard("HZ-2")),
                    highestLevel = "一般隐患",
                ),
                uploadHazards = listOf(hazard("HZ-1"), hazard("HZ-2")),
                suggestionHazardCode = "HZ-1",
            ),
        )

        assertEquals(listOf("HZ-1", "HZ-2"), session.toResolvedHazardContent().hazards.map { it.hidNum })
    }

    private fun session(
        source: StructuredHazardSource,
        presentation: DeepV2Presentation = DeepV2Presentation(
            targets = emptyList(),
            others = DeepV2GlobalHazards(hazards = listOf(hazard("HZ-1")), highestLevel = "一般隐患"),
            uploadHazards = listOf(hazard("HZ-1")),
            suggestionHazardCode = "HZ-1",
        ),
        imageBytes: ByteArray = byteArrayOf(1),
    ) = StructuredHazardResultSession(
        source = source,
        imagePayload = DeepV2ImagePayload(imageBytes, 1200, 1600),
        presentation = presentation,
        requestId = 7L,
        epoch = 3L,
    )

    private fun target(hazards: List<DeepV2PresentationHazard>) = DeepV2Target(
        labelId = "det-1",
        label = "燃气灶",
        bbox = DeepV2BoundingBox(1f, 2f, 3f, 4f),
        detectionScore = 0.9,
        detectionIndex = 0,
        highestLevel = "一般隐患",
        hazards = hazards,
    )

    private fun hazard(code: String) = DeepV2PresentationHazard(
        labelId = "others",
        label = "全局隐患",
        description = "描述-$code",
        level = "一般隐患",
        lawBasis = "依据-$code",
        advice = "建议-$code",
        hazardCode = code,
        sourceIndex = 0,
    )
}
