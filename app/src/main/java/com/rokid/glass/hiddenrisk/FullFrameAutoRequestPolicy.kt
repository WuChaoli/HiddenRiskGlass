package com.rokid.glass.hiddenrisk

internal object FullFrameAutoRequestPolicy {
    fun canRequest(placeCode: String?): Boolean = !placeCode.isNullOrBlank()
}
