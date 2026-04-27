package com.rokid.glass.utils

import android.os.SystemClock
import android.util.Log
import com.rokid.security.glass3.open.sdk.GlassSdk

/**
 * Rokid 离线 TTS 的轻量封装。
 */
object OfflineTtsPlayer {
    fun speak(ownerTag: String, message: String): Boolean {
        val speakStartElapsedMs = SystemClock.elapsedRealtime()
        if (message.isBlank()) {
            Log.w(ownerTag, "skip offline tts: empty message elapsedMs=$speakStartElapsedMs")
            return false
        }

        Log.i(
            ownerTag,
            "offline tts begin elapsedMs=$speakStartElapsedMs thread=${Thread.currentThread().name} messageLength=${message.length}",
        )

        val sdkReadyStartElapsedMs = SystemClock.elapsedRealtime()
        val sdkReady = runCatching { GlassSdk.isReady() }
            .onFailure { error ->
                Log.w(
                    ownerTag,
                    "skip offline tts: sdk readiness check failed elapsedMs=${SystemClock.elapsedRealtime() - sdkReadyStartElapsedMs} message=${error.message}",
                )
            }
            .getOrDefault(false)
        Log.i(
            ownerTag,
            "offline tts sdk ready checked ready=$sdkReady elapsedMs=${SystemClock.elapsedRealtime() - sdkReadyStartElapsedMs}",
        )
        if (!sdkReady) {
            Log.w(
                ownerTag,
                "skip offline tts: sdk not ready totalElapsedMs=${SystemClock.elapsedRealtime() - speakStartElapsedMs}",
            )
            return false
        }

        val serviceStartElapsedMs = SystemClock.elapsedRealtime()
        val ttsService = runCatching { GlassSdk.getGlassOfflineTtsService() }
            .onFailure { error ->
                Log.w(
                    ownerTag,
                    "skip offline tts: get service failed elapsedMs=${SystemClock.elapsedRealtime() - serviceStartElapsedMs} message=${error.message}",
                )
            }
            .getOrNull()
        Log.i(
            ownerTag,
            "offline tts service fetched available=${ttsService != null} elapsedMs=${SystemClock.elapsedRealtime() - serviceStartElapsedMs}",
        )
        if (ttsService == null) {
            Log.w(
                ownerTag,
                "skip offline tts: service unavailable totalElapsedMs=${SystemClock.elapsedRealtime() - speakStartElapsedMs}",
            )
            return false
        }

        val playStartElapsedMs = SystemClock.elapsedRealtime()
        return runCatching {
            ttsService.playTtsMsg(message)
            Log.i(
                ownerTag,
                "offline tts spoken playElapsedMs=${SystemClock.elapsedRealtime() - playStartElapsedMs} totalElapsedMs=${SystemClock.elapsedRealtime() - speakStartElapsedMs} message=$message",
            )
            true
        }.onFailure { error ->
            Log.e(
                ownerTag,
                "offline tts failed playElapsedMs=${SystemClock.elapsedRealtime() - playStartElapsedMs} totalElapsedMs=${SystemClock.elapsedRealtime() - speakStartElapsedMs} message=${error.message}",
                error,
            )
        }.getOrDefault(false)
    }
}
