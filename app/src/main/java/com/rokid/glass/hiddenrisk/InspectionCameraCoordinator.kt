package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.component.RokidCameraPreviewView

/**
 * HiddenRisk 巡检流的统一相机会话编排器。
 * 负责收口帧流 owner、预览绑定和异步回调的 generation 防护。
 */
object InspectionCameraCoordinator {

    enum class CameraOwner {
        LOADING,
        AI_INSPECTION,
        HAZARD_RECORD,
    }

    enum class CameraSessionState {
        IDLE,
        OPENING,
        READY_NO_PREVIEW,
        READY_WITH_PREVIEW,
        RELEASING,
    }

    internal data class SessionSnapshot(
        val owner: CameraOwner?,
        val state: CameraSessionState,
        val generation: Long,
    )

    /**
     * 仅维护 owner/state/generation 的纯状态机，便于单测覆盖。
     */
    internal class StateMachine {
        private var snapshot = SessionSnapshot(
            owner = null,
            state = CameraSessionState.IDLE,
            generation = 0L,
        )

        fun snapshot(): SessionSnapshot = snapshot

        fun beginAcquire(owner: CameraOwner, readyNow: Boolean): SessionSnapshot {
            val next = SessionSnapshot(
                owner = owner,
                state = if (readyNow) snapshot.state.coerceReadyState() else CameraSessionState.OPENING,
                generation = snapshot.generation + 1L,
            )
            snapshot = next
            return next
        }

        fun beginPreviewUpdate(owner: CameraOwner, readyNow: Boolean): SessionSnapshot? {
            if (snapshot.owner != owner) {
                return null
            }
            val next = SessionSnapshot(
                owner = owner,
                state = if (readyNow) snapshot.state.coerceReadyState() else CameraSessionState.OPENING,
                generation = snapshot.generation + 1L,
            )
            snapshot = next
            return next
        }

        fun beginRestart(owner: CameraOwner): SessionSnapshot? {
            if (snapshot.owner != owner) {
                return null
            }
            val next = SessionSnapshot(
                owner = owner,
                state = CameraSessionState.OPENING,
                generation = snapshot.generation + 1L,
            )
            snapshot = next
            return next
        }

        fun beginRelease(owner: CameraOwner): SessionSnapshot? {
            if (snapshot.owner != owner) {
                return null
            }
            val next = SessionSnapshot(
                owner = null,
                state = CameraSessionState.RELEASING,
                generation = snapshot.generation + 1L,
            )
            snapshot = next
            return next
        }

        fun finishRelease(generation: Long) {
            if (snapshot.generation != generation || snapshot.state != CameraSessionState.RELEASING) {
                return
            }
            snapshot = SessionSnapshot(
                owner = null,
                state = CameraSessionState.IDLE,
                generation = generation,
            )
        }

        fun finishReady(
            owner: CameraOwner,
            generation: Long,
            needPreview: Boolean,
        ): Boolean {
            if (snapshot.owner != owner || snapshot.generation != generation) {
                return false
            }
            snapshot = SessionSnapshot(
                owner = owner,
                state = if (needPreview) {
                    CameraSessionState.READY_WITH_PREVIEW
                } else {
                    CameraSessionState.READY_NO_PREVIEW
                },
                generation = generation,
            )
            return true
        }

        fun failOpening(
            owner: CameraOwner,
            generation: Long,
        ): Boolean {
            if (snapshot.owner != owner || snapshot.generation != generation) {
                return false
            }
            snapshot = SessionSnapshot(
                owner = owner,
                state = CameraSessionState.IDLE,
                generation = generation,
            )
            return true
        }

        fun resetForTest() {
            snapshot = SessionSnapshot(
                owner = null,
                state = CameraSessionState.IDLE,
                generation = 0L,
            )
        }

        private fun CameraSessionState.coerceReadyState(): CameraSessionState {
            return when (this) {
                CameraSessionState.READY_WITH_PREVIEW -> CameraSessionState.READY_WITH_PREVIEW
                CameraSessionState.READY_NO_PREVIEW -> CameraSessionState.READY_NO_PREVIEW
                else -> CameraSessionState.READY_NO_PREVIEW
            }
        }
    }

    private const val TAG = "InspectionCameraCoord"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val stateMachine = StateMachine()

    @Volatile
    private var activeNeedPreview = false

    @Volatile
    private var boundPreviewView: RokidCameraPreviewView? = null

    fun acquire(
        owner: CameraOwner,
        needPreview: Boolean,
        previewView: RokidCameraPreviewView? = null,
        enableRecovery: Boolean = false,
        onReady: (Boolean) -> Unit = {},
    ): Long {
        val readyNow = isFrameStreamReady()
        val snapshot = synchronized(lock) {
            activeNeedPreview = needPreview
            if (needPreview) {
                boundPreviewView = previewView
            } else {
                boundPreviewView = null
            }
            stateMachine.beginAcquire(owner, readyNow = readyNow)
        }
        logState(
            action = "acquire",
            snapshot = snapshot,
            owner = owner,
            needPreview = needPreview,
            previewAttached = previewView != null,
            extra = "enableRecovery=$enableRecovery",
        )
        if (readyNow) {
            mainHandler.post {
                applyPreviewBinding(
                    owner = owner,
                    generation = snapshot.generation,
                    needPreview = needPreview,
                    previewView = previewView,
                    onReady = onReady,
                )
            }
            return snapshot.generation
        }
        RokidFrameSource.startFrameStream { success ->
            mainHandler.post {
                handleStreamReady(
                    owner = owner,
                    generation = snapshot.generation,
                    success = success,
                    needPreview = needPreview,
                    previewView = previewView,
                    onReady = onReady,
                    reason = "acquire",
                )
            }
        }
        return snapshot.generation
    }

    fun release(owner: CameraOwner, reason: String): Long {
        val snapshot = synchronized(lock) {
            stateMachine.beginRelease(owner)
        } ?: run {
            val ignoredGeneration = getGeneration()
            Log.i(
                TAG,
                "ignore release owner=$owner reason=$reason activeOwner=${getOwner()} generation=$ignoredGeneration",
            )
            return ignoredGeneration
        }
        val previewToStop = synchronized(lock) {
            val preview = boundPreviewView
            boundPreviewView = null
            activeNeedPreview = false
            preview
        }
        logState(
            action = "release",
            snapshot = snapshot,
            owner = owner,
            needPreview = false,
            previewAttached = previewToStop != null,
            extra = "reason=$reason",
        )
        previewToStop?.let {
            Log.i(TAG, "previewUnbind owner=$owner generation=${snapshot.generation}")
            it.stopPreview()
        }
        RokidFrameSource.stopFrameStream()
        synchronized(lock) {
            stateMachine.finishRelease(snapshot.generation)
        }
        logState(
            action = "release_complete",
            snapshot = synchronized(lock) { stateMachine.snapshot() },
            owner = owner,
            needPreview = false,
            previewAttached = false,
            extra = "reason=$reason",
        )
        return snapshot.generation
    }

    fun updatePreview(
        owner: CameraOwner,
        needPreview: Boolean,
        previewView: RokidCameraPreviewView? = null,
        onReady: (Boolean) -> Unit = {},
    ): Long {
        val readyNow = isFrameStreamReady()
        val snapshot = synchronized(lock) {
            stateMachine.beginPreviewUpdate(owner, readyNow = readyNow)?.also { next ->
                activeNeedPreview = needPreview
                if (needPreview) {
                    boundPreviewView = previewView
                } else {
                    boundPreviewView = null
                }
            }
        } ?: run {
            Log.i(
                TAG,
                "ignore updatePreview owner=$owner activeOwner=${getOwner()} generation=${getGeneration()}",
            )
            return getGeneration()
        }
        logState(
            action = "updatePreview",
            snapshot = snapshot,
            owner = owner,
            needPreview = needPreview,
            previewAttached = previewView != null,
        )
        if (readyNow) {
            mainHandler.post {
                applyPreviewBinding(
                    owner = owner,
                    generation = snapshot.generation,
                    needPreview = needPreview,
                    previewView = previewView,
                    onReady = onReady,
                )
            }
            return snapshot.generation
        }
        RokidFrameSource.startFrameStream { success ->
            mainHandler.post {
                handleStreamReady(
                    owner = owner,
                    generation = snapshot.generation,
                    success = success,
                    needPreview = needPreview,
                    previewView = previewView,
                    onReady = onReady,
                    reason = "update_preview",
                )
            }
        }
        return snapshot.generation
    }

    fun restart(
        owner: CameraOwner,
        reason: String,
        needPreview: Boolean,
        previewView: RokidCameraPreviewView? = null,
        onReady: (Boolean) -> Unit = {},
    ): Long {
        val snapshot = synchronized(lock) {
            stateMachine.beginRestart(owner)?.also {
                activeNeedPreview = needPreview
                if (needPreview) {
                    boundPreviewView = previewView
                } else {
                    boundPreviewView = null
                }
            }
        } ?: run {
            Log.i(
                TAG,
                "ignore restart owner=$owner reason=$reason activeOwner=${getOwner()} generation=${getGeneration()}",
            )
            return getGeneration()
        }
        val previewToStop = synchronized(lock) { boundPreviewView }
        logState(
            action = "restart",
            snapshot = snapshot,
            owner = owner,
            needPreview = needPreview,
            previewAttached = previewView != null,
            extra = "reason=$reason",
        )
        previewToStop?.stopPreview()
        RokidFrameSource.restartFrameStream { success ->
            mainHandler.post {
                handleStreamReady(
                    owner = owner,
                    generation = snapshot.generation,
                    success = success,
                    needPreview = needPreview,
                    previewView = previewView,
                    onReady = onReady,
                    reason = "restart:$reason",
                )
            }
        }
        return snapshot.generation
    }

    fun setConsumerWaiting(owner: CameraOwner, waiting: Boolean) {
        if (getOwner() != owner) {
            return
        }
        Log.i(
            TAG,
            "consumerWaiting owner=$owner waiting=$waiting generation=${getGeneration()} state=${getState()}",
        )
    }

    fun reportFrameConsumed(owner: CameraOwner, timestamp: Long) {
        if (getOwner() != owner) {
            return
        }
        Log.v(
            TAG,
            "frameConsumed owner=$owner timestamp=$timestamp generation=${getGeneration()} state=${getState()}",
        )
    }

    fun getState(): CameraSessionState {
        return synchronized(lock) { stateMachine.snapshot().state }
    }

    fun getGeneration(): Long {
        return synchronized(lock) { stateMachine.snapshot().generation }
    }

    fun getOwner(): CameraOwner? {
        return synchronized(lock) { stateMachine.snapshot().owner }
    }

    fun isFrameStreamReady(): Boolean {
        return when (getState()) {
            CameraSessionState.READY_NO_PREVIEW,
            CameraSessionState.READY_WITH_PREVIEW,
            -> true

            CameraSessionState.IDLE,
            CameraSessionState.OPENING,
            CameraSessionState.RELEASING,
            -> false
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            stateMachine.resetForTest()
            activeNeedPreview = false
            boundPreviewView = null
        }
    }

    private fun handleStreamReady(
        owner: CameraOwner,
        generation: Long,
        success: Boolean,
        needPreview: Boolean,
        previewView: RokidCameraPreviewView?,
        onReady: (Boolean) -> Unit,
        reason: String,
    ) {
        if (!isCurrent(owner, generation)) {
            Log.i(
                TAG,
                "ignore late stream callback owner=$owner generation=$generation currentOwner=${getOwner()} currentGeneration=${getGeneration()} reason=$reason",
            )
            return
        }
        if (!success) {
            synchronized(lock) {
                stateMachine.failOpening(owner, generation)
            }
            logState(
                action = "stream_failed",
                snapshot = synchronized(lock) { stateMachine.snapshot() },
                owner = owner,
                needPreview = needPreview,
                previewAttached = previewView != null,
                extra = "reason=$reason",
            )
            onReady(false)
            return
        }
        applyPreviewBinding(
            owner = owner,
            generation = generation,
            needPreview = needPreview,
            previewView = previewView,
            onReady = onReady,
        )
    }

    private fun applyPreviewBinding(
        owner: CameraOwner,
        generation: Long,
        needPreview: Boolean,
        previewView: RokidCameraPreviewView?,
        onReady: (Boolean) -> Unit,
    ) {
        if (!isCurrent(owner, generation)) {
            Log.i(
                TAG,
                "ignore stale preview bind owner=$owner generation=$generation currentOwner=${getOwner()} currentGeneration=${getGeneration()}",
            )
            return
        }
        val previewToStop = synchronized(lock) {
            val previous = boundPreviewView
            if (!needPreview || previewView == null) {
                boundPreviewView = null
                previous
            } else if (previous !== previewView) {
                boundPreviewView = previewView
                previous?.takeIf { it !== previewView }
            } else {
                previous?.takeIf { false }
            }
        }
        previewToStop?.let {
            Log.i(TAG, "previewUnbind owner=$owner generation=$generation")
            it.stopPreview()
        }
        if (!needPreview || previewView == null) {
            synchronized(lock) {
                stateMachine.finishReady(owner, generation, needPreview = false)
            }
            logState(
                action = "preview_disabled",
                snapshot = synchronized(lock) { stateMachine.snapshot() },
                owner = owner,
                needPreview = false,
                previewAttached = false,
            )
            onReady(true)
            return
        }
        if (previewView.isPreviewStarted()) {
            synchronized(lock) {
                stateMachine.finishReady(owner, generation, needPreview = true)
            }
            logState(
                action = "preview_reused",
                snapshot = synchronized(lock) { stateMachine.snapshot() },
                owner = owner,
                needPreview = true,
                previewAttached = true,
            )
            onReady(true)
            return
        }
        Log.i(TAG, "previewBind owner=$owner generation=$generation")
        previewView.startPreview { success ->
            mainHandler.post {
                if (!isCurrent(owner, generation)) {
                    Log.i(
                        TAG,
                        "ignore late preview callback owner=$owner generation=$generation currentOwner=${getOwner()} currentGeneration=${getGeneration()} success=$success",
                    )
                    return@post
                }
                if (success) {
                    synchronized(lock) {
                        stateMachine.finishReady(owner, generation, needPreview = true)
                    }
                } else {
                    synchronized(lock) {
                        stateMachine.finishReady(owner, generation, needPreview = false)
                    }
                }
                logState(
                    action = "preview_ready",
                    snapshot = synchronized(lock) { stateMachine.snapshot() },
                    owner = owner,
                    needPreview = success,
                    previewAttached = true,
                    extra = "success=$success",
                )
                onReady(success)
            }
        }
    }

    private fun isCurrent(owner: CameraOwner, generation: Long): Boolean {
        val snapshot = synchronized(lock) { stateMachine.snapshot() }
        return snapshot.owner == owner && snapshot.generation == generation
    }

    private fun logState(
        action: String,
        snapshot: SessionSnapshot,
        owner: CameraOwner?,
        needPreview: Boolean,
        previewAttached: Boolean,
        extra: String = "",
    ) {
        val suffix = if (extra.isBlank()) "" else " $extra"
        Log.i(
            TAG,
            "$action owner=$owner generation=${snapshot.generation} state=${snapshot.state} needPreview=$needPreview previewAttached=$previewAttached frameOpen=${RokidFrameSource.isFrameStreamOpen()}$suffix",
        )
    }
}
