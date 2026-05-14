package com.rokid.glass.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * 自动睡眠控制层。
 * 页面只消费状态快照；陀螺仪、摘镜广播和 tick 推进统一在这里协调。
 */
class AutoSleepController(
    context: Context,
    private val ownerTag: String,
    wakingDurationMs: Long,
    sleepWarningDurationMs: Long,
    quietGyroMaxRad: Float,
    private val callback: Callback,
) {
    interface Callback {
        fun onAutoSleepStateChanged(snapshot: AutoSleepStateMachine.Snapshot?)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateMachine = AutoSleepStateMachine(
        config = AutoSleepStateMachine.Config(
            wakingDurationMs = wakingDurationMs,
            sleepWarningDurationMs = sleepWarningDurationMs,
        ),
    )
    private val motionTracker = HeadMotionStabilityTracker(
        context = context,
        stableDurationMs = wakingDurationMs,
        quietGyroMaxRad = quietGyroMaxRad,
    )
    private val wearMonitor = GlassesWearMonitor(
        context = context,
        listener = object : GlassesWearMonitor.Listener {
            override fun onWearStateChanged(isWorn: Boolean) {
                val now = SystemClock.elapsedRealtime()
                val updates = if (isWorn) {
                    stateMachine.onGlassesWorn(now)
                } else {
                    listOfNotNull(stateMachine.onGlassesRemoved(now))
                }
                dispatchSnapshots(updates)
            }
        },
    )
    private val tickRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            dispatchSnapshots(stateMachine.tick(now))
            scheduleNextTick()
        }
    }
    private val motionListener = object : HeadMotionStabilityTracker.Listener {
        override fun onStabilityChanged(isStable: Boolean, stableSinceMillis: Long?) {
            val now = SystemClock.elapsedRealtime()
            val snapshots = if (isStable) {
                listOfNotNull(stateMachine.onIdleQualified(now))
            } else {
                stateMachine.onUserActivity(AutoSleepStateMachine.UserActivitySource.HEAD_MOTION, now)
            }
            dispatchSnapshots(snapshots)
        }
    }

    private var attached = false
    private var enabled = false
    private var lastSnapshot: AutoSleepStateMachine.Snapshot? = null

    fun attach() {
        if (attached) {
            syncTrackers()
            scheduleNextTick()
            return
        }
        attached = true
        motionTracker.addListener(motionListener)
        wearMonitor.attach()
        syncTrackers()
        scheduleNextTick()
    }

    fun detach() {
        if (!attached) {
            return
        }
        attached = false
        mainHandler.removeCallbacks(tickRunnable)
        motionTracker.removeListener(motionListener)
        motionTracker.stop()
        wearMonitor.detach()
    }

    fun release() {
        setEnabled(false)
        detach()
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        mainHandler.removeCallbacks(tickRunnable)
        val now = SystemClock.elapsedRealtime()
        lastSnapshot = stateMachine.setEnabled(enabled, now)
        callback.onAutoSleepStateChanged(lastSnapshot)
        syncTrackers()
        scheduleNextTick()
    }

    fun notifyUserActivity(source: AutoSleepStateMachine.UserActivitySource) {
        val now = SystemClock.elapsedRealtime()
        dispatchSnapshots(stateMachine.onUserActivity(source, now))
        if (enabled && source != AutoSleepStateMachine.UserActivitySource.HEAD_MOTION) {
            motionTracker.reset()
        }
        scheduleNextTick()
    }

    fun markSleepHandled() {
        val snapshot = stateMachine.markSleepHandled(SystemClock.elapsedRealtime())
        dispatchSnapshots(listOfNotNull(snapshot))
    }

    fun currentSnapshot(): AutoSleepStateMachine.Snapshot? {
        return stateMachine.currentSnapshot(SystemClock.elapsedRealtime())
    }

    fun isPromptVisible(): Boolean = currentSnapshot()?.state == AutoSleepStateMachine.State.SLEEP_WARNING

    private fun syncTrackers() {
        if (!attached || !enabled) {
            motionTracker.stop()
            return
        }
        if (!motionTracker.start()) {
            Log.w(ownerTag, "自动睡眠陀螺仪不可用，无法启用睡眠监控")
        }
    }

    private fun dispatchSnapshots(snapshots: List<AutoSleepStateMachine.Snapshot>) {
        snapshots.forEach { snapshot ->
            lastSnapshot = snapshot
            callback.onAutoSleepStateChanged(snapshot)
        }
    }

    private fun scheduleNextTick() {
        mainHandler.removeCallbacks(tickRunnable)
        if (!attached || !enabled) {
            return
        }
        val snapshot = stateMachine.currentSnapshot(SystemClock.elapsedRealtime()) ?: return
        val delayMs = when (snapshot.state) {
            AutoSleepStateMachine.State.WAKING,
            AutoSleepStateMachine.State.SLEEP_WARNING -> 250L
            else -> return
        }
        mainHandler.postDelayed(tickRunnable, delayMs)
    }
}
