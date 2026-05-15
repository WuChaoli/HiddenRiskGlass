package com.rokid.glass.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpUtilsTest {

    @Test
    fun apiResponse_isSuccess_acceptsCodeZeroAndTwoHundred() {
        assertTrue(HttpUtils.ApiResponse(code = 0, data = null, msg = null).isSuccess())
        assertTrue(HttpUtils.ApiResponse(code = 200, data = null, msg = null).isSuccess())
    }

    @Test
    fun apiResponse_isSuccess_rejectsOtherCodes() {
        assertFalse(HttpUtils.ApiResponse(code = 1, data = null, msg = null).isSuccess())
        assertFalse(HttpUtils.ApiResponse(code = null, data = null, msg = null).isSuccess())
    }
}
