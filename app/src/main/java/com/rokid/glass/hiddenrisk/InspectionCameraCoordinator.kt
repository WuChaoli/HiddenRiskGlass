package com.rokid.glass.hiddenrisk

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.glass.camera.CameraStreamProfile
import com.rokid.glass.camera.RokidFrameSource
import com.rokid.glass.component.RokidCameraPreviewView

/**
 * HiddenRisk 巡检流的统一相机会话编排器。
 * 负责收口帧流 owner、预览绑定和异步回调的 generation 防护。
 */
object InspectionCameraCoordinator {

    enum class CameraOwner {
        LOADING,
        ENTERPRISE_QR_SCAN,
        AI_INSPECTION,
        DEVICE_GUIDE,
        HAZARD_RECORD,
        RAW_CAMERA_DEBUG,
        FULL_FRAME_OVERLAY_TEST,
    }

    enum class CameraSessionState {
        IDLE,
        OPENING,
        READY_NO_PREVIEW,
        READY_WITH_PREVIEW,
        PAUSED_NO_PREVIEW,
        PAUSED_WITH_PREVIEW,
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

        fun beginAcquire(
            owner: CameraOwner,
            readyNow: Boolean,
            needPreview: Boolean,
        ): SessionSnapshot {
            val next = SessionSnapshot(
                owner = owner,
                state = if (readyNow) {
                    if (needPreview) CameraSessionState.READY_WITH_PREVIEW else CameraSessionState.READY_NO_PREVIEW
                } else {
                    CameraSessionState.OPENING
                },
                generation = snapshot.generation + 1L,
            )
            snapshot = next
            return next
        }

        fun beginPreviewUpdate(
            owner: CameraOwner,
            readyNow: Boolean,
            needPreview: Boolean,
        ): SessionSnapshot? {
            if (snapshot.owner != owner) {
                return null
            }
            val next = SessionSnapshot(
                owner = owner,
                state = if (readyNow) {
                    if (needPreview) CameraSessionState.READY_WITH_PREVIEW else CameraSessionState.READY_NO_PREVIEW
                } else {
                    CameraSessionState.OPENING
                },
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

        fun beginPause(owner: CameraOwner): SessionSnapshot? {
            if (snapshot.owner != owner) {
                return null
            }
            val previous = snapshot
            val next = SessionSnapshot(
                owner = null,
                state = if (previous.state == CameraSessionState.READY_WITH_PREVIEW ||
                    previous.state == CameraSessionState.PAUSED_WITH_PREVIEW
                ) {
                    CameraSessionState.PAUSED_WITH_PREVIEW
                } else {
                    CameraSessionState.PAUSED_NO_PREVIEW
                },
                generation = previous.generation + 1L,
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

        /** 强制释放当前 owner 和状态，进入 IDLE。增加 generation 使旧回调失效。 */
        fun forceRelease(): SessionSnapshot {
            val next = SessionSnapshot(
                owner = null,
                state = CameraSessionState.IDLE,
                generation = snapshot.generation + 1L,
            )
            snapshot = next
            return next
        }

        fun resetForTest() {
            snapshot = SessionSnapshot(
                owner = null,
                state = CameraSessionState.IDLE,
                generation = 0L,
            )
        }

    }

    private const val TAG = "InspectionCameraCoord"
    private const val RETRY_DELAY_MS = 300L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val stateMachine = StateMachine()

    @Volatile
    private var activeNeedPreview = false

    @Volatile
    private var boundPreviewView: RokidCameraPreviewView? = null

    // 当前活跃的逻辑请求 token，-1 表示无活跃请求
    @Volatile
    private var currentRequestToken: Long = -1L

    // requestToken 生成器
    private var nextRequestToken: Long = 1L

    private fun generateRequestToken(): Long {
        val token = nextRequestToken
        nextRequestToken++
        return token
    }

    fun acquire(
        owner: CameraOwner,
        needPreview: Boolean,
        previewView: RokidCameraPreviewView? = null,
        streamProfile: CameraStreamProfile? = null,
        enableRecovery: Boolean = false,
        onReady: (Boolean) -> Unit = {},
    ): Long {
        val readyNow = (isFrameStreamReady() || RokidFrameSource.isFrameStreamOpen()) &&
            (streamProfile == null || RokidFrameSource.isUsingProfile(streamProfile))
        val snapshot = synchronized(lock) {
            activeNeedPreview = needPreview
            if (needPreview) {
                boundPreviewView = previewView
            } else {
                boundPreviewView = null
            }
            stateMachine.beginAcquire(owner, readyNow = readyNow, needPreview = needPreview)
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
        RokidFrameSource.startFrameStream(streamProfile) { success ->
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

    /**
     * 业务页面按需获取相机。
     * 若当前已有其他 owner 占用，先执行强制移交（释放旧资源，再为新 owner 申请）。
     * 若获取失败，自动执行最多 3 次额外重试（共 4 次尝试）。
     * @return requestToken，页面可保存用于后续判断回调是否过期
     */
    fun acquireForActivity(
        owner: CameraOwner,
        needPreview: Boolean,
        previewView: RokidCameraPreviewView? = null,
        streamProfile: CameraStreamProfile? = null,
        enableRecovery: Boolean = false,
        onReady: (Boolean) -> Unit = {},
    ): Long {
        val token = generateRequestToken()
        currentRequestToken = token

        val current = synchronized(lock) { stateMachine.snapshot() }
        if (current.owner != null && current.owner != owner) {
            Log.w(TAG, "forceTransfer oldOwner=${current.owner} newOwner=$owner")
            forceReleaseCurrentOwner(reason = "force_transfer_to_${owner.name}")
        }
        acquireWithRetry(
            owner = owner,
            needPreview = needPreview,
            previewView = previewView,
            streamProfile = streamProfile,
            enableRecovery = enableRecovery,
            attempt = 1,
            maxAttempts = 4,
            requestToken = token,
            onReady = onReady,
        )
        return token
    }

    /**
     * 临时暂停：用于权限弹窗、系统遮挡或短暂进入后台。
     * 停止页面消费或预览，但不完整释放 NV21 和 owner。
     * requestToken 保持不变，允许后续恢复。
     */
    fun pauseTemporarily(owner: CameraOwner, reason: String): Long {
        return pause(owner = owner, reason = reason)
    }

    /**
     * 明确离开：用于返回、取消、完成、跳转其他 Activity 或主动 finish()。
     * 停止 Surface 和 NV21，清空 owner，并将 currentRequestToken 置为 -1
     * 以终止所有进行中的重试。
     */
    fun releaseForNavigation(owner: CameraOwner, reason: String): Long {
        // 仅在当前 owner 匹配时才取消进行中的请求，避免跨页面误伤
        // 否则 AiInspectionActivity.onDestroy 中的 releaseForNavigation 会重置
        // HazardRecordActivity 刚发起的 acquire 请求的 requestToken，导致回调被丢弃
        if (getOwner() == owner) {
            currentRequestToken = -1L
        }
        return release(owner = owner, reason = reason)
    }

    fun pause(owner: CameraOwner, reason: String): Long {
        val snapshot = synchronized(lock) {
            stateMachine.beginPause(owner)
        } ?: run {
            val ignoredGeneration = getGeneration()
            Log.i(
                TAG,
                "ignore pause owner=$owner reason=$reason activeOwner=${getOwner()} generation=$ignoredGeneration",
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
            action = "pause",
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
        // 安全交接：若 release 执行期间 generation 已变更，说明新 owner 已抢占，
        // 不应停止当前全局帧流，避免误停新 owner 的帧源。
        val currentGeneration = getGeneration()
        if (currentGeneration != snapshot.generation) {
            Log.i(
                TAG,
                "release skip stopFrameStream generation changed from ${snapshot.generation} to $currentGeneration owner=$owner",
            )
        } else {
            RokidFrameSource.stopFrameStream()
        }
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

    fun releaseAppCamera(reason: String): Long {
        val previous = synchronized(lock) { stateMachine.snapshot() }
        val releaseSnapshot = SessionSnapshot(
            owner = null,
            state = CameraSessionState.RELEASING,
            generation = previous.generation + 1L,
        )
        synchronized(lock) {
            activeNeedPreview = false
            boundPreviewView = null
        }
        logState(
            action = "release_app",
            snapshot = releaseSnapshot,
            owner = null,
            needPreview = false,
            previewAttached = false,
            extra = "reason=$reason",
        )
        RokidFrameSource.stopSurfacePreview()
        RokidFrameSource.stopFrameStream()
        synchronized(lock) {
            stateMachine.forceRelease()
        }
        logState(
            action = "release_app_complete",
            snapshot = synchronized(lock) { stateMachine.snapshot() },
            owner = null,
            needPreview = false,
            previewAttached = false,
            extra = "reason=$reason",
        )
        return releaseSnapshot.generation
    }

    private fun acquireWithRetry(
        owner: CameraOwner,
        needPreview: Boolean,
        previewView: RokidCameraPreviewView?,
        streamProfile: CameraStreamProfile?,
        enableRecovery: Boolean,
        attempt: Int,
        maxAttempts: Int,
        requestToken: Long,
        onReady: (Boolean) -> Unit,
    ) {
        Log.i(TAG, "acquire attempt=$attempt/$maxAttempts owner=$owner requestToken=$requestToken")
        acquire(
            owner = owner,
            needPreview = needPreview,
            previewView = previewView,
            streamProfile = streamProfile,
            enableRecovery = enableRecovery,
        ) { success ->
            // 检查当前请求是否已被取消（releaseForNavigation 会将 currentRequestToken 置为 -1）
            if (currentRequestToken != requestToken) {
                Log.i(TAG, "retry callback ignored: requestToken=$requestToken current=$currentRequestToken")
                return@acquire
            }
            if (success) {
                onReady(true)
                return@acquire
            }
            if (attempt >= maxAttempts) {
                Log.e(TAG, "acquire failed after $maxAttempts attempts owner=$owner")
                onReady(false)
                return@acquire
            }
            // 重试前完整清理 App 相机资源（注意：这会清空 owner，但不影响 requestToken 判断）
            releaseAppCamera(reason = "retry_cleanup_before_attempt_${attempt + 1}")
            mainHandler.postDelayed({
                // 再次检查 requestToken，而非检查 owner（owner 已被 releaseAppCamera 清空）
                if (currentRequestToken != requestToken) {
                    Log.i(TAG, "retry aborted: request cancelled owner=$owner requestToken=$requestToken")
                    return@postDelayed
                }
                acquireWithRetry(
                    owner = owner,
                    needPreview = needPreview,
                    previewView = previewView,
                    streamProfile = streamProfile,
                    enableRecovery = enableRecovery,
                    attempt = attempt + 1,
                    maxAttempts = maxAttempts,
                    requestToken = requestToken,
                    onReady = onReady,
                )
            }, RETRY_DELAY_MS)
        }
    }

    private fun forceReleaseCurrentOwner(reason: String) {
        val previewToStop = synchronized(lock) {
            val preview = boundPreviewView
            boundPreviewView = null
            activeNeedPreview = false
            preview
        }
        previewToStop?.stopPreview()
        RokidFrameSource.stopSurfacePreview()
        RokidFrameSource.stopFrameStream()
        synchronized(lock) {
            stateMachine.forceRelease()
        }
        Log.i(TAG, "forceReleaseCurrentOwner reason=$reason")
    }

    fun updatePreview(
        owner: CameraOwner,
        needPreview: Boolean,
        previewView: RokidCameraPreviewView? = null,
        onReady: (Boolean) -> Unit = {},
    ): Long {
        val readyNow = isFrameStreamReady()
        val snapshot = synchronized(lock) {
            stateMachine.beginPreviewUpdate(owner, readyNow = readyNow, needPreview = needPreview)?.also {
                activeNeedPreview = needPreview
                if (needPreview) {
                    boundPreviewView = previewView
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
            CameraSessionState.PAUSED_NO_PREVIEW,
            CameraSessionState.PAUSED_WITH_PREVIEW,
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
            currentRequestToken = -1L
            nextRequestToken = 1L
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
                null
            }
        }
        previewToStop?.let {
            Log.i(TAG, "previewUnbind owner=$owner generation=$generation")
            it.detachPreview()
            RokidFrameSource.stopSurfacePreview()
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
