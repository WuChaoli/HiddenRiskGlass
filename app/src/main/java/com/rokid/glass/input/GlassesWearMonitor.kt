package com.rokid.glass.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

class GlassesWearMonitor(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onWearStateChanged(isWorn: Boolean)
    }

    private var attached = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_TAKE_STATUS_CHANGED) return
            when (intent.extras?.getString(EXTRA_GLASSES_TAKE_STATE)) {
                "1" -> listener.onWearStateChanged(true)
                "0" -> listener.onWearStateChanged(false)
            }
        }
    }

    fun attach() {
        if (attached) return
        attached = true
        val filter = IntentFilter(ACTION_TAKE_STATUS_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    fun detach() {
        if (!attached) return
        attached = false
        context.unregisterReceiver(receiver)
    }

    companion object {
        const val ACTION_TAKE_STATUS_CHANGED = "com.rokid.sprite.ACTION_TAKE_STATUS_CHANGED"
        const val EXTRA_GLASSES_TAKE_STATE = "glasses_take_state"
    }
}
