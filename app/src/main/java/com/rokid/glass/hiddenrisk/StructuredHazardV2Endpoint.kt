package com.rokid.glass.hiddenrisk

internal enum class StructuredHazardV2Endpoint(
    val expectedResponseType: String,
    val supportsScene: Boolean,
) {
    DEEP_V2("deep_v2", true),
    GENERAL_DEEP_V2("general_deep_v2", true),
    GM_V2("gm_v2", false),
}

internal data class StructuredHazardV2Route(
    val endpoint: StructuredHazardV2Endpoint,
    val scene: String?,
)

internal object StructuredHazardV2EndpointRouter {
    fun forItem(placeCode: String?): StructuredHazardV2Route {
        val scene = placeCode.normalizedScene()
        return if (scene == null) {
            StructuredHazardV2Route(StructuredHazardV2Endpoint.GM_V2, null)
        } else {
            StructuredHazardV2Route(StructuredHazardV2Endpoint.DEEP_V2, scene)
        }
    }

    fun forScene(placeCode: String?): StructuredHazardV2Route? {
        val scene = placeCode.normalizedScene() ?: return null
        return StructuredHazardV2Route(StructuredHazardV2Endpoint.GENERAL_DEEP_V2, scene)
    }

    private fun String?.normalizedScene(): String? = this?.trim()?.takeIf(String::isNotEmpty)
}
