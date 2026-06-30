package com.rokid.glass.hiddenrisk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.rokid.glass.MyApplication
import com.rokid.glass.config.InspectionConfigRepository
import com.rokid.glass.input.GlassesWearStateMachine
import com.rokid.glass.input.WearStateManager

open class BaseGlassActivity : AppCompatActivity(), WearStateManager.Callback {

    /** 子类可覆盖为 false 来禁用自动常亮（如更新弹窗等短暂页面） */
    protected open val keepScreenOnEnabled: Boolean
        get() = true

    /** 子类覆盖为 true 后，可接收全局佩戴睡眠/恢复回调 */
    protected open val wearSleepEnabled: Boolean
        get() = false

    private var baseWearSnapshot: GlassesWearStateMachine.Snapshot? = null

    private val buttonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MyApplication.ORDER_ACTION_BUTTON_CLICK -> {
                    onGlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK)
                    if (isOrderedBroadcast) {
                        abortBroadcast()
                    }
                }

                MyApplication.ACTION_CLICK -> onGlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK)

                MyApplication.ORDER_ACTION_BUTTON_DOUBLE_CLICK -> {
                    onGlassKeyEvent(GlassKeyEvent.KEYCODE_DOUBLE_CLICK)
                    if (isOrderedBroadcast) {
                        abortBroadcast()
                    }
                }

                MyApplication.ACTION_DOUBLE_CLICK -> onGlassKeyEvent(GlassKeyEvent.KEYCODE_DOUBLE_CLICK)
            }
        }
    }

    private val buttonIntentFilter = IntentFilter().apply {
        addAction(MyApplication.ORDER_ACTION_BUTTON_CLICK)
        addAction(MyApplication.ORDER_ACTION_BUTTON_DOUBLE_CLICK)
        addAction(MyApplication.ACTION_CLICK)
        addAction(MyApplication.ACTION_DOUBLE_CLICK)
        addAction(MyApplication.ACTION_BUTTON_DOWN)
        addAction(MyApplication.ACTION_BUTTON_UP)
        priority = 100
    }

    private var isButtonReceiverRegistered = false

    override fun onStart() {
        super.onStart()

        if (isButtonReceiverRegistered) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(buttonReceiver, buttonIntentFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(buttonReceiver, buttonIntentFilter)
        }
        isButtonReceiverRegistered = true
    }

    override fun onStop() {
        if (isButtonReceiverRegistered) {
            runCatching { unregisterReceiver(buttonReceiver) }
            isButtonReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        Log.e("startActivity",this.javaClass.name)
        super.onResume()
        if (keepScreenOnEnabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (wearSleepEnabled) {
            WearStateManager.setGlobalEnabled(isGlobalWearSleepEnabled())
            WearStateManager.subscribe(
                owner = this,
                eligible = shouldEnableWearSleepNow(),
                callback = this,
            )
        }
    }

    override fun onPause() {
        if (wearSleepEnabled) {
            WearStateManager.unsubscribe(this)
        }
        if (keepScreenOnEnabled) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        super.onPause()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val handled = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> onGlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK)

            KeyEvent.KEYCODE_DPAD_LEFT -> onGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> onGlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND)
            KeyEvent.KEYCODE_BACK -> onGlassKeyEvent(GlassKeyEvent.KEYCODE_BACK)
            else -> false
        }

        return handled || super.onKeyUp(keyCode, event)
    }

    open fun onGlassKeyEvent(keyEvent: Int): Boolean {
        if (keyEvent == GlassKeyEvent.KEYCODE_DOUBLE_CLICK || keyEvent == GlassKeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return false
    }

    override fun onWearStateChanged(snapshot: GlassesWearStateMachine.Snapshot?) {
        val previousState = baseWearSnapshot?.state
        baseWearSnapshot = snapshot
        when (snapshot?.state) {
            GlassesWearStateMachine.State.SLEEP -> onWearSleep(snapshot)
            GlassesWearStateMachine.State.WAKE -> onWearWake(snapshot)
            GlassesWearStateMachine.State.ACTIVE -> onWearActive(snapshot, previousState)
            else -> Unit
        }
    }

    protected open fun shouldEnableWearSleepNow(): Boolean {
        return wearSleepEnabled && isGlobalWearSleepEnabled()
    }

    protected fun updateWearSleepEligibility(enabled: Boolean) {
        if (!wearSleepEnabled) return
        WearStateManager.setGlobalEnabled(isGlobalWearSleepEnabled())
        WearStateManager.updateOwnerEligibility(
            owner = this,
            eligible = enabled && isGlobalWearSleepEnabled(),
        )
    }

    protected fun reportWearRecoveryReady() {
        WearStateManager.reportRecoveryReady(this)
    }

    protected fun currentWearSnapshot(): GlassesWearStateMachine.Snapshot? {
        return WearStateManager.currentSnapshot()
    }

    protected fun isWearStateInteractionBlocked(): Boolean {
        return wearSleepEnabled && WearStateManager.isInteractionBlocked()
    }

    protected open fun onWearSleep(snapshot: GlassesWearStateMachine.Snapshot) = Unit

    protected open fun onWearWake(snapshot: GlassesWearStateMachine.Snapshot) = Unit

    protected open fun onWearActive(
        snapshot: GlassesWearStateMachine.Snapshot,
        previousState: GlassesWearStateMachine.State?,
    ) = Unit

    private fun isGlobalWearSleepEnabled(): Boolean {
        return InspectionConfigRepository.get().aiInspection.enableAutoSleepMonitoring
    }
}
