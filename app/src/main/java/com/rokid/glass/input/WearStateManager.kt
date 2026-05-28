package com.rokid.glass.input

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * 全局佩戴状态管理器。
 * 只维护佩戴状态和当前前台页面回调，不直接处理页面业务资源。
 */
object WearStateManager {
    interface Callback {
        fun onWearStateChanged(snapshot: GlassesWearStateMachine.Snapshot?)
    }

    private const val TAG = "WearStateManager"

    private val stateMachine = GlassesWearStateMachine()

    private var monitor: GlassesWearMonitor? = null
    private var initialized = false
    private var globalEnabled = false
    private var activeOwner: Any? = null
    private var activeCallback: Callback? = null
    private var activeOwnerEligible = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        monitor = GlassesWearMonitor(
            context = context.applicationContext,
            listener = object : GlassesWearMonitor.Listener {
                override fun onWearStateChanged(isWorn: Boolean) {
                    val now = SystemClock.elapsedRealtime()
                    val snapshot = if (isWorn) {
                        stateMachine.onGlassesWorn(now)
                    } else {
                        stateMachine.onGlassesRemoved(now)
                    }
                    dispatchToActiveOwner(snapshot)
                }
            },
        )
        monitor?.attach()
    }

    fun setGlobalEnabled(enabled: Boolean) {
        if (globalEnabled == enabled) return
        if (!enabled && activeOwnerEligible) {
            activeCallback?.onWearStateChanged(null)
        }
        globalEnabled = enabled
        val snapshot = stateMachine.setEnabled(enabled, SystemClock.elapsedRealtime())
        dispatchToActiveOwner(snapshot)
    }

    fun subscribe(owner: Any, eligible: Boolean, callback: Callback) {
        activeOwner = owner
        activeCallback = callback
        activeOwnerEligible = eligible
        dispatchCurrentSnapshot()
    }

    fun updateOwnerEligibility(owner: Any, eligible: Boolean) {
        if (activeOwner !== owner) return
        if (activeOwnerEligible == eligible) return
        activeOwnerEligible = eligible
        if (!eligible) {
            activeCallback?.onWearStateChanged(null)
            return
        }
        dispatchCurrentSnapshot()
    }

    fun unsubscribe(owner: Any) {
        if (activeOwner !== owner) return
        activeOwner = null
        activeCallback = null
        activeOwnerEligible = false
    }

    fun reportRecoveryReady(owner: Any) {
        if (activeOwner !== owner) {
            Log.d(TAG, "ignore recovery ready from inactive owner=${owner.javaClass.simpleName}")
            return
        }
        dispatchToActiveOwner(stateMachine.onRecoveryReady(SystemClock.elapsedRealtime()))
    }

    fun currentSnapshot(): GlassesWearStateMachine.Snapshot? {
        return stateMachine.currentSnapshot()
    }

    fun isInteractionBlocked(): Boolean {
        return stateMachine.isInteractionBlocked()
    }

    private fun dispatchCurrentSnapshot() {
        dispatchToActiveOwner(stateMachine.currentSnapshot())
    }

    private fun dispatchToActiveOwner(snapshot: GlassesWearStateMachine.Snapshot?) {
        if (!globalEnabled || !activeOwnerEligible) return
        activeCallback?.onWearStateChanged(snapshot)
    }
}
