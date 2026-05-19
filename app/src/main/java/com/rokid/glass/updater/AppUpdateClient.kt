package com.rokid.glass.updater

import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class AppUpdateClient(
    private val manifestUrl: String = DEFAULT_MANIFEST_URL,
    private val httpClient: OkHttpClient = defaultHttpClient,
    private val gson: Gson = Gson(),
) {
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
            return gson.fromJson(body, AppUpdateInfo::class.java)
        }
    }

    companion object {
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
