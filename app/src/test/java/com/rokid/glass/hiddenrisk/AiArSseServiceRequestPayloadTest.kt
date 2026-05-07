package com.rokid.glass.hiddenrisk

import com.google.gson.Gson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiArSseServiceRequestPayloadTest {

    @Test
    fun requestPayload_forItemDetectionIncludesImageAndCtype1() {
        val json = Gson().toJson(
            AiArSseService.RequestPayload(
                task_id = "task-item",
                ctype = 1,
                image = "base64-item",
            ),
        )

        assertTrue(json.contains("\"ctype\":1"))
        assertTrue(json.contains("\"image\":\"base64-item\""))
        assertFalse(json.contains("\"text\""))
    }

    @Test
    fun requestPayload_forSceneDetectionIncludesImageAndCtype2() {
        val json = Gson().toJson(
            AiArSseService.RequestPayload(
                task_id = "task-scene",
                ctype = 2,
                image = "base64-scene",
            ),
        )

        assertTrue(json.contains("\"ctype\":2"))
        assertTrue(json.contains("\"image\":\"base64-scene\""))
        assertFalse(json.contains("\"text\""))
    }

    @Test
    fun requestPayload_forAdviceIncludesTextAndOmitsImage() {
        val json = Gson().toJson(
            AiArSseService.RequestPayload(
                task_id = "task-1",
                ctype = 3,
                text = "隐患描述：测试隐患\n整改建议：测试建议",
            ),
        )

        assertTrue(json.contains("\"ctype\":3"))
        assertTrue(json.contains("\"text\":\"隐患描述：测试隐患\\n整改建议：测试建议\""))
        assertFalse(json.contains("\"image\""))
    }
}