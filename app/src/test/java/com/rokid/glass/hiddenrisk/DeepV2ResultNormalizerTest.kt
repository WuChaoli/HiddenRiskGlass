package com.rokid.glass.hiddenrisk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepV2ResultNormalizerTest {
    private val normalizer = DeepV2ResultNormalizer()

    @Test
    fun `detection without associated hazard is hidden`() {
        val result = normalizer.normalize(
            response(
                detections = listOf(
                    detection("matched", 0f, 0f, 100f, 100f, 0.9, 0),
                    detection("unmatched", 0f, 200f, 100f, 300f, 0.8, 1),
                ),
                hazards = listOf(hazard("matched", "HZ-1", "一般隐患", 0)),
            ),
        )

        assertEquals(listOf("matched"), result.targets.map { it.labelId })
    }

    @Test
    fun `multiple hazards use highest level in bbox label`() {
        val result = normalizer.normalize(
            response(
                detections = listOf(detection("det", 0f, 0f, 100f, 100f, 0.9, 0)),
                hazards = listOf(
                    hazard("det", "HZ-1", "一般隐患", 0),
                    hazard("det", "HZ-2", "重大隐患", 1),
                    hazard("det", "HZ-3", "重点问题", 2),
                ),
            ),
        )

        assertEquals("重大隐患", result.targets.single().highestLevel)
        assertEquals(listOf("HZ-1", "HZ-2", "HZ-3"), result.targets.single().hazards.map { it.hazardCode })
    }

    @Test
    fun `severity codes are normalized and unknown text is retained`() {
        val known = normalizer.normalize(
            response(
                detections = listOf(detection("det", 0f, 0f, 100f, 100f, 0.9, 0)),
                hazards = listOf(
                    hazard("det", "HZ-1", "1", 0),
                    hazard("det", "HZ-2", "3", 1),
                    hazard("det", "HZ-3", "2", 2),
                    hazard("det", "HZ-4", "自定义等级", 3),
                ),
            ),
        )

        assertEquals("重大隐患", known.targets.single().highestLevel)
        assertEquals(
            listOf("一般隐患", "重点问题", "重大隐患", "自定义等级"),
            known.targets.single().hazards.map { it.level },
        )
    }

    @Test
    fun `targets are ordered top then left and others is last`() {
        val detections = listOf(
            detection("bottom", 10f, 300f, 110f, 400f, 0.9, 0),
            detection("top-right", 200f, 10f, 300f, 110f, 0.9, 1),
            detection("top-left", 10f, 10f, 110f, 110f, 0.9, 2),
        )
        val hazards = detections.mapIndexed { index, detection ->
            hazard(detection.labelId, "HZ-$index", "一般隐患", index)
        } + hazard("others", "HZ-GLOBAL", "重点问题", 3)

        val result = normalizer.normalize(response(detections, hazards))

        assertEquals(listOf("top-left", "top-right", "bottom"), result.targets.map { it.labelId })
        assertEquals("others", result.others?.labelId)
        assertEquals("重点问题", result.others?.highestLevel)
        assertEquals(listOf("HZ-2", "HZ-1", "HZ-0", "HZ-GLOBAL"), result.uploadHazards.map { it.hazardCode })
    }

    @Test
    fun `duplicate label id prefers score then area then source order`() {
        val result = normalizer.normalize(
            response(
                detections = listOf(
                    detection("det", 0f, 0f, 300f, 300f, 0.8, 0),
                    detection("det", 0f, 0f, 100f, 100f, 0.9, 1),
                    detection("det", 0f, 0f, 200f, 200f, 0.9, 2),
                    detection("det", 0f, 0f, 200f, 200f, 0.9, 3),
                ),
                hazards = listOf(hazard("det", "HZ-1", "一般隐患", 0)),
            ),
        )

        assertEquals(2, result.targets.single().detectionIndex)
        assertEquals(40_000f, result.targets.single().bbox.area, 0f)
    }

    @Test
    fun `duplicate hazard code prefers associated score then bbox area`() {
        val detections = listOf(
            detection("low", 0f, 0f, 400f, 400f, 0.8, 0),
            detection("high-small", 0f, 0f, 100f, 100f, 0.9, 1),
            detection("high-large", 0f, 200f, 200f, 400f, 0.9, 2),
        )
        val result = normalizer.normalize(
            response(
                detections = detections,
                hazards = listOf(
                    hazard("low", "DUP", "一般隐患", 0),
                    hazard("high-small", "DUP", "一般隐患", 1),
                    hazard("high-large", "DUP", "一般隐患", 2),
                    hazard("high-large", "UNIQUE", "一般隐患", 3),
                ),
            ),
        )

        assertEquals(listOf("DUP", "UNIQUE"), result.targets.single().hazards.map { it.hazardCode })
        assertEquals("high-large", result.uploadHazards.first().labelId)
    }

    @Test
    fun `bbox hazard wins duplicate code over others`() {
        val result = normalizer.normalize(
            response(
                detections = listOf(detection("det", 0f, 0f, 100f, 100f, 0.1, 0)),
                hazards = listOf(
                    hazard("others", "DUP", "重大隐患", 0),
                    hazard("det", "DUP", "一般隐患", 1),
                ),
            ),
        )

        assertEquals("det", result.uploadHazards.single().labelId)
        assertNull(result.others)
    }

    @Test
    fun `blank hazard code is displayed but not uploaded`() {
        val result = normalizer.normalize(
            response(
                detections = listOf(detection("det", 0f, 0f, 100f, 100f, 0.9, 0)),
                hazards = listOf(
                    hazard("det", "", "一般隐患", 0),
                    hazard("det", "HZ-2", "重点问题", 1),
                ),
            ),
        )

        assertEquals(2, result.targets.single().hazards.size)
        assertEquals(listOf("HZ-2"), result.uploadHazards.map { it.hazardCode })
        assertEquals("HZ-2", result.suggestionHazardCode)
    }

    @Test
    fun `ordinary hazard without detection is ignored`() {
        val result = normalizer.normalize(
            response(
                detections = emptyList(),
                hazards = listOf(hazard("missing", "HZ-1", "一般隐患", 0)),
            ),
        )

        assertEquals(emptyList<DeepV2Target>(), result.targets)
        assertNull(result.others)
        assertEquals(emptyList<DeepV2PresentationHazard>(), result.uploadHazards)
    }

    private fun response(
        detections: List<DeepV2Detection>,
        hazards: List<DeepV2Hazard>,
    ): DeepV2Response {
        return DeepV2Response(
            code = 0,
            message = "success",
            taskId = "task",
            type = "deep_v2",
            detections = detections,
            hazards = hazards,
            checkItems = emptyList(),
            timeSeconds = 1.0,
        )
    }

    private fun detection(
        labelId: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        score: Double,
        sourceIndex: Int,
    ): DeepV2Detection {
        return DeepV2Detection(
            label = "label-$labelId",
            bbox = listOf(left.toDouble(), top.toDouble(), right.toDouble(), bottom.toDouble()),
            score = score,
            inter = null,
            labelId = labelId,
            sourceIndex = sourceIndex,
        )
    }

    private fun hazard(
        labelId: String,
        code: String,
        level: String,
        sourceIndex: Int,
    ): DeepV2Hazard {
        return DeepV2Hazard(
            labelId = labelId,
            description = "description-$code",
            level = level,
            lawBasis = "basis-$code",
            advice = "advice-$code",
            hazardCode = code,
            sourceIndex = sourceIndex,
        )
    }
}
