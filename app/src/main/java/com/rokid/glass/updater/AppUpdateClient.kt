package com.rokid.glass.updater

import android.util.Log
import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class AppUpdateClient(
    private val checkUrl: String = DEFAULT_CHECK_URL,
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
    private val httpClient: OkHttpClient = defaultHttpClient,
    private val gson: Gson = Gson(),
) {
    fun checkUpdate(nscode: String, currentVersionCode: Int): AppUpdateInfo? {
        Log.i(TAG, "checkUpdate nscodeEmpty=${nscode.isBlank()} currentVersionCode=$currentVersionCode")
        return try {
            fetchDynamic(nscode, currentVersionCode)
        } catch (error: Exception) {
            if (nscode.isNotBlank()) {
                Log.w(TAG, "dynamic update check failed for targeted update; no manifest fallback", error)
                throw if (error is IOException) {
                    error
                } else {
                    IOException("Dynamic update check failed for targeted update", error)
                }
            }
            Log.w(TAG, "dynamic update check failed without nscode; fallback to manifest", error)
            fetchLatest().takeIf { it.versionCode > currentVersionCode }
        }
    }

    @Throws(IOException::class)
    fun fetchLatest(): AppUpdateInfo {
        val request = Request.Builder()
            .url(manifestUrl)
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Update manifest request failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Update manifest body is empty")
            return parseUpdateInfoOrNull(body, "Update manifest")
                ?: throw IOException("Update manifest does not contain an update payload")
        }
    }

    @Throws(IOException::class)
    private fun fetchDynamic(nscode: String, currentVersionCode: Int): AppUpdateInfo? {
        val url = checkUrl.toHttpUrl().newBuilder()
            .addQueryParameter("nscode", nscode)
            .addQueryParameter("currentVersionCode", currentVersionCode.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        httpClient.newCall(request).execute().use { response ->
            Log.i(TAG, "dynamic update check httpCode=${response.code}")
            if (!response.isSuccessful) {
                throw IOException("Dynamic update check failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Dynamic update check body is empty")
            return parseUpdateInfoOrNull(body, "Dynamic update check")
        }
    }

    @Throws(IOException::class)
    private fun parseUpdateInfoOrNull(body: String, sourceName: String): AppUpdateInfo? {
        return try {
            val serverResponse = gson.fromJson(body, AppUpdateServerResponse::class.java)
                ?: throw IOException("$sourceName response is empty")
            serverResponse.toUpdateInfoOrNull()
        } catch (error: IOException) {
            throw error
        } catch (error: RuntimeException) {
            throw IOException("$sourceName response is invalid", error)
        }
    }

    companion object {
        private const val TAG = "AppUpdateClient"
        const val DEFAULT_CHECK_URL = "http://192.168.1.152:8080/api/v1/updates/check"
        const val DEFAULT_MANIFEST_URL = "http://192.168.1.152:8080/releases/latest/update.json"

        private val defaultHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }
}
