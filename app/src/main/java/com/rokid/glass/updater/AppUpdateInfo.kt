package com.rokid.glass.updater

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val releaseNotes: String,
    val mandatory: Boolean = false,
)

data class AppUpdateServerResponse(
    val updateAvailable: Boolean? = null,
    val versionCode: Int? = null,
    val versionName: String? = null,
    val apkUrl: String? = null,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val releaseNotes: String? = null,
    val mandatory: Boolean? = null,
) {
    fun toUpdateInfoOrNull(): AppUpdateInfo? {
        if (updateAvailable == false) return null

        return AppUpdateInfo(
            versionCode = requireRequiredField(versionCode, "versionCode"),
            versionName = requireRequiredField(versionName, "versionName"),
            apkUrl = requireRequiredField(apkUrl, "apkUrl"),
            sha256 = requireRequiredField(sha256, "sha256"),
            sizeBytes = requireRequiredField(sizeBytes, "sizeBytes"),
            releaseNotes = requireRequiredField(releaseNotes, "releaseNotes"),
            mandatory = mandatory ?: false,
        )
    }

    private fun <T> requireRequiredField(value: T?, fieldName: String): T {
        return requireNotNull(value) { "Update response missing $fieldName" }
    }
}

data class AppUpdateCheckResult(
    val info: AppUpdateInfo?,
    val currentVersionCode: Int,
) {
    val hasUpdate: Boolean = info != null && info.versionCode > currentVersionCode
}
