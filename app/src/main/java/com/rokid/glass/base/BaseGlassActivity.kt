package com.rokid.ui.view.base

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.rokid.glass.base.GlassKeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Author: zhangshengwei
 * Date: 2025/5/13
 */
open class BaseGlassActivity : AppCompatActivity() {



    private lateinit var rootView: View
    private var TAG ="BaseGlassActivity"
    private var mainScope = MainScope()



    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        initContentView()
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        initContentView()
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        initContentView()
    }

    private fun initContentView() {
        rootView = findViewById(android.R.id.content)
        ViewCompat.addOnUnhandledKeyEventListener(rootView, mOnUnhandledKeyEventListener)
    }



    private val mOnUnhandledKeyEventListener = ViewCompat.OnUnhandledKeyEventListenerCompat { v, event ->

        Log.d("BaseGlassActivity", "KEYCODE_DPAD_DOWN:"+event.action+" "+event.keyCode)
        var ret = false
        when (event.action) {

            KeyEvent.ACTION_DOWN -> {
                when(event.keyCode){
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        ret = GlassKeyEvent(GlassKeyEvent.KEYCODE_DPAD_DOWN)
                        Log.d("BaseGlassActivity", "KEYCODE_DPAD_DOWN:")
                        return@OnUnhandledKeyEventListenerCompat true
                    }


                    KeyEvent.KEYCODE_DPAD_RIGHT ->{
                        Log.d("BaseGlassActivity", "KEYCODE_FRONT:")
                        ret = GlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
                        return@OnUnhandledKeyEventListenerCompat true
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT ->{
                        Log.d("BaseGlassActivity", "KEYCODE_BEHIND:")
                        ret = GlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND)
                        return@OnUnhandledKeyEventListenerCompat true
                    }


                    KeyEvent.KEYCODE_BACK ->{
                        Log.d("BaseGlassActivity", "KEYCODE_BACK:")
                        ret = GlassKeyEvent(GlassKeyEvent.KEYCODE_BACK)
                        return@OnUnhandledKeyEventListenerCompat ret
                    }
                }
            }
        }
        false
    }



    private var startTouchX = 0f
    private var startTouchY = 0f

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        Log.d("BaseGlassActivity", "dispatchTouchEvent:"+ev?.action)

        // 如果是双击后的ACTION_UP事件，直接消费掉不传递
        if (isDoubleClickDetected && ev?.action == MotionEvent.ACTION_UP) {
            isDoubleClickDetected = false
            Log.d("BaseGlassActivity", "拦截双击后的ACTION_UP事件")
            return true
        }

        when (ev?.action) {
            MotionEvent.ACTION_DOWN -> {
                startTouchX = ev.x
                startTouchY = ev.y
                Log.d("BaseGlassActivity", "dispatchTouchEvent:ACTION_DOWN")
            }
            MotionEvent.ACTION_UP -> {
                if (ev.x == startTouchX && ev.y == startTouchY) {
                    Log.d("BaseGlassActivity", "dispatchTouchEvent:home click")
                    ringHomeClick()
                } else if (ev.getX() == startTouchX && ev.getY() > startTouchY) {
                    Log.d("BaseGlassActivity", "指环后键单击")
                    GlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND)
                } else if (ev.getX() == startTouchX && ev.getY() < startTouchY) {
                    Log.d("BaseGlassActivity", "指环前键单击")
                    GlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
                }
                Log.d("BaseGlassActivity", "dispatchTouchEvent:ACTION_UP")
            }
        }

        return super.dispatchTouchEvent(ev)
    }


    private var lastHomeClickTime = 0L
    private var DoubleTime = 500L
    private var isDoubleClickDetected = false
    private var clickJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun ringHomeClick() {
        val curTime = System.currentTimeMillis()
        val timeDiff = curTime - lastHomeClickTime

        Log.e("BaseGlassActivity", "ringHomeClick->$timeDiff ms | curTime:$curTime | lastHomeClickTime:$lastHomeClickTime")

        // 取消之前未执行的单击协程
        clickJob?.cancel()
        clickJob = null
        

        if (timeDiff < DoubleTime) {
            Log.d("BaseGlassActivity", "判定为双击：间隔${timeDiff}ms")
            GlassKeyEvent(GlassKeyEvent.KEYCODE_DOUBLE_CLICK)
        } else {
            // 启动协程延迟发送单击事件
            clickJob = scope.launch {
                delay(DoubleTime)
                // 检查协程是否仍处于活跃状态
                if (isActive) {
                    Log.d("BaseGlassActivity", "判定为单击：间隔${timeDiff}ms")
                    GlassKeyEvent(GlassKeyEvent.KEYCODE_CLICK)
                }
            }
        }

        lastHomeClickTime = curTime
    }





    private var startX = 0f
    private var startY = 0f


    override fun onTouchEvent(event: MotionEvent?): Boolean {

        Log.d(TAG, "onTouchEvent:"+event?.action)
        event?.let { motionEvent ->
            val x = motionEvent.x
            val y = motionEvent.y

            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 记录按下位置
                    startX = x
                    startY = y

                    Log.d(TAG, "触摸开始")
                    return true
                }

                MotionEvent.ACTION_MOVE -> {


                    return true
                }

                MotionEvent.ACTION_UP -> {
                    // 触摸结束

                    if (x-startX<0){
                        Log.d(TAG, "指环后键双击")
//                        simulateKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT)
                        onGlassKeyEvent(GlassKeyEvent.KEYCODE_DOUBLE_BEHIND)
                    }else if(x-startX>0){
                        Log.d(TAG, "指环前键双击")
                        onGlassKeyEvent(GlassKeyEvent.KEYCODE_DOUBLE_FRONT)
                    }

                    if (y-startY>0){
                        Log.d(TAG, "指环后键")
//                        simulateKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT)
                        onGlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND)
                    }else if(y-startY<0){
                        Log.d(TAG, "指环前键")
//                        simulateKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT)
                        onGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
                    }


                    return true
                }

                else -> {}
            }
        }

        return super.onTouchEvent(event)
    }


    private var lastKeyTime: Long = 0
    private var lastKeyCode = -1
    // 防重复时间间隔（100ms）
    private val DEBOUNCE_INTERVAL: Long = 100

    fun GlassKeyEvent(keyEvent: Int): Boolean {
        Log.d("BaseGlassActivity", "onGlassKeyEvent:" + keyEvent)
        // 获取当前时间戳
        val currentTime = System.currentTimeMillis()

        // 检查是否在100ms内按下相同的键
        if (keyEvent == lastKeyCode && (currentTime - lastKeyTime) < DEBOUNCE_INTERVAL) {
            // 100ms内重复按相同键，视为重复事件，直接返回
            Log.d("BaseGlassActivity", "忽略重复按键: " + keyEvent)
            return true // 消费事件，不再传递
        }

        // 不是重复事件，更新记录的时间和键值
        lastKeyTime = currentTime
        lastKeyCode = keyEvent
        onGlassKeyEvent(keyEvent)
        return false
    }




     open fun onGlassKeyEvent(@GlassKeyEvent keyEvent: Int): Boolean {

        Log.d("BaseGlassActivity", "onGlassKeyEvent:$keyEvent")
         if (keyEvent == GlassKeyEvent.KEYCODE_DOUBLE_CLICK) {
             finish()
         }
         return false
     }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }










}