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
        if (updateAvailable != true) {
            throw IllegalArgumentException("Dynamic update response missing updateAvailable")
        }
        return AppUpdateInfo(
            versionCode = requireNotNull(versionCode) { "Dynamic update response missing versionCode" },
            versionName = requireNotNull(versionName) { "Dynamic update response missing versionName" },
            apkUrl = requireNotNull(apkUrl) { "Dynamic update response missing apkUrl" },
            sha256 = requireNotNull(sha256) { "Dynamic update response missing sha256" },
            sizeBytes = requireNotNull(sizeBytes) { "Dynamic update response missing sizeBytes" },
            releaseNotes = requireNotNull(releaseNotes) { "Dynamic update response missing releaseNotes" },
            mandatory = mandatory ?: false,
        )
    }
}

data class AppUpdateCheckResult(
    val info: AppUpdateInfo?,
    val currentVersionCode: Int,
) {
    val hasUpdate: Boolean = info != null && info.versionCode > currentVersionCode
}
