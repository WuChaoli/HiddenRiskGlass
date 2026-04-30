package com.rokid.glass.camera

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.rokid.glass.component.RokidCameraPreviewView

/**
 * 统一编排共享相机帧流恢复，页面只保留业务阶段与 UI 响应。
 */
class RokidCameraRecoveryController(
    private val mode: RecoveryMode,
    private val callback: Callback,
    private val previewView: RokidCameraPreviewView? = null,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val restartHandler: ((RecoveryIssue, (Boolean) -> Unit) -> Unit)? = null,
) : RokidCameraPreviewView.PreviewHealthListener {

    enum class RecoveryMode {
        PREVIEW_HEALTH,
        CONSUMER_TIMEOUT,
    }

    enum class RecoveryIssue {
        FIRST_FRAME_TIMEOUT,
        FRAME_STALLED,
        CONSUMER_CAPTURE_TIMEOUT,
    }

    enum class RecoveryState {
        RECOVERING,
        SUCCESS,
        ABANDONED,
    }

    interface Callback {
        fun onRecoveryStarted(issue: RecoveryIssue, attempt: Int, maxAttempts: Int)

        fun onRecoverySucceeded()

        fun onRecoveryAbandoned(issue: RecoveryIssue)
    }

    companion object {
        private const val TAG = "RokidCamRecovery"
        private const val MAX_AUTO_RECOVERY_ATTEMPTS = 3
        private const val CONSUMER_TIMEOUT_MS = 1000L
        private const val MAX_CONSUMER_TIMEOUTS = 3
    }

    @Volatile
    private var started = false

    @Volatile
    private var recoveryEnabled = false

    @Volatile
    private var recoveryInProgress = false

    private var autoRecoveryAttempts = 0
    private var consumerWaitActive = false
    private var consumerLastProgressAtElapsedMs = 0L
    private var consecutiveConsumerTimeouts = 0
    private var lastConsumedFrameTimestamp = 0L

    private val consumerTimeoutRunnable = Runnable { handleConsumerTimeoutTick() }

    fun startOrReuse(onReady: (Boolean) -> Unit = {}) {
        started = true
        previewView?.setPreviewHealthListener(this)
        if (mode == RecoveryMode.PREVIEW_HEALTH) {
            previewView?.setPreviewHealthMonitoringEnabled(false)
            previewView?.stopPreview()
        }
        clearConsumerWaitState()
        RokidFrameSource.startFrameStream { success ->
            mainHandler.post {
                if (!started) {
                    onReady(false)
                    return@post
                }
                if (!success) {
                    onReady(false)
                    return@post
                }
                val view = previewView
                if (mode != RecoveryMode.PREVIEW_HEALTH || view == null) {
                    onReady(true)
                    return@post
                }
                view.startPreview {
                    mainHandler.post previewStarted@{
                        if (!started) {
                            onReady(false)
                            return@previewStarted
                        }
                        view.setPreviewHealthMonitoringEnabled(recoveryEnabled)
                        onReady(true)
                    }
                }
            }
        }
    }

    fun stop() {
        started = false
        recoveryEnabled = false
        recoveryInProgress = false
        clearConsumerWaitState()
        previewView?.setPreviewHealthMonitoringEnabled(false)
        previewView?.setPreviewHealthListener(null)
        previewView?.stopPreview()
    }

    fun setRecoveryEnabled(enabled: Boolean) {
        recoveryEnabled = enabled
        if (mode == RecoveryMode.PREVIEW_HEALTH) {
            previewView?.setPreviewHealthMonitoringEnabled(enabled && started && !recoveryInProgress)
            return
        }
        if (!enabled) {
            clearConsumerWaitState()
        } else if (consumerWaitActive && !recoveryInProgress) {
            scheduleConsumerTimeoutCheck(CONSUMER_TIMEOUT_MS)
        }
    }

    fun resetRecoveryAttempts() {
        autoRecoveryAttempts = 0
        recoveryInProgress = false
        consecutiveConsumerTimeouts = 0
        previewView?.reportPreviewRecoveryState(RokidCameraPreviewView.PreviewRecoveryState.SUCCESS)
    }

    fun reportFrameConsumed(timestamp: Long) {
        if (mode != RecoveryMode.CONSUMER_TIMEOUT || timestamp <= 0L) {
            return
        }
        if (timestamp <= lastConsumedFrameTimestamp) {
            return
        }
        lastConsumedFrameTimestamp = timestamp
        consecutiveConsumerTimeouts = 0
        consumerLastProgressAtElapsedMs = SystemClock.elapsedRealtime()
    }

    fun notifyConsumerWaitStarted() {
        if (mode != RecoveryMode.CONSUMER_TIMEOUT) {
            return
        }
        consumerWaitActive = true
        consumerLastProgressAtElapsedMs = SystemClock.elapsedRealtime()
        if (recoveryEnabled && started && !recoveryInProgress) {
            scheduleConsumerTimeoutCheck(CONSUMER_TIMEOUT_MS)
        }
    }

    fun notifyConsumerWaitStopped() {
        if (mode != RecoveryMode.CONSUMER_TIMEOUT) {
            return
        }
        clearConsumerWaitState()
    }

    override fun onPreviewHealthIssue(issue: RokidCameraPreviewView.PreviewHealthIssue) {
        if (mode != RecoveryMode.PREVIEW_HEALTH || !started || !recoveryEnabled || recoveryInProgress) {
            return
        }
        beginRecovery(issue.toRecoveryIssue())
    }

    override fun onPreviewRecoveryStateChanged(state: RokidCameraPreviewView.PreviewRecoveryState) = Unit

    private fun beginRecovery(issue: RecoveryIssue) {
        if (!started || recoveryInProgress) {
            return
        }
        if (autoRecoveryAttempts >= MAX_AUTO_RECOVERY_ATTEMPTS) {
            abandonRecovery(issue)
            return
        }

        autoRecoveryAttempts += 1
        recoveryInProgress = true
        clearConsumerWaitState()
        previewView?.setPreviewHealthMonitoringEnabled(false)
        callback.onRecoveryStarted(issue, autoRecoveryAttempts, MAX_AUTO_RECOVERY_ATTEMPTS)
        previewView?.stopPreview()
        Log.i(TAG, "recovery start issue=$issue attempt=$autoRecoveryAttempts/$MAX_AUTO_RECOVERY_ATTEMPTS")

        val restartBlock = restartHandler ?: { _: RecoveryIssue, onReady: (Boolean) -> Unit ->
            RokidFrameSource.restartFrameStream(onReady = onReady)
        }
        restartBlock(issue) { success ->
            mainHandler.post {
                if (!started) {
                    recoveryInProgress = false
                    return@post
                }
                if (!success) {
                    handleRecoveryAttemptFailed(issue)
                    return@post
                }
                val view = previewView
                if (mode != RecoveryMode.PREVIEW_HEALTH || view == null) {
                    completeRecovery()
                    return@post
                }
                view.startPreview {
                    mainHandler.post previewRestarted@{
                        if (!started) {
                            recoveryInProgress = false
                            return@previewRestarted
                        }
                        completeRecovery()
                    }
                }
            }
        }
    }

    private fun handleRecoveryAttemptFailed(issue: RecoveryIssue) {
        previewView?.reportPreviewRecoveryState(RokidCameraPreviewView.PreviewRecoveryState.FAILED)
        recoveryInProgress = false
        Log.w(TAG, "recovery failed issue=$issue attempt=$autoRecoveryAttempts/$MAX_AUTO_RECOVERY_ATTEMPTS")
        if (autoRecoveryAttempts >= MAX_AUTO_RECOVERY_ATTEMPTS) {
            abandonRecovery(issue)
            return
        }
        beginRecovery(issue)
    }

    private fun completeRecovery() {
        recoveryInProgress = false
        consecutiveConsumerTimeouts = 0
        previewView?.reportPreviewRecoveryState(RokidCameraPreviewView.PreviewRecoveryState.SUCCESS)
        previewView?.setPreviewHealthMonitoringEnabled(recoveryEnabled)
        callback.onRecoverySucceeded()
    }

    private fun abandonRecovery(issue: RecoveryIssue) {
        recoveryEnabled = false
        recoveryInProgress = false
        clearConsumerWaitState()
        previewView?.setPreviewHealthMonitoringEnabled(false)
        previewView?.reportPreviewRecoveryState(RokidCameraPreviewView.PreviewRecoveryState.ABANDONED)
        Log.e(TAG, "recovery abandoned issue=$issue attempts=$autoRecoveryAttempts")
        callback.onRecoveryAbandoned(issue)
    }

    private fun handleConsumerTimeoutTick() {
        if (mode != RecoveryMode.CONSUMER_TIMEOUT || !started || !recoveryEnabled || !consumerWaitActive || recoveryInProgress) {
            return
        }
        val elapsedMs = SystemClock.elapsedRealtime() - consumerLastProgressAtElapsedMs
        if (elapsedMs < CONSUMER_TIMEOUT_MS) {
            scheduleConsumerTimeoutCheck(CONSUMER_TIMEOUT_MS - elapsedMs)
            return
        }
        consecutiveConsumerTimeouts += 1
        consumerLastProgressAtElapsedMs = SystemClock.elapsedRealtime()
        Log.w(
            TAG,
            "consumer timeout consecutive=$consecutiveConsumerTimeouts/$MAX_CONSUMER_TIMEOUTS",
        )
        if (consecutiveConsumerTimeouts >= MAX_CONSUMER_TIMEOUTS) {
            beginRecovery(RecoveryIssue.CONSUMER_CAPTURE_TIMEOUT)
            return
        }
        scheduleConsumerTimeoutCheck(CONSUMER_TIMEOUT_MS)
    }

    private fun scheduleConsumerTimeoutCheck(delayMs: Long) {
        mainHandler.removeCallbacks(consumerTimeoutRunnable)
        mainHandler.postDelayed(consumerTimeoutRunnable, delayMs.coerceAtLeast(0L))
    }

    private fun clearConsumerWaitState() {
        consumerWaitActive = false
        consecutiveConsumerTimeouts = 0
        consumerLastProgressAtElapsedMs = 0L
        mainHandler.removeCallbacks(consumerTimeoutRunnable)
    }

    private fun RokidCameraPreviewView.PreviewHealthIssue.toRecoveryIssue(): RecoveryIssue {
        return when (this) {
            RokidCameraPreviewView.PreviewHealthIssue.FIRST_FRAME_TIMEOUT -> RecoveryIssue.FIRST_FRAME_TIMEOUT
            RokidCameraPreviewView.PreviewHealthIssue.FRAME_STALLED -> RecoveryIssue.FRAME_STALLED
        }
    }
}
