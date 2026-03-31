package com.rokid.glass


import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.rokid.glass.annotation.MenuConfigType
import com.rokid.glass.base.BaseActivity
import com.rokid.glass.base.GlassKeyEvent
import com.rokid.glass.component.MenuItem
import com.rokid.glass.data.GlobalData
import com.rokid.glass.utils.GlassSdkUtils
import com.rokid.glass.utils.collect
import com.rokid.glesse.R
import com.rokid.glesse.databinding.ActivityHomeBinding
import com.rokid.security.glass3.open.sdk.GlassSdk
import com.rokid.security.glass3.sdk.base.data.offlineCmd.bean.VoiceAction
import com.rokid.security.glass3.sdk.base.data.offlineCmd.listener.IVoiceCallback
import com.rokid.security.zjsj.home.adapter.HomeAdapter
import kotlinx.coroutines.flow.drop
import java.lang.reflect.Method
import kotlin.math.ceil


class HomeActivity : BaseActivity() {

    private val TAG = "HomeActivity"
    private lateinit var binding: ActivityHomeBinding
    private var workHomeAdapter: HomeAdapter? = null
    private val menuList: MutableList<MenuItem> = ArrayList()
    private val showList: MutableList<MenuItem> = ArrayList()
    private var focusPosition = 0
    private val showMenuMax = 3
    private var currentPageNum = 1
    private var totalPageNum = 1
    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var snowVoiceAction: VoiceAction
    private lateinit var jdVoiceAction: VoiceAction
    private lateinit var zyVoiceAction: VoiceAction

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSystemProp("persist.vendor.boot.pkg", this.packageName)
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        initSDK()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 明确声明允许外部应用发送广播
            registerReceiver(buttonReceiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(buttonReceiver, intentFilter, RECEIVER_EXPORTED)
        }

        binding.keyMark!!.setOnKeyListener { view, keyCode, event ->
            // 触摸板事件
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    Log.d(TAG, "setOnKeyListener keyCode=${keyCode}")
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_ENTER -> {
                            Log.d(TAG, "setOnKeyListener 键盘回车事件")
                            jumpPage()
                        }

                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            Log.d(TAG, "setOnKeyListener 键盘往前键")
                            onGlassKeyEvent(GlassKeyEvent.KEYCODE_FRONT)
                        }

                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            Log.d(TAG, "setOnKeyListener 键盘往后键")
                            onGlassKeyEvent(GlassKeyEvent.KEYCODE_BEHIND)
                        }

                        KeyEvent.KEYCODE_BACK -> {
                            Log.d(TAG, "setOnKeyListener 鼠标右击返回事件")
                            finish()
                        }
                    }
                }
            }
            true
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 优先将事件分发给keyMark视图
        binding.keyMark!!.dispatchKeyEvent(event)
        return true
    }

    override fun onGlassKeyEvent(keyEvent: Int): Boolean {
        when (keyEvent) {
            GlassKeyEvent.KEYCODE_FRONT -> {
                workHomeAdapter?.toNextItem()
            }

            GlassKeyEvent.KEYCODE_BEHIND -> {
                workHomeAdapter?.toPreviousItem()
            }

            GlassKeyEvent.KEYCODE_CLICK -> {
                jumpPage()
            }
        }
        return super.onGlassKeyEvent(keyEvent)
    }

    private fun jumpPage() {
        val startIndex = (currentPageNum - 1) * showMenuMax
        var endIndex = startIndex + showMenuMax
        if (endIndex > menuList.size) {
            endIndex = menuList.size
        }
        workHomeAdapter?.getFocusPosition()?.let { position ->
            if (position < endIndex - startIndex) { // 只处理有效菜单项点击
                jump2NextScene(position)
            }
        }
    }

    @SuppressLint("UseKtx")
    private fun initSDK() {
        GlassSdkUtils.initSdk()
        GlobalData.sdkInitState.drop(1).collect(lifecycleScope) {
            if (it) {
                Log.d(TAG, "SDK 初始化成功")
                initIconData()
                //离线语音转文字,当说打开编号12的警灯将会打印打开编号12的警灯
                jdVoiceAction = VoiceAction("打开编号12的警灯", "da kai bian hao shi er de jing deng", object : IVoiceCallback.Stub() {
                    override fun onVoiceTriggered() {
                        Log.e(TAG, "打开编号12的警灯")
                    }
                })
                GlassSdk.getGlassOfflineCmdService()?.add(jdVoiceAction)
                // 获取离线文本转语音管理模块
//                GlassSdk.getGlassOfflineTtsService()?.playTtsMsg("进入眼镜端演示工程")
                Log.d(TAG, "---眼镜SN号=${GlassSdk.getGlassDeviceService()?.serialNumber}")
                Log.d(TAG, "---电量值=${GlassSdk.getGlassDeviceService()?.deviceStatusInfo?.powerValue}")
                //离线语音转文字,当说下雪了将会打印下雪了
                snowVoiceAction = VoiceAction("下雪了", "xia xue le", object : IVoiceCallback.Stub() {
                    override fun onVoiceTriggered() {
                        Log.e(TAG, "下雪了")
                    }
                })
                GlassSdk.getGlassOfflineCmdService()?.add(snowVoiceAction)

                zyVoiceAction = VoiceAction("智涌", "zhi yong", object : IVoiceCallback.Stub() {
                    override fun onVoiceTriggered() {
                        Log.e(TAG, "智涌")
                    }
                })
                GlassSdk.getGlassOfflineCmdService()?.add(zyVoiceAction)
                AudioService.start(this)
//                startActivity(Intent(this, TestMediaActivity::class.java))
//                startActivity(Intent(this, CameraActivity::class.java))

                //        timeStr: 2026-02-02 14:44:44,1770014682490
                // 设置眼睛端系统时间
                // GlassSdk.getGlassDeviceService()?.setSystemTime(1770014682490)
            } else {
                Log.d(TAG, "SDK 初始化失败")
            }
        }
        GlobalData.btConnectState.drop(1).collect(lifecycleScope) { connectState ->
            if (connectState) {
                binding.tvBtStatus.text = "蓝牙已连接"
            } else {
                binding.tvBtStatus.text = "蓝牙未连接"
            }
        }
        GlobalData.p2pConnectState.drop(1).collect(lifecycleScope) { connectState ->
            if (connectState) {
                binding.tvP2pStatus.text = "P2P已连接"
            } else {
                binding.tvP2pStatus.text = "P2P未连接"
            }
        }
        GlobalData.ringConnectState.drop(1).collect(lifecycleScope) { connectState ->
            if (connectState) {
                binding.ringBtStatus.text = "指环已连接"
            } else {
                binding.ringBtStatus.text = "指环未连接"
            }
        }
        binding.workHomeRecyclerLaunch.run {
            // 使用垂直方向的GridLayoutManager，每行3列
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false).apply {
                // 禁用滑动（通过外部按钮翻页）
                isSmoothScrollbarEnabled = false
            }
            // 禁用所有滚动和越界效果
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            // 设置Adapter（确保Item宽度固定为1/3屏幕）
            adapter = HomeAdapter(context).also {
                workHomeAdapter = it
            }
        }

//
//        TestNetworkActivity.start(this, "https://www.baidu.com")
//        val str = timestampToDateTime(1768467978416)
//        Log.d(TAG,"-----时间=${str}")
    }

    override fun onDestroy() {
        super.onDestroy()
        GlassSdkUtils.destroySdk()
        unregisterReceiver(buttonReceiver)
        if (::snowVoiceAction.isInitialized) {
            GlassSdk.getGlassOfflineCmdService()?.remove(snowVoiceAction)
        }
        if (::jdVoiceAction.isInitialized) {
            GlassSdk.getGlassOfflineCmdService()?.remove(jdVoiceAction)
        }
        if (::zyVoiceAction.isInitialized) {
            GlassSdk.getGlassOfflineCmdService()?.remove(zyVoiceAction)
        }
    }

    private fun initIconData() {
        menuList.run {
            clear()
            add(
                MenuItem(
                    "人脸检测",
                    MenuConfigType.MenuInfoType.MENU_FACE_RECOG,
                    R.mipmap.icon_face_recog,
                    R.mipmap.icon_face_recog,
                    R.drawable.home_bg,
                    R.drawable.home_bg_focus,
                    true
                )
            )

            add(
                MenuItem(
                    //   HomeMenuIndexEnum.FACE_ONLINE_RECOG.chineseName,
                    "音视频发送",
                    MenuConfigType.MenuInfoType.MENU_FACE_ONLINE_RECOG,
                    R.mipmap.icon_face_recog,
                    R.mipmap.icon_face_recog,
                    R.drawable.home_bg,
                    R.drawable.home_bg_focus,
                    false
                )
            )

            add(
                MenuItem(
//                    HomeMenuIndexEnum.LPR_RECOG.chineseName,
                    "车牌识别",
                    MenuConfigType.MenuInfoType.MENU_LPR_RECOG,
                    R.mipmap.icon_car,
                    R.mipmap.icon_car,
                    R.drawable.home_bg,
                    R.drawable.home_bg_focus,
                    false
                )
            )
            add(
                MenuItem(
                    //   HomeMenuIndexEnum.IDCARD_RECOG.chineseName,
                    "消息接收",
                    MenuConfigType.MenuInfoType.RECEIVE_MSG,
                    R.mipmap.icon_card,
                    R.mipmap.icon_card,
                    R.drawable.home_bg,
                    R.drawable.home_bg_focus,
                    false
                )
            )
            add(
                MenuItem(
//                    HomeMenuIndexEnum.AI_CHAT.chineseName,
                    "消息发送",
                    MenuConfigType.MenuInfoType.SEND_MSG,
                    R.mipmap.icon_ai_chat,
                    R.mipmap.icon_ai_chat,
                    R.drawable.home_bg,
                    R.drawable.home_bg_focus,
                    false
                )
            )
//            add(
//                MenuItem(
//                    HomeMenuIndexEnum.TRANSLATE.chineseName,
//                    MenuConfigType.MenuInfoType.MENU_TRANSLATE,
//                    R.mipmap.icon_translate,
//                    R.mipmap.icon_translate,
//                    R.drawable.home_bg,
//                    R.drawable.home_bg_focus,
//                    false
//                )
//            )
            add(
                MenuItem(
                    "拍照",
                    MenuConfigType.MenuInfoType.MENU_TAKE_PHOTO,
                    R.mipmap.icon_take_photo,
                    R.mipmap.icon_take_photo,
                    R.drawable.home_bg,
                    R.drawable.home_bg_focus,
                    false
                )
            )
        }
        totalPageNum = ceil(menuList.size.toDouble() / showMenuMax.toDouble()).toInt()
        if (totalPageNum <= 0) {
            totalPageNum = 1
        }
        currentPageNum = 1
        showPage(currentPageNum)
        workHomeAdapter?.toNextPage?.observe(this) {
            toNextPage()
        }
        workHomeAdapter?.toPreviousPage?.observe(this) {
            toPreviousPage()
        }
    }

    /**
     * 显示某页数
     */
    private fun showPage(pageIndex: Int) {
        Log.d(TAG, "pageIndex:$pageIndex")
        val startIndex = (pageIndex - 1) * showMenuMax
        var endIndex = startIndex + showMenuMax
        if (endIndex > menuList.size) {
            endIndex = menuList.size
        }
        showList.clear()
        showList.addAll(menuList.subList(startIndex, endIndex))
        workHomeAdapter?.run {
            setIconInfoList(showList)
            setFocusPosition(focusPosition)
            notifyDataSetChanged()
            setItemOnClickListener { position: Int, view: View? ->
                Log.d(TAG, "-->position: $position ，endIndex: $endIndex ，startIndex:${startIndex} ，focusPosition：" + focusPosition)
                if (position < endIndex - startIndex) { // 只处理有效菜单项点击
                    jump2NextScene(position)
                }
            }
        }
    }


    /**
     * 翻到上一页
     */
    private fun toPreviousPage() {
        if (currentPageNum > 1) {
            currentPageNum--
            focusPosition = (showMenuMax - 1)
            showPage(currentPageNum)
            Log.d(TAG, "进入上一页")
        }
    }

    /**
     * 向下翻页
     */
    private fun toNextPage() {
        if (currentPageNum < totalPageNum) {
            currentPageNum++
            focusPosition = 0
            showPage(currentPageNum)
            Log.d(TAG, "进入下一页")
        }
    }

    /**
     * 跳到下一个场景
     */
    private fun jump2NextScene(position: Int) {
        val actualPosition = (currentPageNum - 1) * showMenuMax + position
        Log.d(TAG, "jump2NextScene ${menuList[actualPosition].menuType}")
        if (actualPosition >= menuList.size) return
        when (menuList[actualPosition].menuType) {
            MenuConfigType.MenuInfoType.MENU_FACE_RECOG -> {
                startActivity(Intent(this, GlassFaceTrackActivity::class.java))
            }

            MenuConfigType.MenuInfoType.MENU_FACE_ONLINE_RECOG -> {
                if (!MyApplication.sendVideoStatus) {
                    GlassSdk.getGlassMessageService()?.sendVideoStreamData()
                    GlassSdk.getGlassMessageService()?.sendAudioStreamData()
                    Toast.makeText(this, "音视频传输已打开", Toast.LENGTH_SHORT).show()
                    MyApplication.sendVideoStatus = !MyApplication.sendVideoStatus
                } else {
                    GlassSdk.getGlassMessageService()?.stopAudioStreamData()
                    GlassSdk.getGlassMessageService()?.stopVideoStreamData()
                    Toast.makeText(this, "音视频传输已关闭", Toast.LENGTH_SHORT).show()
                    MyApplication.sendVideoStatus = !MyApplication.sendVideoStatus
                }
            }

            MenuConfigType.MenuInfoType.MENU_LPR_RECOG -> {
                startActivity(Intent(this, GlassLprTrackActivity::class.java))
            }

            MenuConfigType.MenuInfoType.RECEIVE_MSG -> {
                startActivity(Intent(this, MessageReceiveActivity::class.java))
            }

            MenuConfigType.MenuInfoType.SEND_MSG -> {
                if (!GlobalData.btConnectState.value) {
                    Toast.makeText(this, "请先连接蓝牙", Toast.LENGTH_SHORT).show()
                    return
                }
                startActivity(Intent(this, SendMessageActivity::class.java))
            }

            MenuConfigType.MenuInfoType.MENU_TAKE_PHOTO -> {
                startActivity(Intent(this, CameraPageActivity::class.java))
            }
        }
    }

    /**
     * 设置应用开机自启动
     */
    fun setSystemProp(key: String?, value: String?) {
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val set: Method = clazz.getMethod("set", String::class.java, String::class.java)
            set.invoke(clazz, key, value)
        } catch (e: Exception) {
        }
    }

    private val mHandler = Handler(Looper.getMainLooper())
    private val mRunnable = Runnable { Log.d(TAG, "眼镜腿收到长点击事件") }

    // 创建广播接收器
    private val buttonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return

            when (action) {
                MyApplication.ORDER_ACTION_BUTTON_CLICK -> {
                    Log.d(TAG, "Single click received")
                    // 在这里添加单击按键的处理逻辑,终止系统广播
                    screenShot()
                }

                MyApplication.ORDER_ACTION_BUTTON_DOUBLE_CLICK -> {
                    Log.d(TAG, "Double click received")
                    // 在这里添加双击按键的处理逻辑
                }

                MyApplication.ACTION_BUTTON_DOWN -> {
                    mHandler.postDelayed(mRunnable, 1200)
                    Log.d(TAG, "ACTION_BUTTON_DOWN received")
                }

                MyApplication.ACTION_BUTTON_UP -> {
                    mHandler.removeCallbacks(mRunnable)
                    Log.d(TAG, "ACTION_BUTTON_UP received")
                }
            }
        }
    }

    // 设置广播过滤器
    private val intentFilter = IntentFilter().apply {
        addAction(MyApplication.ORDER_ACTION_BUTTON_CLICK)
        addAction(MyApplication.ORDER_ACTION_BUTTON_DOUBLE_CLICK)
        addAction(MyApplication.ACTION_BUTTON_DOWN)
        addAction(MyApplication.ACTION_BUTTON_UP)
        addAction(MyApplication.ACTION_CLICK)
        addAction(MyApplication.ACTION_DOUBLE_CLICK)
        addAction(MyApplication.ACTION_AI_START)
        priority = 100 // 设置高优先级确保优先接收广播
    }

    //截图
    fun screenShot() {
//        QuickCameraManager.initialize(true) {
//            if (it) {
//                if (QuickCameraManager.isCameraDoing()) {
//                    runOnUiThread {
//                        SpriteToastUtil.showSpriteToast(MyApplication.getContext(), this.getString(R.string.camera_busy), 0, 1500, true)
//                    }
//                    return@initialize
//                }
//                QuickCameraManager.takePicture { outFile ->
//                    Log.d(TAG, "拍照图片路径---->${outFile?.absolutePath}")
//                }
//            }
//        }
    }

}