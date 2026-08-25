package com.rokid.glass.hiddenrisk

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeepV2ProtocolTest {

    @Test
    fun `request contains only v2 fields`() {
        val json = JsonParser.parseString(
            DeepV2Protocol.buildRequestJson(
                DeepV2Request(
                    taskId = "task-001",
                    scene = "PLACE-001",
                    temp = 0.3,
                    image = "base64-image",
                ),
            ),
        ).asJsonObject

        assertEquals(setOf("task_id", "scene", "temp", "image"), json.keySet())
        assertEquals("task-001", json["task_id"].asString)
        assertEquals("PLACE-001", json["scene"].asString)
        assertEquals(0.3, json["temp"].asDouble, 0.0)
        assertFalse(json.has("stream"))
        assertFalse(json.has("text"))
    }

    @Test
    fun `response accepts numeric boolean and missing inter`() {
        val response = DeepV2Protocol.parseResponse(
            successResponse(
                detections = """
                    [
                      {"label":"燃气灶","bbox":[10,20,110,220],"score":0.9,"inter":0,"label_id":"det_001"},
                      {"label":"热水器","bbox":[20,30,120,230],"score":0.8,"inter":false,"label_id":"det_002"},
                      {"label":"软管","bbox":[30,40,130,240],"score":0.7,"label_id":"det_003"}
                    ]
                """.trimIndent(),
            ),
        )

        assertEquals(DeepV2Inter.NumberValue(0.0), response.detections[0].inter)
        assertEquals(DeepV2Inter.BooleanValue(false), response.detections[1].inter)
        assertEquals(null, response.detections[2].inter)
    }

    @Test
    fun `response maps hazards and preserves check items`() {
        val response = DeepV2Protocol.parseResponse(successResponse())

        assertEquals(0, response.code)
        assertEquals("success", response.message)
        assertEquals("task-001", response.taskId)
        assertEquals("deep_v2", response.type)
        assertEquals("det_001", response.hazards.single().labelId)
        assertEquals("描述", response.hazards.single().description)
        assertEquals("一般隐患", response.hazards.single().level)
        assertEquals("依据", response.hazards.single().lawBasis)
        assertEquals("建议", response.hazards.single().advice)
        assertEquals("HZ-001", response.hazards.single().hazardCode)
        assertEquals(1, response.checkItems.size)
        assertEquals("CHECK-001", response.checkItems.single()["检查编码"].asString)
        assertEquals(5.181, response.timeSeconds ?: 0.0, 0.0)
    }

    @Test
    fun `invalid detection is dropped without rejecting response`() {
        val response = DeepV2Protocol.parseResponse(
            successResponse(
                detections = """
                    [
                      {"label":"坏框","bbox":[10,20,30],"score":0.9,"inter":0,"label_id":"bad"},
                      {"label":"缺少标识","bbox":[10,20,110,220],"score":0.8,"inter":0},
                      {"label":"燃气灶","bbox":[10,20,110,220],"score":0.7,"inter":0,"label_id":"det_001"}
                    ]
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("det_001"), response.detections.map { it.labelId })
        assertEquals(2, response.detections.single().sourceIndex)
    }

    @Test
    fun `invalid hazard is dropped without rejecting response`() {
        val response = DeepV2Protocol.parseResponse(
            successResponse(
                hazards = """
                    [
                      {"隐患描述":"缺少标识","隐患等级":"一般隐患","主要依据":"依据","整改建议":"建议","隐患编号":"BAD"},
                      {"label_id":"det_001","隐患描述":"描述","隐患等级":"一般隐患","主要依据":"依据","整改建议":"建议","隐患编号":"HZ-001"}
                    ]
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("HZ-001"), response.hazards.map { it.hazardCode })
        assertEquals(1, response.hazards.single().sourceIndex)
    }

    @Test
    fun `nonzero code is rejected`() {
        assertProtocolFailure("""{"code":500,"msg":"failed","task_id":"task-001","type":"deep_v2","detections":[],"hazards":[],"check_items":[]}""")
    }

    @Test
    fun `wrong type is rejected`() {
        assertProtocolFailure("""{"code":0,"msg":"success","task_id":"task-001","type":"deep","detections":[],"hazards":[],"check_items":[]}""")
    }

    @Test
    fun `non object json is rejected`() {
        assertProtocolFailure("[]")
    }

    private fun assertProtocolFailure(body: String) {
        try {
            DeepV2Protocol.parseResponse(body)
            fail("Expected DeepV2ProtocolException")
        } catch (expected: DeepV2ProtocolException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }

    private fun successResponse(
        detections: String = """
            [
              {"label":"燃气灶","bbox":[10,20,110,220],"score":0.9,"inter":0,"label_id":"det_001"}
            ]
        """.trimIndent(),
        hazards: String = """
            [
              {"label_id":"det_001","隐患描述":"描述","隐患等级":"一般隐患","主要依据":"依据","整改建议":"建议","隐患编号":"HZ-001"}
            ]
        """.trimIndent(),
    ): String {
        return """
            {
              "code": 0,
              "msg": "success",
              "task_id": "task-001",
              "type": "deep_v2",
              "detections": $detections,
              "hazards": $hazards,
              "check_items": [{"检查编码":"CHECK-001"}],
              "time": 5.181
            }
        """.trimIndent()
    }
}
