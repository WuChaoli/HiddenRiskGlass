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
        if (updateAvailable != true) return null
        return AppUpdateInfo(
            versionCode = versionCode ?: return null,
            versionName = versionName ?: return null,
            apkUrl = apkUrl ?: return null,
            sha256 = sha256 ?: return null,
            sizeBytes = sizeBytes ?: return null,
            releaseNotes = releaseNotes ?: return null,
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
