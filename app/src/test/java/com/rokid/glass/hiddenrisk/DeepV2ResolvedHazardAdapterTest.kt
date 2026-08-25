package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepV2ResolvedHazardAdapterTest {

    @Test
    fun `adapter preserves all valid hazards and first normalized hazard`() {
        val presentation = presentation(
            listOf(
                hazard("det-1", "燃气灶", "HZ-1", "一般隐患"),
                hazard("det-2", "热水器", "HZ-2", "重大隐患"),
            ),
        )
        val image = DeepV2ImagePayload(byteArrayOf(1, 2, 3), 1512, 2016)

        val content = DeepV2ResolvedHazardAdapter.adapt(presentation, image)

        assertEquals(HazardSource.ONLINE, content.source)
        assertEquals(2, content.hazards.size)
        assertEquals("HZ-1", content.primaryHazard()?.hidNum)
        assertEquals("燃气灶", content.primaryHazard()?.displayTitle)
        assertArrayEquals(image.jpegBytes, content.jpegBytes)
        assertTrue(content.remoteSaveAllowed)
    }

    @Test
    fun `adapter maps advice to display and upload fields`() {
        val content = DeepV2ResolvedHazardAdapter.adapt(
            presentation(listOf(hazard("det", "燃气灶", "HZ", "一般隐患"))),
            DeepV2ImagePayload(byteArrayOf(1), 1512, 2016),
        )

        assertEquals("建议-HZ", content.primaryHazard()?.advice)
        assertEquals("建议-HZ", content.primaryHazard()?.uploadAdvice)
        assertEquals("依据-HZ", content.primaryHazard()?.lawBasis)
    }

    @Test
    fun `adapter preserves unknown level text`() {
        val content = DeepV2ResolvedHazardAdapter.adapt(
            presentation(listOf(hazard("det", "燃气灶", "HZ", "自定义等级"))),
            DeepV2ImagePayload(byteArrayOf(1), 1512, 2016),
        )

        assertEquals("自定义等级", content.primaryHazard()?.hidLevel)
    }

    private fun presentation(hazards: List<DeepV2PresentationHazard>): DeepV2Presentation {
        return DeepV2Presentation(
            targets = emptyList(),
            others = null,
            uploadHazards = hazards,
            suggestionHazardCode = hazards.firstOrNull()?.hazardCode,
        )
    }

    private fun hazard(
        labelId: String,
        label: String,
        code: String,
        level: String,
    ): DeepV2PresentationHazard {
        return DeepV2PresentationHazard(
            labelId = labelId,
            label = label,
            description = "描述-$code",
            level = level,
            lawBasis = "依据-$code",
            advice = "建议-$code",
            hazardCode = code,
            sourceIndex = 0,
        )
    }
}
