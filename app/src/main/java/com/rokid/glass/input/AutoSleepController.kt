package com.rokid.glass.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 自动睡眠控制层。
 * 页面只声明检测态启停和回调，陀螺仪静止/活动判断在这里统一处理。
 */
class AutoSleepController(
    context: Context,
    private val ownerTag: String,
    idleBeforePromptMs: Long,
    private val promptTimeoutMs: Long,
    quietGyroMaxRad: Float,
    private val callback: Callback,
) {
    interface Callback {
        fun onSleepPromptShown()
        fun onSleepResumeRequested(source: AutoSleepStateMachine.UserActivitySource)
        fun onSleepTimeout()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateMachine = AutoSleepStateMachine(
        idleBeforePromptMs = idleBeforePromptMs,
        promptTimeoutMs = promptTimeoutMs,
    )
    private val motionTracker = HeadMotionStabilityTracker(
        context = context,
        stableDurationMs = idleBeforePromptMs,
        quietGyroMaxRad = quietGyroMaxRad,
    )
    private val timeoutRunnable = Runnable {
        dispatchEvents(stateMachine.tick(SystemClock.elapsedRealtime()))
    }
    private val motionListener = object : HeadMotionStabilityTracker.Listener {
        override fun onStabilityChanged(isStable: Boolean, stableSinceMillis: Long?) {
            if (isStable) {
                dispatchEvents(stateMachine.onIdleQualified(SystemClock.elapsedRealtime()))
            } else {
                notifyUserActivity(AutoSleepStateMachine.UserActivitySource.HEAD_MOTION)
            }
        }
    }

    private var attached = false
    private var enabled = false

    fun attach() {
        if (attached) {
            syncTracker()
            return
        }
        attached = true
        motionTracker.addListener(motionListener)
        syncTracker()
    }

    fun detach() {
        if (!attached) {
            return
        }
        attached = false
        mainHandler.removeCallbacks(timeoutRunnable)
        stateMachine.setEnabled(false)
        motionTracker.removeListener(motionListener)
        motionTracker.stop()
    }

    fun release() {
        setEnabled(false)
        detach()
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        mainHandler.removeCallbacks(timeoutRunnable)
        dispatchEvents(stateMachine.setEnabled(enabled))
        syncTracker()
    }

    fun notifyUserActivity(source: AutoSleepStateMachine.UserActivitySource) {
        mainHandler.removeCallbacks(timeoutRunnable)
        val events = stateMachine.notifyUserActivity(source)
        if (events.isNotEmpty()) {
            motionTracker.reset()
        } else if (enabled && source != AutoSleepStateMachine.UserActivitySource.HEAD_MOTION) {
            motionTracker.reset()
        }
        dispatchEvents(events)
    }

    fun isPromptVisible(): Boolean = stateMachine.isPromptVisible()

    private fun syncTracker() {
        if (!attached || !enabled) {
            motionTracker.stop()
            return
        }
        if (!motionTracker.start()) {
            Log.w(ownerTag, "自动睡眠陀螺仪不可用，无法启用睡眠监控")
        }
    }

    private fun dispatchEvents(events: List<AutoSleepStateMachine.Event>) {
        events.forEach { event ->
            when (event) {
                AutoSleepStateMachine.Event.PromptShown -> {
                    callback.onSleepPromptShown()
                    mainHandler.removeCallbacks(timeoutRunnable)
                    mainHandler.postDelayed(timeoutRunnable, promptTimeoutMs.coerceAtLeast(0L))
                }
                is AutoSleepStateMachine.Event.ResumeRequested -> {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    callback.onSleepResumeRequested(event.source)
                }
                AutoSleepStateMachine.Event.TimeoutReturnToMenu -> {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    callback.onSleepTimeout()
                }
            }
        }
    }
}
