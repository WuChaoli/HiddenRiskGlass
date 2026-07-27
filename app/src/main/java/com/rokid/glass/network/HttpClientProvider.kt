package com.rokid.glass.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 共享 OkHttpClient 单例，统一超时配置并提供连接池复用。
 *
 * 项目中原有 6 处独立的 OkHttpClient.Builder() 创建，各自配置超时参数。
 * 统一为以下两个客户端后，OkHttp 内部会自动复用连接池与线程池，
 * 减少资源浪费并降低连接建立开销。
 */
object HttpClientProvider {

    /** 巡检 API 客户端（30s 超时，连接池复用） */
    val inspectionClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(InspectionNetworkAccessPolicy.interceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** SSE 长连接客户端（无读超时，适合 EventSource 和长时间 HTTP 回退） */
    val sseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(InspectionNetworkAccessPolicy.interceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // SSE 无超时
            .retryOnConnectionFailure(true)
            .build()
    }
}
