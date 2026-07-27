package com.rokid.glass.network

import com.rokid.glass.InspectionFeatureFlags
import com.rokid.glass.utils.AppFileLogger
import java.io.IOException
import okhttp3.Interceptor

/** 应用巡检业务请求的最终网络总闸。 */
object InspectionNetworkAccessPolicy {
    private const val TAG = "InspectionNetworkPolicy"
    private const val ERROR_PREFIX = "offline_local_blocked"

    fun isAllowed(): Boolean = InspectionFeatureFlags.isBusinessNetworkAllowed()

    internal fun ensureAllowed(
        requestUrl: String,
        allowed: Boolean = isAllowed(),
    ) {
        if (allowed) return
        throw IOException("$ERROR_PREFIX:$requestUrl")
    }

    val interceptor: Interceptor = Interceptor { chain ->
        val request = chain.request()
        if (!isAllowed()) {
            runCatching {
                AppFileLogger.w(TAG, "blocked request url=${request.url}")
            }
        }
        ensureAllowed(request.url.toString())
        chain.proceed(request)
    }
}
