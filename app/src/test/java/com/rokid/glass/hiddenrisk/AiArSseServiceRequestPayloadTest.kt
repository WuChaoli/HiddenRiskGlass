package com.rokid.glass.hiddenrisk

import com.google.gson.Gson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiArSseServiceRequestPayloadTest {

    @Test
    fun requestPayload_forAdviceIncludesTextAndOmitsImage() {
        val json = Gson().toJson(
            AiArSseService.RequestPayload(
                task_id = "task-1",
                ctype = 2,
                text = "隐患描述：测试隐患\n整改建议：测试建议",
            ),
        )

        assertTrue(json.contains("\"ctype\":2"))
        assertTrue(json.contains("\"text\":\"隐患描述：测试隐患\\n整改建议：测试建议\""))
        assertFalse(json.contains("\"image\""))
    }
}
