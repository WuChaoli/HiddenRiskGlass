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

open class BaseGlassActivity : AppCompatActivity() {

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
}
