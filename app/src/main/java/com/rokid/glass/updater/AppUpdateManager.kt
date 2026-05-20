package com.rokid.glass.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.rokid.glass.hiddenrisk.RokidSdkManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

class AppUpdateManager(
    private val context: Context,
    private val client: AppUpdateClient = AppUpdateClient(),
    private val httpClient: OkHttpClient = defaultHttpClient,
    private val serialNumberProvider: () -> String = { RokidSdkManager.getSerialNumber() },
) {
    /** 本次 app 期间不再弹出更新提示（非持久化） */
    fun skipCurrentSession() {
        synchronized(sessionStateLock) {
            sessionSkipped = true
        }
    }

    fun checkForUpdate(ignoreSkipped: Boolean = false): AppUpdateCheckResult {
        val currentVersion = getCurrentVersionCode()
        // 用户取消后在本次 app 期间不再弹出
        if (!ignoreSkipped && isSessionSkipped()) {
            return AppUpdateCheckResult(null, currentVersion)
        }
        val nscode = serialNumberProvider()
        Log.i(TAG, "checkForUpdate nscodeEmpty=${nscode.isBlank()} currentVersionCode=$currentVersion")
        val latest = client.checkUpdate(nscode, currentVersion)
            ?: return AppUpdateCheckResult(null, currentVersion)
        val effectiveInfo = if (
            latest.versionCode > currentVersion &&
            (ignoreSkipped || latest.mandatory || !isVersionSkipped(latest.versionCode))
        ) {
            latest
        } else {
            null
        }
        return AppUpdateCheckResult(effectiveInfo, currentVersion)
    }

    fun markAutoPromptShownIfAllowed(): Boolean {
        synchronized(sessionStateLock) {
            if (sessionSkipped || autoPromptShown) return false
            autoPromptShown = true
            return true
        }
    }

    fun skipVersion(versionCode: Int) {
        prefs.edit().putInt(KEY_SKIPPED_VERSION_CODE, versionCode).apply()
    }

    fun downloadAndInstall(info: AppUpdateInfo): File {
        val apkFile = downloadApk(info)
        val actualSha = sha256(apkFile)
        if (!actualSha.equals(info.sha256, ignoreCase = true)) {
            apkFile.delete()
            throw IOException("APK sha256 mismatch expected=${info.sha256} actual=$actualSha")
        }
        launchInstaller(apkFile)
        return apkFile
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    fun canRequestPackageInstalls(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()
    }

    private fun downloadApk(info: AppUpdateInfo): File {
        val request = Request.Builder().url(info.apkUrl).get().build()
        val targetDir = File(context.cacheDir, UPDATE_CACHE_DIR).apply { mkdirs() }
        val targetFile = File(targetDir, UPDATE_APK_NAME)
        val tempFile = File(targetDir, "$UPDATE_APK_NAME.download")
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("APK download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("APK download body is empty")
            tempFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output)
                }
            }
        }
        if (info.sizeBytes > 0 && tempFile.length() != info.sizeBytes) {
            tempFile.delete()
            throw IOException("APK size mismatch expected=${info.sizeBytes} actual=${tempFile.length()}")
        }
        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) {
            throw IOException("Failed to move APK into update cache")
        }
        return targetFile
    }

    private fun launchInstaller(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.appupdate.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val resolved = intent.resolveActivity(context.packageManager)
        if (resolved == null) {
            throw IOException("No package installer can handle APK install intent")
        }
        context.startActivity(intent)
    }

    private fun getCurrentVersionCode(): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    }

    private fun isVersionSkipped(versionCode: Int): Boolean {
        return prefs.getInt(KEY_SKIPPED_VERSION_CODE, -1) == versionCode
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            String.format(Locale.US, "%02x", byte)
        }
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val PREFS_NAME = "app_update"
        private const val KEY_SKIPPED_VERSION_CODE = "skipped_version_code"
        private const val UPDATE_CACHE_DIR = "app_updates"
        private const val UPDATE_APK_NAME = "latest.apk"
        private val sessionStateLock = Any()
        private var sessionSkipped = false
        private var autoPromptShown = false

        private fun isSessionSkipped(): Boolean {
            synchronized(sessionStateLock) {
                return sessionSkipped
            }
        }

        private val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }
}
