package com.rokid.glass.utils

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.annotation.RawRes

/**
 * 本地提示音播放器。
 * 使用 raw 音频资源代替 Rokid 离线 TTS，并在新请求到达时抢占当前播放。
 */
object OfflineTtsPlayer {
    private const val PLAYER_TAG = "OfflineTtsPlayer"

    private val playerLock = Any()
    private var currentPlayer: MediaPlayer? = null
    private var currentOwnerTag: String? = null

    fun play(context: Context, ownerTag: String, @RawRes audioResId: Int): Boolean {
        return play(
            context = context,
            ownerTag = ownerTag,
            audioResId = audioResId,
            onComplete = null,
            onError = null,
        )
    }

    fun play(
        context: Context,
        ownerTag: String,
        @RawRes audioResId: Int,
        onComplete: (() -> Unit)? = null,
        onError: (() -> Unit)? = null,
    ): Boolean {
        val appContext = context.applicationContext
        synchronized(playerLock) {
            releasePlayerLocked(reason = "preempt", ownerTag = ownerTag)
            val mediaPlayer = runCatching { MediaPlayer.create(appContext, audioResId) }
                .onFailure { error ->
                    Log.e(ownerTag, "local audio create failed resId=$audioResId", error)
                }
                .getOrNull()

            if (mediaPlayer == null) {
                Log.w(ownerTag, "skip local audio: player unavailable resId=$audioResId")
                onError?.invoke()
                return false
            }

            currentPlayer = mediaPlayer
            currentOwnerTag = ownerTag
            mediaPlayer.setOnCompletionListener { completedPlayer ->
                var shouldNotifyComplete = false
                synchronized(playerLock) {
                    if (currentPlayer === completedPlayer) {
                        currentPlayer = null
                        currentOwnerTag = null
                        shouldNotifyComplete = true
                    }
                    runCatching { completedPlayer.release() }
                    Log.i(ownerTag, "local audio completed resId=$audioResId owner=$ownerTag")
                }
                if (shouldNotifyComplete) {
                    onComplete?.invoke()
                }
            }
            mediaPlayer.setOnErrorListener { failedPlayer, what, extra ->
                var shouldNotifyError = false
                synchronized(playerLock) {
                    if (currentPlayer === failedPlayer) {
                        currentPlayer = null
                        currentOwnerTag = null
                        shouldNotifyError = true
                    }
                    runCatching { failedPlayer.release() }
                    Log.e(ownerTag, "local audio failed resId=$audioResId what=$what extra=$extra owner=$ownerTag")
                }
                if (shouldNotifyError) {
                    onError?.invoke()
                }
                true
            }

            return runCatching {
                mediaPlayer.start()
                Log.i(ownerTag, "local audio started resId=$audioResId owner=$ownerTag")
                true
            }.onFailure { error ->
                if (currentPlayer === mediaPlayer) {
                    currentPlayer = null
                    currentOwnerTag = null
                }
                runCatching { mediaPlayer.release() }
                Log.e(ownerTag, "local audio start failed resId=$audioResId owner=$ownerTag", error)
                onError?.invoke()
            }.getOrDefault(false)
        }
    }

    fun release(ownerTag: String) {
        synchronized(playerLock) {
            if (currentPlayer == null) {
                Log.i(PLAYER_TAG, "local audio release skipped owner=$ownerTag reason=no_active_player")
                return
            }
            if (currentOwnerTag != ownerTag) {
                Log.i(
                    PLAYER_TAG,
                    "local audio release skipped owner=$ownerTag activeOwner=$currentOwnerTag reason=owner_mismatch",
                )
                return
            }
            releasePlayerLocked(reason = "manual_release", ownerTag = ownerTag)
        }
    }

    private fun releasePlayerLocked(reason: String, ownerTag: String) {
        val player = currentPlayer ?: return
        val activeOwnerTag = currentOwnerTag
        currentPlayer = null
        currentOwnerTag = null
        runCatching {
            if (player.isPlaying) {
                player.stop()
            }
        }.onFailure { error ->
            Log.w(ownerTag, "local audio stop failed reason=$reason", error)
        }
        runCatching { player.release() }
            .onFailure { error ->
                Log.w(ownerTag, "local audio release failed reason=$reason", error)
            }
        Log.i(
            PLAYER_TAG,
            "local audio released reason=$reason owner=$ownerTag activeOwner=$activeOwnerTag ownerMatched=${activeOwnerTag == ownerTag}",
        )
    }
}
