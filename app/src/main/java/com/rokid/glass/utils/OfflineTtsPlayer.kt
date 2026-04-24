package com.rokid.glass.utils

import android.util.Log
import com.rokid.security.glass3.open.sdk.GlassSdk

/**
 * Rokid 离线 TTS 的轻量封装。
 */
object OfflineTtsPlayer {
    fun speak(ownerTag: String, message: String): Boolean {
        if (message.isBlank()) {
            Log.w(ownerTag, "skip offline tts: empty message")
            return false
        }

        val sdkReady = runCatching { GlassSdk.isReady() }
            .onFailure { error ->
                Log.w(ownerTag, "skip offline tts: sdk readiness check failed: ${error.message}")
            }
            .getOrDefault(false)
        if (!sdkReady) {
            Log.w(ownerTag, "skip offline tts: sdk not ready")
            return false
        }

        val ttsService = runCatching { GlassSdk.getGlassOfflineTtsService() }
            .onFailure { error ->
                Log.w(ownerTag, "skip offline tts: get service failed: ${error.message}")
            }
            .getOrNull()
        if (ttsService == null) {
            Log.w(ownerTag, "skip offline tts: service unavailable")
            return false
        }

        return runCatching {
            ttsService.playTtsMsg(message)
            Log.i(ownerTag, "offline tts spoken: $message")
            true
        }.onFailure { error ->
            Log.e(ownerTag, "offline tts failed: ${error.message}", error)
        }.getOrDefault(false)
    }
}
