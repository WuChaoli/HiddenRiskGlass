package com.rokid.glass.input

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 自动睡眠控制层。
 * 页面只消费状态快照；摘镜广播和戴回后的 tick 推进统一在这里协调。
 */
class AutoSleepController(
    context: Context,
    private val callback: Callback,
) {
    interface Callback {
        fun onAutoSleepStateChanged(snapshot: AutoSleepStateMachine.Snapshot?)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateMachine = AutoSleepStateMachine(
        config = AutoSleepStateMachine.Config(
            wakeDurationMs = WAKE_DURATION_MS,
        ),
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

    private var attached = false
    private var enabled = false
    private var lastSnapshot: AutoSleepStateMachine.Snapshot? = null

    fun attach() {
        if (attached) {
            scheduleNextTick()
            return
        }
        attached = true
        wearMonitor.attach()
        scheduleNextTick()
    }

    fun detach() {
        if (!attached) {
            return
        }
        attached = false
        mainHandler.removeCallbacks(tickRunnable)
        wearMonitor.detach()
    }

    fun release() {
        setEnabled(false)
        detach()
    }

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) {
            scheduleNextTick()
            return
        }
        this.enabled = enabled
        mainHandler.removeCallbacks(tickRunnable)
        val now = SystemClock.elapsedRealtime()
        lastSnapshot = stateMachine.setEnabled(enabled, now)
        callback.onAutoSleepStateChanged(lastSnapshot)
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
            AutoSleepStateMachine.State.WAKE -> 250L
            else -> return
        }
        mainHandler.postDelayed(tickRunnable, delayMs)
    }

    companion object {
        private const val WAKE_DURATION_MS = 3_000L
    }
}
